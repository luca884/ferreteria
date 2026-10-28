package com.ferreteria.despliegue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.support.ListasDePrueba;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
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
 * La imagen del backend construida con backend/Dockerfile, el mismo que usa Railway, y levantada como en produccion:
 * perfil prod, las mismas variables que README.md (Deploy) y un MySQL 8.4 vacio. Prueba lo que la suite no ve porque
 * corre desde target/classes: el jar empaquetado y el entorno de la imagen (zona horaria). Si LectorPdf pierde la rama
 * del jar (la JVM hija con PropertiesLauncher), LectorPdfTest e ImportacionPdfIntegrationTest siguen en verde y solo
 * falla este test.
 *
 * Se construye para linux/amd64, como en Railway, aunque la maquina sea una Mac con Apple Silicon: ahi Docker emula x86 y
 * el arranque tarda unos 20 s en lugar de 5. Igual no es la misma imagen byte a byte: las imagenes base
 * (eclipse-temurin:17) son tags que cada builder resuelve por su cuenta.
 *
 * Fuera de la suite normal por el tag "imagen": construir la imagen tarda minutos. Se corre antes de cada deploy con
 * ./mvnw test -Pimagen. Cada corrida le pone a la imagen un tag propio, para no levantar una vieja si el build no la deja
 * en el daemon, y la borra al terminar; las capas quedan en la cache de BuildKit y la proxima construccion las reusa.
 *
 * La imagen se construye con docker build (BuildKit, como Railway) y no con ImageFromDockerfile de Testcontainers.
 *
 * El ultimo test le manda SIGTERM al contenedor, como Railway en cada deploy, y prueba el apagado ordenado: por eso los
 * tests van en orden y ese va al final.
 */
@Tag("imagen")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImagenDeProduccionTest {

    private static final String IMAGEN = "ferreteria-api:imagen-test-" + UUID.randomUUID();
    private static final String PLATAFORMA = "linux/amd64";
    private static final String AVISO_DE_ZONA = "La zona horaria de la JVM";
    private static final String CLAVE_ADMIN = "clave-del-admin-123";
    private static final String CLAVE_DUENO = "clave-del-dueno-123";
    private static final String EMAIL_DUENO = "dueno@imagen.test";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Network RED = Network.newNetwork();
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withNetwork(RED)
        .withNetworkAliases("mysql");
    /**
     * Health en 200 y el log "Started": Tomcat responde antes de que termine el arranque, y la zona se lee de ese log.
     * El aviso de ChequeoZonaHoraria sale en el ApplicationReadyEvent, en el mismo hilo y apenas despues de "Started".
     */
    private static final GenericContainer<?> API = new GenericContainer<>(DockerImageName.parse(IMAGEN))
        .withNetwork(RED)
        .withExposedPorts(8080)
        .waitingFor(new WaitAllStrategy(WaitAllStrategy.Mode.WITH_MAXIMUM_OUTER_TIMEOUT)
            .withStrategy(Wait.forHttp("/actuator/health").forStatusCode(200))
            .withStrategy(Wait.forLogMessage(".*Started FerreteriaApplication.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(3)));

    private static RestClient http;
    private static String tokenDelDueno;

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
    static void bajar() throws Exception {
        API.stop();
        MYSQL.stop();
        RED.close();
        new ProcessBuilder("docker", "rmi", IMAGEN).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor(1, TimeUnit.MINUTES);
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
    void laImagenCorreEnAmd64ComoEnRailway() throws Exception {
        assertThat(API.execInContainer("uname", "-m").getStdout().strip()).isEqualTo("x86_64");
    }

    @Test
    void elSuperAdminCreadoConUnInsertIniciaSesion() throws Exception {
        JsonNode login = login("admin@imagen.test", CLAVE_ADMIN);

        assertThat(login.path("sesion").path("rol").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(login.path("token").asText()).isNotBlank();
    }

    @Test
    void elLectorDePdfLeeUnaListaDesdeElJar() throws Exception {
        String dueno = dueno();
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

    /**
     * Lo que hace Railway en cada deploy: SIGTERM al contenedor viejo y SIGKILL pasados deploy.drainingSeconds de
     * backend/railway.json. La app tiene que apagarse ordenada (server.shutdown=graceful: el log del apagado ordenado de
     * Tomcat) y salir sola antes del SIGKILL, con el 143 de un SIGTERM atendido (128 + 15; un SIGKILL da 137). Va ultimo
     * porque apaga el contenedor que usan los demas tests.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void conSigtermSeApagaOrdenadaAntesDelDrenajeDeRailway() throws Exception {
        long drenaje = JSON.readTree(Path.of("railway.json").toFile()).path("deploy").path("drainingSeconds").asLong();
        assertThat(drenaje).as("deploy.drainingSeconds en railway.json").isPositive();
        String contenedor = API.getContainerId();

        long inicio = System.nanoTime();
        API.getDockerClient().killContainerCmd(contenedor).withSignal("SIGTERM").exec();
        // docker kill no manda el SIGKILL: se espera de mas para medir cuanto tarda si se pasa del drenaje.
        Integer salida = API.getDockerClient().waitContainerCmd(contenedor).start()
            .awaitStatusCode(drenaje + 60, TimeUnit.SECONDS);
        Duration tardo = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(API.getLogs()).contains("Commencing graceful shutdown", "Graceful shutdown complete");
        assertThat(salida).as("codigo de salida").isEqualTo(143);
        assertThat(tardo).as("hasta salir, con drainingSeconds = %d", drenaje).isLessThan(Duration.ofSeconds(drenaje));
    }

    /**
     * docker build de backend/ (el directorio del pom, donde corre surefire) para amd64, la arquitectura de Railway. La
     * salida queda en target/ para leer un error.
     */
    private static void construirImagen() throws Exception {
        Path salida = Path.of("target", "imagen-de-produccion-build.log").toAbsolutePath();
        Process build = new ProcessBuilder("docker", "build", "--platform", PLATAFORMA, "--tag", IMAGEN, ".")
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

    /** Token del Dueño de la ferreteria de la imagen. El alta va una sola vez: el CUIT y el email son unicos en la base. */
    private static String dueno() throws Exception {
        if (tokenDelDueno == null) {
            tokenDelDueno = duenoDeUnaFerreteriaNueva();
        }
        return tokenDelDueno;
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
