package com.ferreteria.auth.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
 *   <li><b>Por email + IP:</b> cada {@code maxFallosPorEmailEIp} fallos sin un login correcto en el medio, ese email queda
 *       bloqueado desde esa IP 1, 5, 15 y después 30 minutos (tope). Quien se equivoca o prueba contraseñas ajenas se
 *       bloquea a sí mismo: el dueño sigue entrando desde la PC de su ferretería (revisión de seguridad del 2026-11-05,
 *       hallazgo 6). Un login correcto borra esta racha.</li>
 *   <li><b>Por email, desde cualquier IP:</b> cada {@code maxFallosPorEmail} fallos, con la misma escalera. Frena al que
 *       prueba desde muchas IPs. Un login correcto no la borra (si no, ese atacante empezaría de cero cada vez que el dueño
 *       entra): solo devuelve su propio intento.</li>
 *   <li><b>Por IP:</b> {@code maxIntentosPorIp} intentos por ventana fija de 1 minuto, correctos o no.</li>
 *   <li><b>Cambiar mi contraseña:</b> la contraseña actual, por usuario, con las reglas del email + IP y su propia racha.</li>
 *   <li><b>Código de la app del super admin</b> (totp-super-admin): por usuario, con las reglas del email + IP y su propia
 *       racha.</li>
 * </ul>
 * Las rachas se olvidan tras 1 hora sin fallos.
 *
 * <p>{@link #reservar} va antes del bcrypt: cuenta el intento como fallo de entrada y {@link #exito} lo descuenta si la
 * contraseña era buena. Así N pedidos en paralelo no pasan todos antes de que se registre el primer fallo. Lo rechazado no
 * se evalúa ni suma en ningún contador: insistir bloqueado no bloquea la cuenta. Cada clave se actualiza con
 * {@code compute} (atómico por clave).
 */
@Component
public class LimiteDeIntentos {

    static final List<Duration> BLOQUEOS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofMinutes(30));
    static final Duration OLVIDO = Duration.ofHours(1);
    static final Duration VENTANA_IP = Duration.ofMinutes(1);
    private static final Duration CADA_CUANTO_PURGAR = Duration.ofMinutes(1);
    private static final Pattern MARCAS_E_INVISIBLES = Pattern.compile("[\\p{M}\\p{Cf}]");
    /** Lo único que puede tener una IPv6 escrita como texto (sin la zona): se lee sin consultar el DNS. */
    private static final Pattern DIRECCION_IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    /**
     * Una racha: sus fallos, cuántos bloqueos lleva, hasta cuándo está bloqueada, la hora del último intento y la del
     * anterior (para que un login correcto, al devolver su intento, devuelva también la hora).
     */
    private record Racha(int fallos, int nivel, Instant bloqueadoHasta, Instant ultimo, Instant anterior) {
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
    private final int maxFallosPorEmailEIp;
    private final int maxFallosPorEmail;
    private final int maxIntentosPorIp;
    /** Las rachas de cada email, desde cualquier IP. */
    private final ConcurrentHashMap<String, Racha> emails = new ConcurrentHashMap<>();
    /** Las rachas de cada email desde cada IP (clave: email, espacio, clave de la IP). */
    private final ConcurrentHashMap<String, Racha> pares = new ConcurrentHashMap<>();
    /** Las rachas de «Cambiar mi contraseña», por id de usuario. */
    private final ConcurrentHashMap<Long, Racha> cambios = new ConcurrentHashMap<>();
    /** Las rachas del código de la app del super admin, por id de usuario. */
    private final ConcurrentHashMap<Long, Racha> codigos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VentanaDeIp> ips = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaPurga;

    public LimiteDeIntentos(Clock reloj,
                            @Value("${app.login.max-fallos-por-email-e-ip}") int maxFallosPorEmailEIp,
                            @Value("${app.login.max-fallos-por-email}") int maxFallosPorEmail,
                            @Value("${app.login.max-intentos-por-ip}") int maxIntentosPorIp) {
        this.reloj = reloj;
        this.maxFallosPorEmailEIp = maxFallosPorEmailEIp;
        this.maxFallosPorEmail = maxFallosPorEmail;
        this.maxIntentosPorIp = maxIntentosPorIp;
        this.ultimaPurga = new AtomicReference<>(reloj.instant());
    }

    /**
     * Reserva un intento para el email desde la IP, o tira {@link DemasiadosIntentosException} con los segundos que faltan.
     * Primero la IP, después el email (solo se mira, sin contar) y el email + IP: un intento rechazado por cualquiera de
     * ellos no suma en los otros. Si se acepta, cuenta como fallo del email + IP y del email.
     */
    public void reservar(String email, String ip) {
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);
        String claveIp = claveDeIp(ip);
        String clave = normalizar(email);
        if (reservarIp(claveIp, ahora)) {
            log.warn("Login: la IP {} llegó a {} intentos en el minuto; se bloquea hasta que cierre", ip, maxIntentosPorIp);
        }
        rechazarSiEstaBloqueada(emails.get(clave), ahora);
        Duration bloqueoDelPar = reservarRacha(pares, par(clave, claveIp), ahora, maxFallosPorEmailEIp);
        if (bloqueoDelPar != null) {
            log.warn("Login: {} fallos seguidos de {} desde la IP {}; se bloquea {} min desde esa IP",
                    maxFallosPorEmailEIp, clave, ip, bloqueoDelPar.toMinutes());
        }
        Duration bloqueoDelEmail = reservarRacha(emails, clave, ahora, maxFallosPorEmail);
        if (bloqueoDelEmail != null) {
            log.warn("Login: {} fallos de {} desde distintas IPs (el último desde {}); se bloquea el email {} min",
                    maxFallosPorEmail, clave, ip, bloqueoDelEmail.toMinutes());
        }
    }

    /**
     * La contraseña era buena: la racha del email desde esa IP se borra, y al email se le devuelve el intento que se
     * reservó (sin borrar los fallos de otras IPs). La IP no se descuenta.
     */
    public void exito(String email, String ip) {
        String clave = normalizar(email);
        pares.remove(par(clave, claveDeIp(ip)));
        devolverIntento(clave);
    }

    /**
     * Reserva un intento de «Cambiar mi contraseña» del usuario, antes de comparar la actual, con las mismas reglas que el
     * email + IP del login y una racha propia (revisión de seguridad del 2026-11-05, hallazgo 7: con un token robado se
     * probaba la contraseña actual sin freno). Tira {@link DemasiadosIntentosException} si está bloqueado.
     */
    public void reservarCambioDeContrasena(long usuarioId) {
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);
        Duration bloqueo = reservarRacha(cambios, usuarioId, ahora, maxFallosPorEmailEIp);
        if (bloqueo != null) {
            log.warn("Cambio de contraseña: {} actuales incorrectas seguidas del usuario {}; se bloquea {} min",
                    maxFallosPorEmailEIp, usuarioId, bloqueo.toMinutes());
        }
    }

    /** La contraseña actual era buena: la racha del cambio se borra. */
    public void exitoCambioDeContrasena(long usuarioId) {
        cambios.remove(usuarioId);
    }

    /**
     * Reserva un intento del código de la app del super admin (totp-super-admin, S5d), antes de compararlo, con las mismas
     * reglas que el email + IP del login y una racha propia por usuario. Tira {@link DemasiadosIntentosException} si está
     * bloqueado. El log lleva solo el id del usuario, nunca el código.
     */
    public void reservarCodigoDeLaApp(long usuarioId) {
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);
        Duration bloqueo = reservarRacha(codigos, usuarioId, ahora, maxFallosPorEmailEIp);
        if (bloqueo != null) {
            log.warn("Código de la app: {} códigos incorrectos seguidos del usuario {}; se bloquea {} min",
                    maxFallosPorEmailEIp, usuarioId, bloqueo.toMinutes());
        }
    }

    /** El código de la app era bueno: la racha se borra. */
    public void exitoCodigoDeLaApp(long usuarioId) {
        codigos.remove(usuarioId);
    }

    /** Cuántos emails, pares email + IP, usuarios (cambio de contraseña y código de la app) e IPs hay en memoria. */
    int entradas() {
        return emails.size() + pares.size() + cambios.size() + codigos.size() + ips.size();
    }

    private static String par(String email, String claveIp) {
        return email + " " + claveIp;
    }

    /** Reserva el intento de la IP; devuelve true si con este llegó al tope (se registra una vez por bloqueo). */
    private boolean reservarIp(String claveIp, Instant ahora) {
        long[] espera = {0};
        VentanaDeIp despues = ips.compute(claveIp, (clave, ventana) -> {
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

    private static void rechazarSiEstaBloqueada(Racha racha, Instant ahora) {
        if (racha != null && racha.bloqueada(ahora)) {
            throw new DemasiadosIntentosException(segundosHasta(ahora, racha.bloqueadoHasta()));
        }
    }

    /**
     * Le devuelve al email el intento que reservó un login correcto. Si ese intento fue el que llegó al tope y empezó el
     * bloqueo, el bloqueo se deshace: el login correcto nunca bloquea la cuenta.
     */
    private void devolverIntento(String clave) {
        emails.computeIfPresent(clave, (k, racha) -> {
            // La hora vuelve a la del intento anterior: un login correcto no mantiene viva la racha (se olvida tras 1 hora
            // sin fallos aunque el dueño entre seguido).
            if (racha.bloqueadoHasta() == null) {
                int fallos = racha.fallos() - 1;
                return fallos <= 0 && racha.nivel() == 0 ? null : new Racha(Math.max(0, fallos), racha.nivel(), null,
                        racha.anterior(), racha.anterior());
            }
            if (racha.fallos() >= maxFallosPorEmail) {
                return new Racha(racha.fallos() - 1, Math.max(0, racha.nivel() - 1), null, racha.anterior(),
                        racha.anterior());
            }
            return racha;
        });
    }

    /**
     * Reserva un intento de la racha de {@code clave} en {@code rachas}: tira {@link DemasiadosIntentosException} si esta
     * bloqueada, y si no devuelve el bloqueo que empieza con este intento, o null si no empieza ninguno.
     */
    private static <K> Duration reservarRacha(ConcurrentHashMap<K, Racha> rachas, K clave, Instant ahora,
                                              int maxFallos) {
        long[] espera = {0};
        Duration[] empieza = {null};
        rachas.compute(clave, (k, racha) -> {
            if (racha == null || racha.olvidada(ahora)) {
                racha = new Racha(0, 0, null, ahora, ahora);
            }
            if (racha.bloqueada(ahora)) {
                espera[0] = segundosHasta(ahora, racha.bloqueadoHasta());
                return racha;
            }
            // Vencido el bloqueo arranca una ronda nueva de fallos; el nivel se mantiene.
            int fallos = (racha.bloqueadoHasta() != null ? 0 : racha.fallos()) + 1;
            if (fallos < maxFallos) {
                return new Racha(fallos, racha.nivel(), null, ahora, racha.ultimo());
            }
            Duration bloqueo = BLOQUEOS.get(Math.min(racha.nivel(), BLOQUEOS.size() - 1));
            empieza[0] = bloqueo;
            return new Racha(fallos, racha.nivel() + 1, ahora.plus(bloqueo), ahora, racha.ultimo());
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
        pares.entrySet().removeIf(e -> e.getValue().olvidada(ahora));
        cambios.entrySet().removeIf(e -> e.getValue().olvidada(ahora));
        codigos.entrySet().removeIf(e -> e.getValue().olvidada(ahora));
        ips.entrySet().removeIf(e -> e.getValue().vencida(ahora));
    }

    /**
     * La clave del límite por IP. Una IPv6 cuenta por su /64: un proveedor le da al cliente un /64 entero, y rotando
     * dentro de él cada intento tendría su propio límite (revisión de seguridad del 2026-11-05, hallazgo 3). Una IPv4
     * escrita como IPv6 ({@code ::ffff:a.b.c.d}) es esa IPv4 y la zona ({@code %eth0}) no cuenta. Solo se interpreta un
     * texto hecho de dígitos hexadecimales, «:» y «.»: {@link InetAddress#getByName} lo lee como dirección literal y no
     * consulta el DNS (con un texto como {@code zz:1} sí lo haría). Cualquier otro texto queda tal cual.
     */
    static String claveDeIp(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        int zona = ip.indexOf('%');
        String sinZona = zona < 0 ? ip : ip.substring(0, zona);
        if (!DIRECCION_IPV6.matcher(sinZona).matches()) {
            return ip;
        }
        try {
            InetAddress direccion = InetAddress.getByName(sinZona);
            if (direccion instanceof Inet4Address) {
                return direccion.getHostAddress();
            }
            byte[] bytes = direccion.getAddress();
            Arrays.fill(bytes, 8, bytes.length, (byte) 0);
            return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            return ip;
        }
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
