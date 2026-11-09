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
    /** La PC del mostrador desde la que entra el dueño. */
    private static final String IP_DEL_DUENO = "192.0.2.10";
    /** Alguien que prueba contraseñas del dueño desde otro lado (hallazgo 6). */
    private static final String IP_DE_UN_TERCERO = "192.0.2.66";

    private RelojControlable reloj;
    private LimiteDeIntentos limite;

    @BeforeEach
    void armar() {
        reloj = new RelojControlable(Instant.parse("2026-11-02T12:00:00Z"));
        limite = new LimiteDeIntentos(reloj, 5, 50, 20);
    }

    /** Un intento fallido: pasa el límite y no hay éxito después. */
    private void fallar(String email, String ip) {
        limite.reservar(email, ip);
    }

    /** Fallos del email desde la IP del dueño. */
    private void fallarVeces(int veces, String email) {
        for (int i = 0; i < veces; i++) {
            fallar(email, IP_DEL_DUENO);
        }
    }

    /** Un fallo del email desde cada una de {@code veces} IPs distintas. */
    private void fallarDesdeIpsDistintas(int veces, String email) {
        for (int i = 0; i < veces; i++) {
            fallar(email, "10.9." + (i / 250) + "." + (i % 250));
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
    @DisplayName("por email + IP (hallazgo 6: el bloqueo es de quien se equivoca, no de la cuenta)")
    class PorEmailEIp {

        @Test
        @DisplayName("5 fallos se evalúan y el sexto intento desde esa IP queda bloqueado 1 minuto")
        void cincoFallosBloqueanUnMinuto() {
            fallarVeces(5, "dueno@x.test");

            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(60);
        }

        @Test
        @DisplayName("hallazgo 6: 5 fallos de un tercero bloquean su IP, y el dueño sigue entrando desde la suya")
        void unTerceroNoBloqueaAlDueno() {
            for (int i = 0; i < 5; i++) {
                fallar("dueno@x.test", IP_DE_UN_TERCERO);
            }
            for (int i = 0; i < 100; i++) {
                assertThatThrownBy(() -> limite.reservar("dueno@x.test", IP_DE_UN_TERCERO))
                        .as("el tercero insiste bloqueado").isInstanceOf(DemasiadosIntentosException.class);
            }

            assertThatCode(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("el aviso dice cuántos minutos faltan, redondeando hacia arriba y en singular con 1")
        void mensaje() {
            fallarVeces(5, "dueno@x.test");

            assertThatThrownBy(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))
                    .hasMessage("Demasiados intentos. Probá de nuevo en 1 minuto.");
            reloj.avanzar(Duration.ofSeconds(60));
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofSeconds(61));
            assertThatThrownBy(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))
                    .hasMessage("Demasiados intentos. Probá de nuevo en 4 minutos.");
        }

        @Test
        @DisplayName("cada 5 fallos el bloqueo crece: 1, 5, 15 y 30 minutos, y no pasa de media hora")
        void bloqueoCreciente() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(1));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(300);
            reloj.avanzar(Duration.ofMinutes(5));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(900);
            reloj.avanzar(Duration.ofMinutes(15));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(1800);
            reloj.avanzar(Duration.ofMinutes(30));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(1800);
        }

        @Test
        @DisplayName("vencido el bloqueo vuelve a evaluar, y el bloqueo no se alarga con los intentos rechazados")
        void vencidoVuelveAEvaluar() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofSeconds(30));
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(30);
            reloj.avanzar(Duration.ofSeconds(30));

            assertThatCode(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("un login correcto vuelve el contador y el nivel de esa IP a cero")
        void exitoResetea() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(1));
            fallarVeces(4, "dueno@x.test");
            limite.reservar("dueno@x.test", IP_DEL_DUENO);
            limite.exito("dueno@x.test", IP_DEL_DUENO);

            fallarVeces(4, "dueno@x.test");
            assertThatCode(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO)).doesNotThrowAnyException();
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(60);
        }

        @Test
        @DisplayName("la racha se olvida tras 1 hora sin fallos: el bloqueo siguiente vuelve a 1 minuto")
        void rachaOlvidada() {
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(1));
            fallarVeces(5, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(5).plusHours(1));

            fallarVeces(5, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(60);
        }

        @Test
        @DisplayName("el email cuenta igual con mayúsculas y espacios")
        void emailNormalizado() {
            fallarVeces(3, "Dueno@X.test");
            fallarVeces(2, "  dueno@x.TEST ");

            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(60);
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
            assertThat(segundosDe(() -> limite.reservar("strasse@x.test", IP_DEL_DUENO))).isEqualTo(60);
        }

        @Test
        @DisplayName("una IPv6 del mismo /64 es la misma IP también para el email")
        void ipv6DelMismoSesentaYCuatro() {
            for (int i = 1; i <= 5; i++) {
                fallar("dueno@x.test", "2001:db8:1:2::" + i);
            }

            assertThatThrownBy(() -> limite.reservar("dueno@x.test", "2001:db8:1:2::99"))
                    .isInstanceOf(DemasiadosIntentosException.class);
        }

        @Test
        @DisplayName("otro email no se ve afectado")
        void otroEmail() {
            fallarVeces(5, "dueno@x.test");

            assertThatCode(() -> limite.reservar("vendedor@x.test", IP_DEL_DUENO)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("por email, desde cualquier IP (frena al que prueba desde muchas IPs)")
    class PorEmailDesdeCualquierIp {

        @Test
        @DisplayName("50 fallos desde IPs distintas bloquean el email 1 minuto desde todas, aunque sea una IP nueva")
        void cincuentaFallosBloqueanElEmail() {
            fallarDesdeIpsDistintas(49, "dueno@x.test");
            assertThatCode(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO)).doesNotThrowAnyException();

            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(60);
            assertThatCode(() -> limite.reservar("vendedor@x.test", OTRA_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("el bloqueo del email crece igual: 1, 5, 15 y 30 minutos")
        void bloqueoCreciente() {
            long[] esperados = {60, 300, 900, 1800, 1800};
            for (long esperado : esperados) {
                fallarDesdeIpsDistintas(50, "dueno@x.test");
                assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(esperado);
                reloj.avanzar(Duration.ofSeconds(esperado));
            }
        }

        @Test
        @DisplayName("los intentos rechazados no suman: un tercero que insiste bloqueado nunca bloquea la cuenta")
        void rechazadosNoSuman() {
            for (int i = 0; i < 5; i++) {
                fallar("dueno@x.test", IP_DE_UN_TERCERO);
            }
            for (int i = 0; i < 200; i++) {
                assertThatThrownBy(() -> limite.reservar("dueno@x.test", IP_DE_UN_TERCERO))
                        .isInstanceOf(DemasiadosIntentosException.class);
            }
            fallarDesdeIpsDistintas(44, "dueno@x.test");

            assertThatCode(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("un login correcto no borra la racha del email: el que prueba desde muchas IPs no vuelve a empezar")
        void exitoNoBorraLaRachaDelEmail() {
            fallarDesdeIpsDistintas(45, "dueno@x.test");
            limite.reservar("dueno@x.test", IP_DEL_DUENO);
            limite.exito("dueno@x.test", IP_DEL_DUENO);
            fallarDesdeIpsDistintas(5, "dueno@x.test");

            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
        }

        @Test
        @DisplayName("los logins correctos no cuentan como fallos del email: el dueño que entra 60 veces en una hora no se bloquea")
        void loginsCorrectosNoCuentan() {
            for (int i = 0; i < 60; i++) {
                if (i % 15 == 0) {
                    reloj.avanzar(Duration.ofMinutes(1));
                }
                limite.reservar("dueno@x.test", IP_DEL_DUENO);
                limite.exito("dueno@x.test", IP_DEL_DUENO);
            }

            assertThatCode(() -> limite.reservar("dueno@x.test", OTRA_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("los logins correctos no mantienen viva la racha: 2 horas sin fallos la olvidan aunque el dueño entre seguido")
        void loginsCorrectosNoRenuevanLaRacha() {
            fallarDesdeIpsDistintas(49, "dueno@x.test");
            reloj.avanzar(Duration.ofMinutes(50));
            limite.reservar("dueno@x.test", IP_DEL_DUENO);
            limite.exito("dueno@x.test", IP_DEL_DUENO);
            reloj.avanzar(Duration.ofMinutes(50));
            limite.reservar("dueno@x.test", IP_DEL_DUENO);
            limite.exito("dueno@x.test", IP_DEL_DUENO);
            reloj.avanzar(Duration.ofMinutes(20));

            fallar("dueno@x.test", "8.8.8.8");
            assertThatCode(() -> limite.reservar("dueno@x.test", OTRA_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("después de 3 bloqueos, horas sin fallos con logins correctos dejan el bloqueo siguiente en 1 minuto")
        void loginsCorrectosNoMantienenElNivel() {
            for (long esperado : new long[] {60, 300, 900}) {
                fallarDesdeIpsDistintas(50, "dueno@x.test");
                reloj.avanzar(Duration.ofSeconds(esperado));
            }
            for (int i = 0; i < 8; i++) {
                reloj.avanzar(Duration.ofMinutes(40));
                limite.reservar("dueno@x.test", IP_DEL_DUENO);
                limite.exito("dueno@x.test", IP_DEL_DUENO);
            }

            fallarDesdeIpsDistintas(50, "dueno@x.test");
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", IP_DEL_DUENO))).isEqualTo(60);
        }

        @Test
        @DisplayName("si el intento que llega al tope es un login correcto, la cuenta no queda bloqueada")
        void elLoginCorrectoQueLlegaAlTopeNoBloquea() {
            fallarDesdeIpsDistintas(49, "dueno@x.test");
            limite.reservar("dueno@x.test", IP_DEL_DUENO);
            limite.exito("dueno@x.test", IP_DEL_DUENO);

            assertThatCode(() -> limite.reservar("dueno@x.test", OTRA_IP)).doesNotThrowAnyException();
            assertThat(segundosDe(() -> limite.reservar("dueno@x.test", OTRA_IP))).isEqualTo(60);
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
        @DisplayName("el bloqueo crece como el del login: 1, 5, 15 y 30 minutos, y no pasa de media hora")
        void bloqueoCreciente() {
            fallarCambio(5, 7L);
            reloj.avanzar(Duration.ofMinutes(1));
            fallarCambio(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(300);
            reloj.avanzar(Duration.ofMinutes(5));
            fallarCambio(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(900);
            reloj.avanzar(Duration.ofMinutes(15));
            fallarCambio(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(1800);
            reloj.avanzar(Duration.ofMinutes(30));
            fallarCambio(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCambioDeContrasena(7L))).isEqualTo(1800);
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

            assertThat(limite.entradas()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("el código de la app del super admin (totp-super-admin, S5d)")
    class CodigoDeLaApp {

        private void fallarCodigo(int veces, long usuario) {
            for (int i = 0; i < veces; i++) {
                limite.reservarCodigoDeLaApp(usuario);
            }
        }

        @Test
        @DisplayName("S5d: 5 códigos mal bloquean al usuario con la escalera del login: 1, 5, 15 y 30 minutos, con el mismo aviso")
        void escaleraDelLogin() {
            fallarCodigo(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCodigoDeLaApp(7L))).isEqualTo(60);
            assertThatThrownBy(() -> limite.reservarCodigoDeLaApp(7L))
                    .hasMessage("Demasiados intentos. Probá de nuevo en 1 minuto.");
            reloj.avanzar(Duration.ofMinutes(1));
            fallarCodigo(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCodigoDeLaApp(7L))).isEqualTo(300);
            reloj.avanzar(Duration.ofMinutes(5));
            fallarCodigo(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCodigoDeLaApp(7L))).isEqualTo(900);
            reloj.avanzar(Duration.ofMinutes(15));
            fallarCodigo(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCodigoDeLaApp(7L))).isEqualTo(1800);
            reloj.avanzar(Duration.ofMinutes(30));
            fallarCodigo(5, 7L);
            assertThat(segundosDe(() -> limite.reservarCodigoDeLaApp(7L))).isEqualTo(1800);
        }

        @Test
        @DisplayName("S5d: el código bien reinicia la racha")
        void exitoResetea() {
            fallarCodigo(4, 7L);
            limite.exitoCodigoDeLaApp(7L);

            fallarCodigo(4, 7L);
            assertThatCode(() -> limite.reservarCodigoDeLaApp(7L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("S5d: la racha es del usuario y no se mezcla con la del login ni con la de cambiar la contraseña; vencida, se purga")
        void rachaPropia() {
            fallarCodigo(5, 7L);

            assertThatCode(() -> limite.reservarCodigoDeLaApp(8L)).doesNotThrowAnyException();
            assertThatCode(() -> limite.reservarCambioDeContrasena(7L)).doesNotThrowAnyException();
            assertThatCode(() -> limite.reservar("7", IP)).doesNotThrowAnyException();
            assertThat(limite.entradas()).as("los usuarios 7 y 8 del código, el 7 del cambio, el email, su par y la IP")
                    .isEqualTo(6);
            reloj.avanzar(Duration.ofHours(1).plusMinutes(1));
            limite.reservar("nuevo@x.test", OTRA_IP);
            assertThat(limite.entradas()).as("el email, su par con la IP y la IP del intento nuevo").isEqualTo(3);
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
        @DisplayName("hallazgo 3: las IPv6 cuentan por su /64, que es lo que tiene un cliente; otro /64 no se ve afectado")
        void ipv6PorSuSesentaYCuatro() {
            for (int i = 0; i < 20; i++) {
                limite.reservar("u" + i + "@x.test", "2001:db8:1:2:" + Integer.toHexString(i) + "::" + (i + 1));
            }

            assertThatThrownBy(() -> limite.reservar("otro@x.test", "2001:db8:1:2:ffff:ffff:ffff:ffff"))
                    .isInstanceOf(DemasiadosIntentosException.class);
            assertThatThrownBy(() -> limite.reservar("otro@x.test", "2001:0db8:0001:0002:0000:0000:0000:0001"))
                    .as("la misma IPv6 escrita entera").isInstanceOf(DemasiadosIntentosException.class);
            assertThatCode(() -> limite.reservar("otro@x.test", "2001:db8:1:3::1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("la zona de una IPv6 (%eth0) no cambia la clave, y un texto que no es una dirección queda tal cual, sin DNS")
        void claveDeIpConZonaYTextosQueNoSonDirecciones() {
            assertThat(LimiteDeIntentos.claveDeIp("fe80::1%nope1")).isEqualTo(LimiteDeIntentos.claveDeIp("fe80::2%nope2"))
                    .isEqualTo(LimiteDeIntentos.claveDeIp("fe80::3"));
            for (String texto : new String[] {"zz:1", "g:", "abc:def", "localhost:80", "[::1]"}) {
                assertThat(LimiteDeIntentos.claveDeIp(texto)).as(texto).isEqualTo(texto);
            }
        }

        @Test
        @DisplayName("una IPv4 escrita como IPv6 (::ffff:a.b.c.d) es esa IPv4, y las IPv4 siguen contando de a una")
        void ipv4MapeadaYIpv4() {
            for (int i = 0; i < 20; i++) {
                limite.reservar("u" + i + "@x.test", "::ffff:" + IP);
            }

            assertThatThrownBy(() -> limite.reservar("otro@x.test", IP)).isInstanceOf(DemasiadosIntentosException.class);
            assertThatCode(() -> limite.reservar("otro@x.test", "203.0.113.8")).doesNotThrowAnyException();
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
                limite.exito("dueno@x.test", IP);
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

            reloj.avanzar(Duration.ofMinutes(1));

            for (int i = 0; i < 4; i++) {
                fallar("dueno@x.test", IP);
            }
            assertThatCode(() -> limite.reservar("dueno@x.test", IP)).doesNotThrowAnyException();
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

        assertThat(limite.entradas()).as("el email, su par con la IP y la IP del intento nuevo").isEqualTo(3);
    }

    @Test
    @DisplayName("con intentos en paralelo sobre el mismo email desde la misma IP se evalúan exactamente 5")
    void concurrenciaPorEmailEIp() throws Exception {
        assertThat(evaluadosEnParalelo(i -> "dueno@x.test", i -> IP)).isEqualTo(5);
    }

    @Test
    @DisplayName("con intentos en paralelo sobre el mismo email desde IPs distintas se evalúan exactamente 50")
    void concurrenciaPorEmail() throws Exception {
        assertThat(evaluadosEnParalelo(i -> "dueno@x.test", i -> "10.2.0." + (i % 200))).isEqualTo(50);
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
