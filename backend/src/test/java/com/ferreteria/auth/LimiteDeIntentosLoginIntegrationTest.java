package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Límite de intentos del login (hallazgo 1 de la revisión de seguridad) contra un Tomcat real: MockMvc no pasa por el
 * {@code RemoteIpValve}, que es el que toma la IP del cliente de {@code X-Real-IP} (lo pone Caddy,
 * {@code frontend/Caddyfile}). Con los límites de producción: el perfil test los sube para el resto de la suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.login.max-fallos-por-email-e-ip=5", "app.login.max-fallos-por-email=50",
        "app.login.max-intentos-por-ip=20"})
class LimiteDeIntentosLoginIntegrationTest extends UsuariosTestBase {

    private static final String AVISO = "Demasiados intentos. Probá de nuevo en 1 minuto.";
    /** Una IP distinta por test: el limitador es uno solo para todo el contexto. */
    private static final AtomicInteger IPS = new AtomicInteger();

    @LocalServerPort
    int puerto;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String email;
    private String ip;

    @BeforeEach
    void armar() {
        long ferreteria = crearFerreteria("Limite");
        email = unico("dueno") + "@limite.test";
        crearUsuario(ferreteria, email, "Dueño", "DUENO");
        ip = "203.0.113." + IPS.incrementAndGet();
    }

    private HttpResponse<String> login(String email, String password, String ipDelCliente) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json("email", email, "password", password)));
        if (ipDelCliente != null) {
            pedido.header("X-Real-IP", ipDelCliente);
        }
        return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode cuerpo(HttpResponse<String> respuesta) throws Exception {
        return objectMapper.readTree(respuesta.body());
    }

    @Test
    @DisplayName("5 contraseñas malas bloquean el email: el sexto intento da 429 con Retry-After y el aviso, aunque sea la buena")
    void cincoFallosBloqueanElEmail() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "equivocada", ip).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> bloqueado = login(email, "demo1234", ip);

        assertThat(bloqueado.statusCode()).isEqualTo(429);
        assertThat(bloqueado.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(cuerpo(bloqueado).get("status").asInt()).isEqualTo(429);
        assertThat(cuerpo(bloqueado).get("message").asText()).isEqualTo(AVISO);
        assertThat(cuerpo(bloqueado).has("token")).isFalse();
    }

    @Test
    @DisplayName("bloqueado el email, las variantes que la base da por iguales (tilde, invisible, ancho completo) dan 429")
    void variantesDelEmailBloqueado() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(email, "equivocada", ip);
        }

        for (String variante : new String[] {
                email.replaceFirst("o", "ó"),
                email.replaceFirst("@", "­@"),
                "ｄｕｅｎｏ" + email.substring("dueno".length())}) {
            HttpResponse<String> respuesta = login(variante, "demo1234", ip);
            assertThat(respuesta.statusCode()).as(variante).isEqualTo(429);
        }
    }

    @Test
    @DisplayName("hallazgo 6: 5 fallos bloquean el email desde esa IP; desde otra IP el dueño entra, y otro email también")
    void elBloqueoEsDelEmailDesdeEsaIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(email, "equivocada", ip);
        }

        assertThat(login(email, "demo1234", ip).statusCode()).as("desde la IP de los fallos").isEqualTo(429);
        assertThat(login(email, "demo1234", "198.51.100." + IPS.incrementAndGet()).statusCode())
                .as("hallazgo 6: el dueño entra desde la PC de su ferretería").isEqualTo(200);
        assertThat(login("dueno@tornillo.demo", "demo1234", ip).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("hallazgo 6: 50 fallos desde IPs distintas bloquean el email desde todas, aunque sea la contraseña buena")
    void cincuentaFallosDesdeIpsDistintasBloqueanElEmail() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(login(email, "equivocada", "198.18." + (i / 250) + "." + (i % 250)).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> bloqueado = login(email, "demo1234", "198.51.100." + IPS.incrementAndGet());

        assertThat(bloqueado.statusCode()).isEqualTo(429);
        assertThat(bloqueado.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(cuerpo(bloqueado).get("message").asText()).isEqualTo(AVISO);
    }

    @Test
    @DisplayName("un login correcto borra la racha: 4 fallos, entra, 4 fallos más y vuelve a entrar")
    void exitoBorraLaRacha() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(email, "equivocada", ip);
        }
        assertThat(login(email, "demo1234", ip).statusCode()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            login(email, "equivocada", ip);
        }

        assertThat(login(email, "demo1234", ip).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("20 intentos desde una IP (X-Real-IP) con emails distintos: el 21 da 429 y desde otra IP el mismo da 401")
    void veinteIntentosPorIp() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(login(unico("nadie") + "@limite.test", "demo1234", ip).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> bloqueado = login(unico("nadie") + "@limite.test", "demo1234", ip);
        assertThat(bloqueado.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(bloqueado.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 60);
        assertThat(cuerpo(bloqueado).get("message").asText()).isEqualTo(AVISO);

        String otraIp = "198.51.100." + IPS.incrementAndGet();
        assertThat(login(unico("nadie") + "@limite.test", "demo1234", otraIp).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> cambiarContrasena(String token, String actual, String nueva) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/auth/password"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json("passwordActual", actual, "passwordNueva", nueva)))
                .build();
        return cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("hallazgo 7: 5 actuales malas en «Cambiar mi contraseña» y el sexto intento da 429 con Retry-After y el aviso, aunque sea la buena")
    void cambiarContrasenaTieneLimite() throws Exception {
        String token = cuerpo(login(email, "demo1234", ip)).get("token").asText();
        for (int i = 0; i < 5; i++) {
            assertThat(cambiarContrasena(token, "equivocada", "clave-nueva-99").statusCode()).isEqualTo(400);
        }

        HttpResponse<String> bloqueado = cambiarContrasena(token, "demo1234", "clave-nueva-99");

        assertThat(bloqueado.statusCode()).isEqualTo(429);
        assertThat(bloqueado.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(cuerpo(bloqueado).get("message").asText()).isEqualTo(AVISO);
        assertThat(login(email, "clave-nueva-99", ip).statusCode()).as("no se cambió").isEqualTo(401);
        assertThat(login(email, "demo1234", ip).statusCode()).as("el login no se bloquea").isEqualTo(200);
    }
}
