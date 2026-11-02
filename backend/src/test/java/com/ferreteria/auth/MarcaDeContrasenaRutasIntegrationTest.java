package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Hallazgo 5 contra un Tomcat real: con la contraseña por cambiar, ninguna forma de escribir la ruta llega a un
 * controlador que no sea el de la sesion o el cambio de contraseña. MockMvc no pasa por la decodificacion de Tomcat ni por
 * el firewall, y {@code /%61pi/rubros} es {@code /api/rubros} para Spring MVC. Los pedidos van por un socket crudo para que
 * nadie normalice la ruta antes de llegar al servidor (Caddy la reenvia tal cual).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarcaDeContrasenaRutasIntegrationTest extends UsuariosTestBase {

    @LocalServerPort
    int puerto;

    private long ferreteria;
    private String email;
    private String token;

    @BeforeEach
    void armar() throws Exception {
        ferreteria = crearFerreteria("Marca rutas");
        email = unico("dueno") + "@marca.test";
        crearUsuario(ferreteria, email, "Dueño", "DUENO");
        token = login(email);
        jdbc.update("update usuario set debe_cambiar_contrasena = true where email = ?", email);
    }

    /** Status de un pedido HTTP/1.1 armado a mano, con la ruta exactamente como se pasa. */
    private int status(String metodo, String ruta, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", puerto)) {
            byte[] cuerpo = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder pedido = new StringBuilder()
                    .append(metodo).append(' ').append(ruta).append(" HTTP/1.1\r\n")
                    .append("Host: localhost\r\nConnection: close\r\n")
                    .append("Authorization: Bearer ").append(token).append("\r\n");
            if (body != null) {
                pedido.append("Content-Type: application/json\r\nContent-Length: ").append(cuerpo.length).append("\r\n");
            }
            pedido.append("\r\n");
            OutputStream salida = socket.getOutputStream();
            salida.write(pedido.toString().getBytes(StandardCharsets.UTF_8));
            salida.write(cuerpo);
            salida.flush();
            String primera = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .lines().findFirst().orElseThrow();
            return Integer.parseInt(primera.split(" ")[1]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61pi/rubros", "/a%70i/rubros", "/%61pi/usuarios", "/%61pi/cotizacion", "/api/%72ubros"})
    @DisplayName("hallazgo 5: con la marca, una ruta con %-encoding no llega al controlador")
    void rutaCodificadaNoSaltaLaMarca(String ruta) throws Exception {
        assertThat(status("GET", ruta, null)).isEqualTo(403);
        assertThat(status("HEAD", ruta, null)).isEqualTo(403);
    }

    @Test
    @DisplayName("hallazgo 5: con la marca, un POST a /%61pi/rubros da 403 y no guarda nada")
    void escrituraCodificadaNoGuarda() throws Exception {
        assertThat(status("POST", "/%61pi/rubros", json("nombre", unico("R"), "margenPorcentaje", 30))).isEqualTo(403);

        assertThat(jdbc.queryForObject("select count(*) from rubro where ferreteria_id = ?", Long.class, ferreteria))
                .isZero();
    }

    @Test
    @DisplayName("hallazgo 5: con la marca siguen andando la sesion (tambien codificada o con query) y el cambio de contraseña")
    void loPermitidoSigueAndando() throws Exception {
        assertThat(status("GET", "/api/auth/me", null)).isEqualTo(200);
        assertThat(status("GET", "/api/auth/me?x=1", null)).isEqualTo(200);
        assertThat(status("GET", "/%61pi/auth/me", null)).isEqualTo(200);
        assertThat(status("GET", "/actuator/health", null)).isEqualTo(200);

        assertThat(status("PUT", "/api/auth/password",
                json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99"))).isEqualTo(204);
        assertThat(status("GET", "/%61pi/rubros", null)).isEqualTo(200);
    }

    @Test
    @DisplayName("hallazgo 5: un usuario dado de baja con la marca recibe 401, no 403")
    void sesionCerradaSigueSiendo401() throws Exception {
        jdbc.update("update usuario set activo = false where email = ?", email);

        assertThat(status("GET", "/api/rubros", null)).isEqualTo(401);
        assertThat(status("GET", "/%61pi/rubros", null)).isEqualTo(401);
    }
}
