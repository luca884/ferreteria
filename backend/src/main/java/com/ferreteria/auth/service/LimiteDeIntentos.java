package com.ferreteria.auth.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Límite de intentos del login, en memoria (una sola instancia del backend; se reinicia con cada deploy).
 *
 * <ul>
 *   <li><b>Por email:</b> cada {@code maxFallosPorEmail} intentos sin un login correcto en el medio, el email queda bloqueado
 *       1, 5 y después 15 minutos (tope). La racha se olvida tras 1 hora sin intentos; un login correcto la borra.</li>
 *   <li><b>Por IP:</b> {@code maxIntentosPorIp} intentos por ventana fija de 1 minuto, correctos o no.</li>
 * </ul>
 *
 * <p>{@link #reservar} va antes del bcrypt: cuenta el intento como fallo de entrada y {@link #exito} lo borra si la
 * contraseña era buena. Así N pedidos en paralelo no pasan todos antes de que se registre el primer fallo. Lo rechazado no
 * se evalúa ni suma. Cada clave se actualiza con {@code compute} (atómico por clave).
 */
@Component
public class LimiteDeIntentos {

    static final List<Duration> BLOQUEOS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    static final Duration OLVIDO = Duration.ofHours(1);
    static final Duration VENTANA_IP = Duration.ofMinutes(1);
    private static final Duration CADA_CUANTO_PURGAR = Duration.ofMinutes(1);
    private static final Pattern MARCAS_E_INVISIBLES = Pattern.compile("[\\p{M}\\p{Cf}]");

    private record RachaDeEmail(int fallos, int nivel, Instant bloqueadoHasta, Instant ultimo) {
        boolean bloqueada(Instant ahora) {
            return bloqueadoHasta != null && ahora.isBefore(bloqueadoHasta);
        }

        boolean olvidada(Instant ahora) {
            return !bloqueada(ahora) && !ahora.isBefore(ultimo.plus(OLVIDO));
        }
    }

    private record VentanaDeIp(Instant inicio, int intentos) {
        Instant fin() {
            return inicio.plus(VENTANA_IP);
        }

        boolean vencida(Instant ahora) {
            return !ahora.isBefore(fin());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LimiteDeIntentos.class);

    private final Clock reloj;
    private final int maxFallosPorEmail;
    private final int maxIntentosPorIp;
    private final ConcurrentHashMap<String, RachaDeEmail> emails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VentanaDeIp> ips = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaPurga;

    public LimiteDeIntentos(Clock reloj,
                            @Value("${app.login.max-fallos-por-email}") int maxFallosPorEmail,
                            @Value("${app.login.max-intentos-por-ip}") int maxIntentosPorIp) {
        this.reloj = reloj;
        this.maxFallosPorEmail = maxFallosPorEmail;
        this.maxIntentosPorIp = maxIntentosPorIp;
        this.ultimaPurga = new AtomicReference<>(reloj.instant());
    }

    /**
     * Reserva un intento para el email desde la IP, o tira {@link DemasiadosIntentosException} con los segundos que faltan.
     * Primero la IP: un intento rechazado por la IP no le suma un fallo al email.
     */
    public void reservar(String email, String ip) {
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);
        if (reservarIp(ip, ahora)) {
            log.warn("Login: la IP {} llegó a {} intentos en el minuto; se bloquea hasta que cierre", ip, maxIntentosPorIp);
        }
        Duration bloqueo = reservarEmail(normalizar(email), ahora);
        if (bloqueo != null) {
            log.warn("Login: {} fallos seguidos de {} (último desde la IP {}); se bloquea el email {} min",
                    maxFallosPorEmail, normalizar(email), ip, bloqueo.toMinutes());
        }
    }

    /** La contraseña era buena: la racha del email se borra. La IP no se descuenta. */
    public void exito(String email) {
        emails.remove(normalizar(email));
    }

    /** Cuántos emails e IPs hay en memoria (para el test de la purga). */
    int entradas() {
        return emails.size() + ips.size();
    }

    /** Reserva el intento de la IP; devuelve true si con este llegó al tope (se registra una vez por bloqueo). */
    private boolean reservarIp(String ip, Instant ahora) {
        long[] espera = {0};
        VentanaDeIp despues = ips.compute(ip, (clave, ventana) -> {
            if (ventana == null || ventana.vencida(ahora)) {
                return new VentanaDeIp(ahora, 1);
            }
            if (ventana.intentos() >= maxIntentosPorIp) {
                espera[0] = segundosHasta(ahora, ventana.fin());
                return ventana;
            }
            return new VentanaDeIp(ventana.inicio(), ventana.intentos() + 1);
        });
        if (espera[0] > 0) {
            throw new DemasiadosIntentosException(espera[0]);
        }
        return despues.intentos() == maxIntentosPorIp;
    }

    /** Reserva el intento del email; devuelve el bloqueo que empieza con este intento, o null si no empieza ninguno. */
    private Duration reservarEmail(String email, Instant ahora) {
        long[] espera = {0};
        Duration[] empieza = {null};
        emails.compute(email, (clave, racha) -> {
            if (racha == null || racha.olvidada(ahora)) {
                racha = new RachaDeEmail(0, 0, null, ahora);
            }
            if (racha.bloqueada(ahora)) {
                espera[0] = segundosHasta(ahora, racha.bloqueadoHasta());
                return racha;
            }
            // Vencido el bloqueo arranca una ronda nueva de fallos; el nivel se mantiene.
            int fallos = (racha.bloqueadoHasta() != null ? 0 : racha.fallos()) + 1;
            if (fallos < maxFallosPorEmail) {
                return new RachaDeEmail(fallos, racha.nivel(), null, ahora);
            }
            Duration bloqueo = BLOQUEOS.get(Math.min(racha.nivel(), BLOQUEOS.size() - 1));
            empieza[0] = bloqueo;
            return new RachaDeEmail(fallos, racha.nivel() + 1, ahora.plus(bloqueo), ahora);
        });
        if (espera[0] > 0) {
            throw new DemasiadosIntentosException(espera[0]);
        }
        return empieza[0];
    }

    /** A lo sumo una vez por minuto recorre los mapas y saca lo vencido: la memoria no crece con emails o IPs viejos. */
    private void purgarSiToca(Instant ahora) {
        Instant anterior = ultimaPurga.get();
        if (ahora.isBefore(anterior.plus(CADA_CUANTO_PURGAR)) || !ultimaPurga.compareAndSet(anterior, ahora)) {
            return;
        }
        emails.entrySet().removeIf(e -> e.getValue().olvidada(ahora));
        ips.entrySet().removeIf(e -> e.getValue().vencida(ahora));
    }

    private static long segundosHasta(Instant ahora, Instant hasta) {
        return Math.max(1, (Duration.between(ahora, hasta).toMillis() + 999) / 1000);
    }

    /**
     * Se acerca a la collation de la columna (utf8mb4_0900_ai_ci): sin tildes, ancho completo a normal (NFKD), sin
     * caracteres invisibles (soft hyphen, ancho cero) y ß como ss. Para un usuario existente la clave ya es su email
     * guardado; esto hace que las variantes de un email inexistente también compartan la racha y no delaten si existe.
     */
    static String normalizar(String email) {
        String descompuesto = Normalizer.normalize(email.trim(), Normalizer.Form.NFKD);
        return MARCAS_E_INVISIBLES.matcher(descompuesto).replaceAll("").toLowerCase(Locale.ROOT).replace("ß", "ss");
    }
}
