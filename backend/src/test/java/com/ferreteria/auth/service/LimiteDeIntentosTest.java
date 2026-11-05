package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.RelojControlable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Límite de intentos del login (hallazgo 1 de la revisión de seguridad): 5 fallos seguidos bloquean el email 1, 5 y 15
 * minutos; una IP tiene 20 intentos por minuto. Lo bloqueado no se evalúa (no gasta el bcrypt).
 */
class LimiteDeIntentosTest {

    private static final String IP = "203.0.113.7";
    private static final String OTRA_IP = "198.51.100.9";

    private RelojControlable reloj;
    private LimiteDeIntentos limite;

    @BeforeEach
    void armar() {
        reloj = new RelojControlable(Instant.parse("2026-11-02T12:00:00Z"));
        limite = new LimiteDeIntentos(reloj, 5, 20);
    }

    /** Un intento fallido: pasa el límite y no hay éxito después. */
    private void fallar(String email, String ip) {
        limite.reservar(email, ip);
    }

    private void fallarVeces(int veces, String email) {
        for (int i = 0; i < veces; i++) {
            fallar(email, "10.0.0." + i);
        }
    }

    private static long segundosDe(Runnable intento) {
        try {
            intento.run();
        } catch (DemasiadosIntentosException e) {
            return e.segundos();
        }
        throw new AssertionError("se esperaba DemasiadosIntentosException");
    }

    @Nested
    @DisplayName("por email")
    class PorEmail {

        @Test
        @DisplayName("5 fallos se evalúan y el sexto intento queda bloqueado 1 minuto")
        void cincoFallosBloqueanUnMinuto() {
            fallarVeces(5, "dueno@x.test");

            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
        }

        @Test
        @DisplayName("el aviso dice cuántos minutos faltan, redondeando hacia arriba y en singular con 1")
        void mensaje() {
            fallarVeces(5, "dueno@x.test");

            assertThatThrownBy(() -> limite.reservar("dueno@x.test", OTRA_IP))
                    .hasMessage("Demasiados intentos. Probá de nuevo en 1 minuto.");
            reloj.avanzar(Duration.ofSeconds(60));
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofSeconds(61));
            assertThatThrownBy(() -> limite.reservar("dueno@x.test", OTRA_IP))
                    .hasMessage("Demasiados intentos. Probá de nuevo en 4 minutos.");
        }

        @Test
        @DisplayName("el bloqueo crece a 5 y a 15 minutos, y no pasa de 15")
        void bloqueoCreciente() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(1));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(300);
            reloj.avanzar(Duration.ofMinutes(5));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(900);
            reloj.avanzar(Duration.ofMinutes(15));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(900);
        }

        @Test
        @DisplayName("vencido el bloqueo vuelve a evaluar, y el bloqueo no se alarga con los intentos rechazados")
        void vencidoVuelveAEvaluar() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofSeconds(30));
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(30);
            reloj.avanzar(Duration.ofSeconds(30));

            assertThatCode(() -> limite.reservar("dueno@x.test", OTRA_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("un login correcto vuelve el contador y el nivel a cero")
        void exitoResetea() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(1));
            fallarVeces(4, "dueno@x.test");
            limite.reservar("dueno@x.test", OTRA_IP);
            limite.exito("dueno@x.test");

            fallarVeces(4, "dueno@x.test");
            assertThatCode(() -> limite.reservar("dueno@x.test", OTRA_IP)).doesNotThrowAnyException();
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
        }

        @Test
        @DisplayName("la racha se olvida tras 1 hora sin fallos: el bloqueo siguiente vuelve a 1 minuto")
        void rachaOlvidada() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(1));
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(5).plusHours(1));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
        }

        @Test
        @DisplayName("el email cuenta igual con mayúsculas y espacios")
        void emailNormalizado() {
            fallarVeces(3, "Dueno@X.test");
            fallarVeces(2, "  dueno@x.TEST ");

            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
        }

        @Test
        @DisplayName("las variantes que MySQL da por iguales (tildes, ancho completo, invisibles, ß) comparten la racha")
        void variantesDeLaCollation() {
            fallar("dueno@x.test", OTRA_IP);
            fallar("duenó@x.test", OTRA_IP);
            fallar("due­no@x.test", OTRA_IP);
            fallar("ｄｕｅｎｏ@x.test", OTRA_IP);
            fallar("DUENO@x.test", OTRA_IP);
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);

            fallarVeces(5, "straße@x.test");
            assertThat(segundosDe(() -> limite.reservar("strasse@x.test", OTRA_IP))).isEqualTo(60);
        }

        @Test
        @DisplayName("otro email no se ve afectado")
        void otroEmail() {
            fallarVeces(5, "dueno@x.test");

            assertThatCode(() -> limite.reservar("vendedor@x.test", OTRA_IP)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("cambiar mi contraseña (hallazgo 7 de la revisión del 2026-11-05)")
    class CambioDeContrasena {

        private void fallarCambio(int veces, long usuario) {
            for (int i = 0; i < veces; i++) {
                limite.reservarCambioDeContrasena(usuario);
            }
        }

        @Test
        @DisplayName("5 actuales malas se evalúan y el sexto intento queda bloqueado 1 minuto, con el mismo aviso")
        void cincoFallosBloqueanUnMinuto() {
            fallarCambio(5, 7L);

            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(60);
            assertThatThrownBy(() -> limite.reservarCambioDeContrasena(7L))
                    .hasMessage("Demasiados intentos. Probá de nuevo en 1 minuto.");
        }

        @Test
        @DisplayName("el bloqueo crece a 5 y a 15 minutos, como el del login")
        void bloqueoCreciente() {
            fallarCambio(5, 7L);
            reloj.avanzar(Duration.ofMinutes(1));
            fallarCambio(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(300);
            reloj.avanzar(Duration.ofMinutes(5));
            fallarCambio(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(900);
        }

        @Test
        @DisplayName("una actual correcta borra la racha")
        void exitoResetea() {
            fallarCambio(4, 7L);
            limite.exitoCambioDeContrasena(7L);

            fallarCambio(4, 7L);
            assertThatCode(() -> limite.reservarCambioDeContrasena(7L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("otro usuario no se ve afectado, y la racha no se mezcla con la del login")
        void cadaUsuarioYCadaOperacionTieneSuRacha() {
            fallarCambio(5, 7L);

            assertThatCode(() -> limite.reservarCambioDeContrasena(8L)).doesNotThrowAnyException();
            assertThatCode(() -> limite.reservar("7", IP)).doesNotThrowAnyException();
            fallarVeces(5, "dueno@x.test");
            assertThatCode(() -> limite.reservarCambioDeContrasena(9L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("la racha vencida se purga")
        void purga() {
            fallarCambio(3, 7L);
            reloj.avanzar(Duration.ofHours(1).plusSeconds(1));

            limite.reservar("nuevo@x.test", IP);

            assertThat(limite.entradas()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("por IP")
    class PorIp {

        @Test
        @DisplayName("20 intentos por minuto con emails distintos; el 21 queda bloqueado hasta que cierra el minuto")
        void veinteIntentosPorMinuto() {
            for (int i = 0; i < 20; i++) {
                limite.reservar("u" + i + "@x.test", IP);
            }
            reloj.avanzar(Duration.ofSeconds(15));

            assertThat(segundosDe(() -> limite.reservar("otro@x.test", IP))).isEqualTo(45);
            assertThatCode(() -> limite.reservar("otro@x.test", OTRA_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("cerrado el minuto, la IP vuelve a tener sus 20 intentos")
        void ventanaNueva() {
            for (int i = 0; i < 20; i++) {
                limite.reservar("u" + i + "@x.test", IP);
            }
            reloj.avanzar(Duration.ofMinutes(1));

            for (int i = 0; i < 20; i++) {
                limite.reservar("v" + i + "@x.test", IP);
            }
            assertThatThrownBy(() -> limite.reservar("w@x.test", IP)).isInstanceOf(DemasiadosIntentosException.class);
        }

        @Test
        @DisplayName("los intentos correctos también cuentan para la IP")
        void exitosCuentan() {
            for (int i = 0; i < 20; i++) {
                limite.reservar("dueno@x.test", IP);
                limite.exito("dueno@x.test");
            }

            assertThatThrownBy(() -> limite.reservar("dueno@x.test", IP)).isInstanceOf(DemasiadosIntentosException.class);
        }

        @Test
        @DisplayName("un intento rechazado por la IP no suma un fallo al email")
        void rechazadoPorIpNoSumaAlEmail() {
            for (int i = 0; i < 20; i++) {
                limite.reservar("u" + i + "@x.test", IP);
            }
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> limite.reservar("dueno@x.test", IP))
                        .isInstanceOf(DemasiadosIntentosException.class);
            }

            fallarVeces(4, "dueno@x.test");
            assertThatCode(() -> limite.reservar("dueno@x.test", OTRA_IP)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("lo vencido se purga: miles de emails e IPs de hace más de una hora no quedan en memoria")
    void purga() {
        for (int i = 0; i < 12_000; i++) {
            limite.reservar("u" + i + "@x.test", "10.1." + (i / 250) + "." + (i % 250));
        }
        reloj.avanzar(Duration.ofHours(1).plusSeconds(1));

        limite.reservar("nuevo@x.test", IP);

        assertThat(limite.entradas()).isEqualTo(2);
    }

    @Test
    @DisplayName("con intentos en paralelo sobre el mismo email se evalúan exactamente 5")
    void concurrenciaPorEmail() throws Exception {
        assertThat(evaluadosEnParalelo(i -> "dueno@x.test", i -> "10.2.0." + (i % 200))).isEqualTo(5);
    }

    @Test
    @DisplayName("con intentos en paralelo desde la misma IP se evalúan exactamente 20")
    void concurrenciaPorIp() throws Exception {
        assertThat(evaluadosEnParalelo(i -> "p" + i + "@x.test", i -> IP)).isEqualTo(20);
    }

    private long evaluadosEnParalelo(java.util.function.IntFunction<String> email, java.util.function.IntFunction<String> ip)
            throws Exception {
        int hilos = 64;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Boolean>> resultados = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int n = i;
                Callable<Boolean> intento = () -> {
                    largada.await();
                    try {
                        limite.reservar(email.apply(n), ip.apply(n));
                        return true;
                    } catch (DemasiadosIntentosException e) {
                        return false;
                    }
                };
                resultados.add(pool.submit(intento));
            }
            largada.countDown();
            long evaluados = 0;
            for (Future<Boolean> r : resultados) {
                if (r.get()) {
                    evaluados++;
                }
            }
            return evaluados;
        } finally {
            pool.shutdownNow();
        }
    }
}
