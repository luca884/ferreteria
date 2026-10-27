package com.ferreteria.despliegue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.support.ListasDePrueba;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La imagen que va a Railway, construida con backend/Dockerfile y levantada como en produccion: perfil prod, las mismas
 * variables que README.md (Deploy) y un MySQL 8.4 vacio. Prueba lo que la suite no ve porque corre desde target/classes:
 * el jar empaquetado (el lector de PDF lanza su JVM con PropertiesLauncher) y el entorno de la imagen (zona horaria).
 *
 * Fuera de la suite normal por el tag "imagen": construir la imagen tarda minutos. Se corre antes de cada deploy con
 * ./mvnw test -Pimagen. La imagen queda como ferreteria-api:imagen-test para que Docker reuse las capas de Maven.
 *
 * La imagen se construye con docker build (BuildKit, como Railway) y no con ImageFromDockerfile de Testcontainers: ese
 * empaqueta el contexto con commons-compress 1.28, que pide un commons-lang3 mas nuevo que el 3.14 que fija Spring Boot.
 */
@Tag("imagen")
class ImagenDeProduccionTest {

    private static final String IMAGEN = "ferreteria-api:imagen-test";
    private static final String AVISO_DE_ZONA = "La zona horaria de la JVM";
    private static final String CLAVE_ADMIN = "clave-del-admin-123";
    private static final String CLAVE_DUENO = "clave-del-dueno-123";
    private static final String EMAIL_DUENO = "dueno@imagen.test";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Network RED = Network.newNetwork();
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withNetwork(RED)
        .withNetworkAliases("mysql");
    private static final GenericContainer<?> API = new GenericContainer<>(DockerImageName.parse(IMAGEN))
        .withNetwork(RED)
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private static RestClient http;

    @BeforeAll
    static void levantarComoEnProduccion() throws Exception {
        construirImagen();
        MYSQL.start();
        API.withEnv(Map.of(
            "SPRING_PROFILES_ACTIVE", "prod",
            "DB_URL", "jdbc:mysql://mysql:3306/" + MYSQL.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            "DB_USER", MYSQL.getUsername(),
            "DB_PASSWORD", MYSQL.getPassword(),
            "JWT_SECRET", UUID.randomUUID() + "-" + UUID.randomUUID(),
            "CORS_ORIGINS", "http://localhost"));
        API.start();
        http = RestClient.builder()
            .baseUrl("http://" + API.getHost() + ":" + API.getMappedPort(8080))
            .defaultStatusHandler(HttpStatusCode::isError, (pedido, respuesta) -> { })
            .build();
        crearSuperAdmin();
    }

    @AfterAll
    static void bajar() {
        API.stop();
        MYSQL.stop();
        RED.close();
    }

    @Test
    void arrancaEnProdConLaBaseMigradaYRespondeUp() throws Exception {
        ResponseEntity<String> health = http.get().uri("/actuator/health").retrieve().toEntity(String.class);

        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(health.getBody()).path("status").asText()).isEqualTo("UP");
        assertThat(API.getLogs()).contains("The following 1 profile is active: \"prod\"", "Successfully applied");
    }

    @Test
    void laJvmCorreConLaZonaDeBuenosAires() {
        String logs = API.getLogs();
        String arranque = logs.lines().filter(l -> l.contains("Started FerreteriaApplication")).findFirst().orElseThrow();

        assertThat(arranque).as("hora del log con la zona de la JVM").contains("-03:00");
        assertThat(logs).doesNotContain(AVISO_DE_ZONA);
    }

    @Test
    void elSuperAdminCreadoConUnInsertIniciaSesion() throws Exception {
        JsonNode login = login("admin@imagen.test", CLAVE_ADMIN);

        assertThat(login.path("sesion").path("rol").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(login.path("token").asText()).isNotBlank();
    }

    @Test
    void elLectorDePdfLeeUnaListaDesdeElJar() throws Exception {
        String dueno = duenoDeUnaFerreteriaNueva();
        // Una palabra por celda: con tan pocos renglones, el espacio entre dos palabras puede leerse como otra columna.
        byte[] pdf = ListasDePrueba.pdf(List.of(
            List.of("Codigo", "Articulo", "Precio"),
            List.of("TOR-001", "Tornillo", "1250,50"),
            List.of("TUE-002", "Tuerca", "310,00")));
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        partes.add("archivo", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "lista.pdf";
            }
        });

        ResponseEntity<String> muestra = http.post().uri("/api/importaciones/muestra")
            .header("Authorization", "Bearer " + dueno)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(partes)
            .retrieve().toEntity(String.class);

        assertThat(muestra.getStatusCode().value()).as(muestra.getBody()).isEqualTo(200);
        JsonNode hoja = JSON.readTree(muestra.getBody()).path("hojas").path(0);
        assertThat(hoja.path("nombre").asText()).isEqualTo("PDF");
        assertThat(celdas(hoja.path("filas").path(1))).containsExactly("TOR-001", "Tornillo", "1250,50");
        assertThat(celdas(hoja.path("filas").path(2))).containsExactly("TUE-002", "Tuerca", "310,00");
    }

    /** docker build de backend/ (el directorio del pom, donde corre surefire). La salida queda en target/ para leer un error. */
    private static void construirImagen() throws Exception {
        Path salida = Path.of("target", "imagen-de-produccion-build.log").toAbsolutePath();
        Process build = new ProcessBuilder("docker", "build", "--tag", IMAGEN, ".")
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectErrorStream(true)
            .redirectOutput(salida.toFile())
            .start();
        boolean termino = build.waitFor(15, TimeUnit.MINUTES);
        if (!termino) {
            build.destroyForcibly();
        }
        assertThat(termino && build.exitValue() == 0).as("docker build de la imagen, salida en " + salida).isTrue();
    }

    /** Como README.md (Deploy): un INSERT con el hash de htpasswd ($2y$), que es lo unico que hay en una base nueva. */
    private static void crearSuperAdmin() throws Exception {
        String hash = new BCryptPasswordEncoder(BCryptVersion.$2Y, 10).encode(CLAVE_ADMIN);
        try (Connection conexion = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement insert = conexion.prepareStatement(
                 "INSERT INTO usuario (ferreteria_id, email, password_hash, nombre, rol) VALUES (NULL, ?, ?, ?, 'SUPER_ADMIN')")) {
            insert.setString(1, "admin@imagen.test");
            insert.setString(2, hash);
            insert.setString(3, "Admin de la imagen");
            insert.executeUpdate();
        }
    }

    /** El alta que hace el SUPER_ADMIN desde el panel: la ferreteria con su primer Dueño. */
    private static String duenoDeUnaFerreteriaNueva() throws Exception {
        String admin = login("admin@imagen.test", CLAVE_ADMIN).path("token").asText();
        ResponseEntity<String> alta = http.post().uri("/api/admin/ferreterias")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "razonSocial", "Ferreteria de la imagen",
                "cuit", "30-71234567-1",
                "condicionIva", "RESPONSABLE_INSCRIPTO",
                "dueno", Map.of("nombre", "Dueño de la imagen", "email", EMAIL_DUENO, "password", CLAVE_DUENO)))
            .retrieve().toEntity(String.class);
        assertThat(alta.getStatusCode().is2xxSuccessful()).as(alta.getBody()).isTrue();

        JsonNode login = login(EMAIL_DUENO, CLAVE_DUENO);
        assertThat(login.path("sesion").path("rol").asText()).isEqualTo("DUENO");
        return login.path("token").asText();
    }

    private static JsonNode login(String email, String clave) throws Exception {
        ResponseEntity<String> respuesta = http.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("email", email, "password", clave))
            .retrieve().toEntity(String.class);
        assertThat(respuesta.getStatusCode().value()).as(respuesta.getBody()).isEqualTo(200);
        return JSON.readTree(respuesta.getBody());
    }

    private static List<String> celdas(JsonNode fila) {
        return JSON.convertValue(fila.path("celdas"), new TypeReference<List<String>>() { });
    }
}
