package com.ferreteria.facturacion;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.model.RegistrosFiscales;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * El simulador de WSFEv1 para las ferreterias demo, solo en el perfil dev (CU-07, rama 3 de facturacion, S7): en dev las demo
 * facturan con el simulador, y en test ninguna ferreteria tiene config_arca sin que el test la cree. Migra con las
 * ubicaciones de Flyway que traen application-dev.yml y application-test.yml, sobre un MySQL de Testcontainers propio de la
 * clase y una base nueva por test.
 */
class SimuladorDeLasDemoTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final String EL_TORNILLO = "30-00000001-5";
    private static final String LA_TUERCA = "30-00000002-3";

    private String url;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void arrancar() {
        MYSQL.start();
    }

    @AfterAll
    static void parar() {
        MYSQL.stop();
    }

    @BeforeEach
    void baseNueva() throws SQLException {
        String base = "demo_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    @Test
    @DisplayName("en dev, El Tornillo (RI) y La Tuerca (Monotributista) quedan con sus datos fiscales y facturan con el simulador")
    void enDevLasDemoFacturanConElSimulador() {
        migrarComoElPerfil("application-dev.yml");

        assertThat(jdbc.queryForObject("select condicion_iva from ferreteria where cuit = ?", String.class, EL_TORNILLO))
            .isEqualTo("RESPONSABLE_INSCRIPTO");
        assertThat(jdbc.queryForObject("select condicion_iva from ferreteria where cuit = ?", String.class, LA_TUERCA))
            .isEqualTo("MONOTRIBUTISTA");
        for (String cuit : List.of(EL_TORNILLO, LA_TUERCA)) {
            Map<String, Object> config = configArca(cuit);
            assertThat(config).as(cuit).containsEntry("de_prueba", true).containsEntry("ambiente", "SIMULADOR")
                .containsEntry("punto_venta", 1).containsEntry("facturacion_habilitada", true);
            assertThat(config.get("habilitada_en")).as(cuit).isNotNull();
            assertThat(config).as(cuit + ": la fila que dejaria el panel del super admin")
                .containsAllEntriesOf(comoLaDejaElPanel(cuit));
        }
    }

    @Test
    @DisplayName("en una base dev con datos cargados, completa solo lo vacio y no toca una config_arca que ya existe")
    void enUnaBaseDevExistenteNoPisaLoQueYaHay() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword())
            .locations("classpath:db/migration", "classpath:db/dev-data").load().migrate();
        jdbc.update("update ferreteria set domicilio_comercial = 'Rivadavia 2500, Mar del Plata' where cuit = ?",
            EL_TORNILLO);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba) select id, true from ferreteria where cuit = ?",
            LA_TUERCA);
        Map<String, Object> configDeLaTuerca = configArca(LA_TUERCA);

        migrarComoElPerfil("application-dev.yml");

        Map<String, Object> elTornillo = datosFiscales(EL_TORNILLO);
        assertThat(elTornillo.get("domicilio_comercial")).isEqualTo("Rivadavia 2500, Mar del Plata");
        assertThat(elTornillo.get("inicio_actividades")).isNotNull();
        assertThat(elTornillo.get("ingresos_brutos")).isNotNull();
        assertThat(configArca(EL_TORNILLO)).containsEntry("ambiente", "SIMULADOR")
            .containsEntry("facturacion_habilitada", true);

        assertThat(datosFiscales(LA_TUERCA)).doesNotContainValue(null);
        assertThat(configArca(LA_TUERCA)).isEqualTo(configDeLaTuerca)
            .containsEntry("ambiente", null).containsEntry("facturacion_habilitada", false);
    }

    @Test
    @DisplayName("en test, ninguna ferreteria arranca con config_arca")
    void enTestNingunaFerreteriaTieneConfigArca() {
        migrarComoElPerfil("application-test.yml");

        assertThat(jdbc.queryForObject("select count(*) from ferreteria", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from config_arca", Integer.class)).isZero();
    }

    /**
     * Repite en el modelo lo que hace el super admin desde el panel (marca «de prueba», simulador y facturacion habilitada,
     * {@code ConfigArcaParaPlataformaImpl}): si a la ferreteria le faltara un dato de CU-01, {@code habilitar} se niega con
     * el mismo mensaje que el panel. Devuelve la fila que quedaria, sin el id ni la fecha.
     */
    private Map<String, Object> comoLaDejaElPanel(String cuit) {
        Map<String, Object> datos = datosFiscales(cuit);
        ConfigArca config = ConfigArca.nueva(((Number) datos.get("id")).longValue());
        config.cambiarDePrueba(true);
        config.asignarSimulador(RegistrosFiscales.NINGUNO);
        config.habilitar(datosFaltantes(datos), Instant.now());
        assertThat(config.puedeFacturar()).as(cuit).isTrue();
        assertThat(config.getAmbiente()).isEqualTo(Ambiente.SIMULADOR);
        return Map.of("de_prueba", config.isDePrueba(), "ambiente", config.getAmbiente().name(),
            "punto_venta", config.getPuntoVenta(), "facturacion_habilitada", config.isFacturacionHabilitada());
    }

    /** Los datos de CU-01 que la factura imprime y el panel exige antes de habilitar, con el nombre que ve el super admin. */
    private static List<String> datosFaltantes(Map<String, Object> datos) {
        List<String> faltan = new ArrayList<>();
        if (vacio(datos.get("razon_social"))) {
            faltan.add("razón social");
        }
        if (datos.get("condicion_iva") == null) {
            faltan.add("condición de IVA");
        }
        if (vacio(datos.get("domicilio_comercial"))) {
            faltan.add("domicilio comercial");
        }
        if (!(datos.get("inicio_actividades") instanceof Date)) {
            faltan.add("inicio de actividades");
        }
        if (vacio(datos.get("ingresos_brutos"))) {
            faltan.add("Ingresos Brutos");
        }
        return faltan;
    }

    private static boolean vacio(Object texto) {
        return texto == null || texto.toString().isBlank();
    }

    private Map<String, Object> datosFiscales(String cuit) {
        return jdbc.queryForMap("select id, razon_social, condicion_iva, domicilio_comercial, inicio_actividades, "
            + "ingresos_brutos from ferreteria where cuit = ?", cuit);
    }

    private Map<String, Object> configArca(String cuit) {
        return jdbc.queryForMap("select c.id, c.de_prueba, c.ambiente, c.punto_venta, c.facturacion_habilitada, "
            + "c.habilitada_en from config_arca c join ferreteria f on f.id = c.ferreteria_id where f.cuit = ?", cuit);
    }

    /** Migra con las ubicaciones de Flyway y el out-of-order del perfil, tal como estan en su application-*.yml. */
    private void migrarComoElPerfil(String archivo) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(archivo));
        Properties perfil = factory.getObject();
        String[] ubicaciones = Arrays.stream(perfil.getProperty("spring.flyway.locations").split(","))
            .map(String::strip).toArray(String[]::new);
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones)
            .outOfOrder(Boolean.parseBoolean(perfil.getProperty("spring.flyway.out-of-order", "false")))
            .load().migrate();
    }
}
