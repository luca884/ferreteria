package com.ferreteria.facturacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V36 (rama 7 de facturacion, cu-07-contingencias T2: S10, S5, S6 y S2): el registro fiscal versionado (varios por
 * venta, uno solo vigente: el reintento del Dueño deja el anterior como historia), el estado SIN_FACTURA con su motivo,
 * usuario y fecha, la marca de «salio con ticket provisorio» y el backoff de la serie. Molde de MigracionV35Test: un MySQL de
 * Testcontainers propio de la clase y una base nueva por test, migrada hasta la V35; siembra por JDBC y recien despues
 * migra a la V36. CUIT: bloque 30-7860136x, con digito verificador valido.
 */
class MigracionV36Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final AtomicInteger COMPROBANTES = new AtomicInteger();
    private static final List<String> COLUMNAS_DEL_REGISTRO = List.of("ticket_provisorio", "motivo_sin_factura",
        "usuario_sin_factura_id", "sin_factura_en", "reemplazado_en");
    private static final List<String> COLUMNAS_DE_LA_SERIE = List.of("fallas_seguidas", "proximo_reintento");
    private static final String CUIT_A = "30-78601360-3";
    private static final String CUIT_B = "30-78601361-1";
    private static final String SIN_FACTURA = "estado = 'SIN_FACTURA', motivo_sin_factura = 'El cliente no volvio', "
        + "sin_factura_en = '2026-10-29 12:00:00'";

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
    void baseNuevaEnLaVersion35() throws SQLException {
        String base = "v36_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("35").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa36() {
        flyway("classpath:db/migration").target("36").load().migrate();
    }

    @Test
    @DisplayName("suma al registro la marca de provisorio (NOT NULL, false), el motivo de hasta 200, el usuario y la fecha de "
        + "SIN_FACTURA y la de reemplazo, y a la serie las fallas seguidas (NOT NULL, 0) y el proximo reintento")
    void columnasNuevas() {
        migrarALa36();

        assertThat(columnas("comprobante_fiscal")).containsAllEntriesOf(esperadas(
            "ticket_provisorio tinyint(1) NO 0", "motivo_sin_factura varchar(200) YES -", "usuario_sin_factura_id bigint YES -",
            "sin_factura_en datetime(6) YES -", "reemplazado_en datetime(6) YES -"));
        assertThat(columnas("serie_fiscal")).containsAllEntriesOf(esperadas(
            "fallas_seguidas int NO 0", "proximo_reintento datetime(6) YES -"));
    }

    @Test
    @DisplayName("uno solo vigente por venta: otro registro entra recien cuando el vigente queda reemplazado, y la historia "
        + "puede tener varios")
    void unoSoloVigentePorVenta() {
        migrarALa36();
        Escenario a = escenario(CUIT_A);
        long r1 = registro(a, a.venta(), "RECHAZADO");

        assertThatThrownBy(() -> registro(a, a.venta(), "PENDIENTE")).hasMessageContaining("uk_comprobante_fiscal_vigente");

        reemplazar(r1);
        long r2 = registro(a, a.venta(), "FUERA_DE_FECHA");
        assertThatThrownBy(() -> registro(a, a.venta(), "PENDIENTE")).hasMessageContaining("uk_comprobante_fiscal_vigente");
        reemplazar(r2);
        long r3 = registro(a, a.venta(), "PENDIENTE");

        assertThat(jdbc.queryForList("select id from comprobante_fiscal where comprobante_id = ? and reemplazado_en is null",
            Long.class, a.venta())).containsExactly(r3);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where comprobante_id = ?", Integer.class,
            a.venta())).isEqualTo(3);
        // Otra venta, y la de otra ferreteria, tienen el suyo.
        assertThatCode(() -> registro(a, venta(a.ferreteria(), a.usuario()), "PENDIENTE")).doesNotThrowAnyException();
        Escenario b = escenario(CUIT_B);
        assertThatCode(() -> registro(b, b.venta(), "PENDIENTE")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("la FK compuesta a la venta sigue, con su indice (ferreteria_id, comprobante_id, id); el unico por venta se "
        + "va y el filtro por estado dentro de la ferreteria tiene su indice")
    void laVentaSigueConSuFkYLosIndices() {
        migrarALa36();
        Escenario a = escenario(CUIT_A);

        assertThat(indice("ix_comprobante_fiscal_comprobante")).containsExactly("ferreteria_id", "comprobante_id", "id");
        assertThat(indice("ix_comprobante_fiscal_estado")).containsExactly("ferreteria_id", "estado", "registrado_en");
        assertThat(indice("uk_comprobante_fiscal_comprobante")).isEmpty();
        assertThat(jdbc.queryForList("select constraint_name from information_schema.referential_constraints "
            + "where constraint_schema = database() and table_name = 'comprobante_fiscal'", String.class))
            .contains("fk_comprobante_fiscal_comprobante", "fk_comprobante_fiscal_usuario_sin_factura");
        assertThatThrownBy(() -> registro(a, a.venta() + 1000, "PENDIENTE"))
            .hasMessageContaining("fk_comprobante_fiscal_comprobante");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        estado fuera de la lista | update comprobante_fiscal set estado = 'ANULADO', resultado = null, numero = 9, cbte_fch = '2026-10-28' | ck_comprobante_fiscal_estado
        SIN_FACTURA sin motivo | update comprobante_fiscal set estado = 'SIN_FACTURA' | ck_comprobante_fiscal_sin_factura
        motivo sin SIN_FACTURA | update comprobante_fiscal set motivo_sin_factura = 'x', usuario_sin_factura_id = usuario_de_prueba, sin_factura_en = '2026-10-29 12:00:00' | ck_comprobante_fiscal_sin_factura
        SIN_FACTURA sin usuario | update comprobante_fiscal set SIN_FACTURA_SIN_USUARIO | ck_comprobante_fiscal_sin_factura
        SIN_FACTURA sin fecha | update comprobante_fiscal set estado = 'SIN_FACTURA', motivo_sin_factura = 'x', usuario_sin_factura_id = usuario_de_prueba | ck_comprobante_fiscal_sin_factura
        motivo en blanco | update comprobante_fiscal set SIN_FACTURA_COMPLETO, motivo_sin_factura = '   ' | ck_comprobante_fiscal_sin_factura
        SIN_FACTURA con numero | update comprobante_fiscal set SIN_FACTURA_COMPLETO, numero = 7, cbte_fch = '2026-10-29' | ck_comprobante_fiscal_coherencia
        SIN_FACTURA con resultado A | update comprobante_fiscal set SIN_FACTURA_COMPLETO, resultado = 'A' | ck_comprobante_fiscal_coherencia
        RECHAZADO sin la R | update comprobante_fiscal set resultado = null | ck_comprobante_fiscal_coherencia
        PENDIENTE reemplazado | update comprobante_fiscal set estado = 'PENDIENTE', resultado = null, reemplazado_en = '2026-10-29 12:00:00' | ck_comprobante_fiscal_reemplazado
        SIN_FACTURA reemplazado | update comprobante_fiscal set SIN_FACTURA_COMPLETO, reemplazado_en = '2026-10-29 12:00:00' | ck_comprobante_fiscal_reemplazado
        usuario de otra ferreteria | update comprobante_fiscal set SIN_FACTURA_COMPLETO, usuario_sin_factura_id = usuario_de_otra | fk_comprobante_fiscal_usuario_sin_factura
        serie: fallas negativas | update serie_fiscal set fallas_seguidas = -1, proximo_reintento = '2026-10-29 12:01:00' | ck_serie_fiscal_backoff
        serie: fallas sin proximo reintento | update serie_fiscal set fallas_seguidas = 2 | ck_serie_fiscal_backoff
        serie: proximo reintento sin fallas | update serie_fiscal set proximo_reintento = '2026-10-29 12:01:00' | ck_serie_fiscal_backoff
        """)
    @DisplayName("cada CHECK y la FK rechazan lo incoherente: SIN_FACTURA con motivo, usuario y fecha juntos y sin numero, "
        + "solo RECHAZADO o FUERA_DE_FECHA reemplazado, el usuario de la ferreteria y el backoff de a dos")
    void cadaCheckRechazaLoIncoherente(String caso, String sentencia, String restriccion) {
        migrarALa36();
        Escenario a = escenario(CUIT_A);
        Escenario b = escenario(CUIT_B);
        registro(a, a.venta(), "RECHAZADO");
        String sql = sentencia
            .replace("SIN_FACTURA_SIN_USUARIO", SIN_FACTURA)
            .replace("SIN_FACTURA_COMPLETO", SIN_FACTURA + ", usuario_sin_factura_id = usuario_de_prueba")
            .replace("usuario_de_prueba", String.valueOf(a.usuario()))
            .replace("usuario_de_otra", String.valueOf(b.usuario()));

        assertThatThrownBy(() -> jdbc.update(sql + " where ferreteria_id = " + a.ferreteria()))
            .as(caso).hasMessageContaining(restriccion);
    }

    @Test
    @DisplayName("lo coherente entra: RECHAZADO y FUERA_DE_FECHA pasan a SIN_FACTURA (conservando lo que dijo ARCA) o quedan "
        + "reemplazados, la marca de provisorio va en cualquier estado y el backoff sube y vuelve a cero")
    void loCoherenteEntra() {
        migrarALa36();
        Escenario a = escenario(CUIT_A);
        long rechazado = registro(a, a.venta(), "RECHAZADO");
        long fueraDeFecha = registro(a, venta(a.ferreteria(), a.usuario()), "FUERA_DE_FECHA");
        long reemplazable = registro(a, venta(a.ferreteria(), a.usuario()), "RECHAZADO");
        long pendiente = registro(a, venta(a.ferreteria(), a.usuario()), "PENDIENTE");
        String aSinFactura = "update comprobante_fiscal set " + SIN_FACTURA + ", usuario_sin_factura_id = ? where id = ?";

        assertThat(jdbc.update(aSinFactura, a.usuario(), rechazado)).isOne();
        assertThat(jdbc.queryForMap("select resultado, errores from comprobante_fiscal where id = ?", rechazado))
            .containsEntry("resultado", "R");
        assertThat(jdbc.update(aSinFactura, a.usuario(), fueraDeFecha)).isOne();
        assertThat(jdbc.update("update comprobante_fiscal set motivo_sin_factura = ? where id = ?", "x".repeat(200),
            fueraDeFecha)).isOne();
        reemplazar(reemplazable);
        assertThat(jdbc.update("update comprobante_fiscal set ticket_provisorio = true where id = ?", pendiente)).isOne();
        assertThat(jdbc.update("update serie_fiscal set fallas_seguidas = 3, proximo_reintento = '2026-10-29 12:04:00' "
            + "where id = ?", a.serie())).isOne();
        assertThat(jdbc.update("update serie_fiscal set fallas_seguidas = 0, proximo_reintento = null where id = ?",
            a.serie())).isOne();
        assertThatThrownBy(() -> jdbc.update("update comprobante_fiscal set motivo_sin_factura = ? where id = ?",
            "x".repeat(201), fueraDeFecha)).hasMessageContaining("motivo_sin_factura");
    }

    @Test
    @DisplayName("aditiva para los datos: los registros y las series quedan como estaban, sin marca, sin SIN_FACTURA, "
        + "vigentes y sin backoff; solo cambian comprobante_fiscal y serie_fiscal, y no se pierde ninguna migracion")
    void losDatosQuedanComoEstaban() {
        Escenario a = escenario(CUIT_A);
        registro(a, a.venta(), "AUTORIZADO");
        registro(a, venta(a.ferreteria(), a.usuario()), "RECHAZADO");
        registro(a, venta(a.ferreteria(), a.usuario()), "PENDIENTE");
        jdbc.update("update serie_fiscal set lease_dueno = 'f3b0c2a4-1d2e-4f5a-8b9c-0d1e2f3a4b5c', lease_vence = "
            + "'2026-10-29 12:01:30', ultimo_autorizado = 1, ultima_cbte_fch = '2026-10-28'");
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");
        List<Map<String, Object>> seriesAntes = jdbc.queryForList("select * from serie_fiscal order by id");
        List<String> esquemaAntes = esquema();

        migrarALa36();

        List<Map<String, Object>> registros = jdbc.queryForList("select * from comprobante_fiscal order by id");
        assertThat(registros).hasSize(3).allSatisfy(fila -> assertThat(fila)
            .containsEntry("ticket_provisorio", false).containsEntry("motivo_sin_factura", null)
            .containsEntry("usuario_sin_factura_id", null).containsEntry("sin_factura_en", null)
            .containsEntry("reemplazado_en", null));
        assertThat(registros.stream().map(fila -> sin(fila, COLUMNAS_DEL_REGISTRO)).toList()).isEqualTo(registrosAntes);
        List<Map<String, Object>> series = jdbc.queryForList("select * from serie_fiscal order by id");
        assertThat(series).allSatisfy(fila -> assertThat(fila).containsEntry("fallas_seguidas", 0)
            .containsEntry("proximo_reintento", null));
        assertThat(series.stream().map(fila -> sin(fila, COLUMNAS_DE_LA_SERIE)).toList()).isEqualTo(seriesAntes);

        List<String> esquemaDespues = esquema();
        List<String> sacadas = esquemaAntes.stream().filter(fila -> !esquemaDespues.contains(fila)).toList();
        List<String> agregadas = esquemaDespues.stream().filter(fila -> !esquemaAntes.contains(fila)).toList();
        // Se van el unico por venta (la restriccion y su indice) y el texto viejo del CHECK de estado y del de coherencia.
        assertThat(sacadas).isNotEmpty().allSatisfy(fila -> assertThat(fila).startsWith("comprobante_fiscal ")
            .containsAnyOf("uk_comprobante_fiscal_comprobante", "ck_comprobante_fiscal_estado",
                "ck_comprobante_fiscal_coherencia"));
        assertThat(agregadas).isNotEmpty().allSatisfy(fila -> assertThat(fila).satisfiesAnyOf(
            f -> assertThat(f).startsWith("comprobante_fiscal ").containsAnyOf("ticket_provisorio", "motivo_sin_factura",
                "usuario_sin_factura_id", "sin_factura_en", "reemplazado_en", "ck_comprobante_fiscal_estado",
                "ck_comprobante_fiscal_coherencia", "ck_comprobante_fiscal_sin_factura",
                "ck_comprobante_fiscal_reemplazado", "uk_comprobante_fiscal_vigente", "ix_comprobante_fiscal_comprobante",
                "ix_comprobante_fiscal_estado", "ix_comprobante_fiscal_usuario_sin_factura",
                "fk_comprobante_fiscal_usuario_sin_factura"),
            f -> assertThat(f).startsWith("serie_fiscal ").containsAnyOf("fallas_seguidas", "proximo_reintento",
                "ck_serie_fiscal_backoff")));
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(36);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V36 entra por out-of-order y deja los registros y las series como "
        + "estaban")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data", "classpath:db/dev-only").ignoreMigrationPatterns("*:missing").load().migrate();
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");
        List<Map<String, Object>> seriesAntes = jdbc.queryForList("select * from serie_fiscal order by id");

        flyway("classpath:db/migration", "classpath:db/dev-data", "classpath:db/dev-only").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '36' and success = 1",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id").stream()
            .map(fila -> sin(fila, COLUMNAS_DEL_REGISTRO)).toList()).isEqualTo(registrosAntes);
        assertThat(jdbc.queryForList("select * from serie_fiscal order by id").stream()
            .map(fila -> sin(fila, COLUMNAS_DE_LA_SERIE)).toList()).isEqualTo(seriesAntes);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v36-contingencias.sql, sin SIN_FACTURA ni reemplazados (aunque haya provisorios y backoff), deja "
        + "el esquema como en la V35, los datos como estaban, saca la V36 del historial y la V36 vuelve a entrar")
    void rollbackSinHistoria(String modo) throws Exception {
        Escenario a = escenario(CUIT_A);
        long autorizado = registro(a, a.venta(), "AUTORIZADO");
        registro(a, venta(a.ferreteria(), a.usuario()), "RECHAZADO");
        registro(a, venta(a.ferreteria(), a.usuario()), "FUERA_DE_FECHA");
        List<String> esquemaAntes = esquema();
        migrarALa36();
        long pendiente = registro(a, venta(a.ferreteria(), a.usuario()), "PENDIENTE");
        jdbc.update("update comprobante_fiscal set ticket_provisorio = true where id in (?, ?)", autorizado, pendiente);
        jdbc.update("update serie_fiscal set fallas_seguidas = 2, proximo_reintento = '2026-10-29 12:02:00'");
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id").stream()
            .map(fila -> sin(fila, COLUMNAS_DEL_REGISTRO)).toList();
        List<Map<String, Object>> seriesAntes = jdbc.queryForList("select * from serie_fiscal order by id").stream()
            .map(fila -> sin(fila, COLUMNAS_DE_LA_SERIE)).toList();

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id")).isEqualTo(registrosAntes);
        assertThat(jdbc.queryForList("select * from serie_fiscal order by id")).isEqualTo(seriesAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '36'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select max(cast(version as unsigned)) from flyway_schema_history", Integer.class))
            .isEqualTo(35);
        // Y V36 se puede volver a aplicar.
        migrarALa36();
        assertThat(columnas("comprobante_fiscal")).containsKeys(COLUMNAS_DEL_REGISTRO.toArray(String[]::new));
        assertThat(columnas("serie_fiscal")).containsKeys(COLUMNAS_DE_LA_SERIE.toArray(String[]::new));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"un SIN_FACTURA", "un reemplazado"})
    @DisplayName("rollback-v36-contingencias.sql se niega con un solo registro SIN_FACTURA o reemplazado, y no toca nada")
    void rollbackConHistoriaSeNiega(String caso) {
        migrarALa36();
        sembrarHistoria(caso);
        List<String> esquemaAntes = esquema();
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");
        List<Map<String, Object>> seriesAntes = jdbc.queryForList("select * from serie_fiscal order by id");

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_v36_sin_historia");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id")).isEqualTo(registrosAntes);
        assertThat(jdbc.queryForList("select * from serie_fiscal order by id")).isEqualTo(seriesAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '36'", Integer.class))
            .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"un SIN_FACTURA", "un reemplazado"})
    @DisplayName("rollback-v36-contingencias.sql corrido sentencia por sentencia ignorando errores (mysql --force, consola) "
        + "con un solo SIN_FACTURA o reemplazado no borra ninguna columna, restriccion, indice ni la 36 del historial")
    void rollbackIgnorandoErroresConHistoriaNoBorraNada(String caso) throws Exception {
        migrarALa36();
        sembrarHistoria(caso);
        List<String> esquemaAntes = esquema();
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");
        List<Map<String, Object>> seriesAntes = jdbc.queryForList("select * from serie_fiscal order by id");

        List<String> errores = correrRollbackIgnorandoErrores();

        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_v36_sin_historia"));
        assertThat(errores).anySatisfy(error -> assertThat(error).contains("guarda_rollback_v36_registro_sin_sacar"));
        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id")).isEqualTo(registrosAntes);
        assertThat(jdbc.queryForList("select * from serie_fiscal order by id")).isEqualTo(seriesAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '36'", Integer.class))
            .isEqualTo(1);
    }

    /** Dos ferreterias con registros y backoff; solo la A con la historia del caso (SIN_FACTURA o un reemplazado). */
    private void sembrarHistoria(String caso) {
        Escenario a = escenario(CUIT_A);
        Escenario b = escenario(CUIT_B);
        registro(b, b.venta(), "PENDIENTE");
        jdbc.update("update serie_fiscal set fallas_seguidas = 1, proximo_reintento = '2026-10-29 12:01:00' where id = ?",
            b.serie());
        long r = registro(a, a.venta(), "RECHAZADO");
        if (caso.equals("un SIN_FACTURA")) {
            jdbc.update("update comprobante_fiscal set " + SIN_FACTURA + ", usuario_sin_factura_id = ? where id = ?",
                a.usuario(), r);
        } else {
            reemplazar(r);
            registro(a, a.venta(), "PENDIENTE");
        }
    }

    /** La fila sin las columnas de la V36: la que habia antes. */
    private static Map<String, Object> sin(Map<String, Object> fila, List<String> columnas) {
        Map<String, Object> sin = new LinkedHashMap<>(fila);
        columnas.forEach(sin::remove);
        return sin;
    }

    // ---- siembra por JDBC ----

    private record Escenario(long ferreteria, long usuario, long venta, long serie) {}

    /** Una ferreteria con su Dueño, una venta, la configuracion del simulador y la serie de las Facturas B. */
    private Escenario escenario(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V36', "
            + "'DUENO')", f, "v36-" + f + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
            + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-28 13:00:00')", f);
        jdbc.update("insert into serie_fiscal (ferreteria_id, ambiente, punto_venta, cbte_tipo) values (?, 'SIMULADOR', 1, 6)",
            f);
        long serie = jdbc.queryForObject("select id from serie_fiscal where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, usuario, venta(f, usuario), serie);
    }

    private long venta(long ferreteria, long usuario) {
        int numero = COMPROBANTES.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)",
            ferreteria, numero, "v36-" + numero, usuario);
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
            ferreteria, numero);
    }

    /**
     * Un registro de una Factura B del simulador a Consumidor Final, por $ 121 al 21 %, en el estado pedido con lo que ese
     * estado exige (AUTORIZADO: numero, CbteFch, CAE y A; RECHAZADO: la R y lo que dijo ARCA).
     */
    private long registro(Escenario e, long venta, String estado) {
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
            + "punto_venta, estado, receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, "
            + "emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, "
            + "emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en) "
            + "values (?, ?, 'SIMULADOR', 'FACTURA', 'B', 6, 1, 'PENDIENTE', 99, 0, 5, '30-78601360-3', 'Ferreteria V36', "
            + "'RESPONSABLE_INSCRIPTO', 'Av. Independencia 1234, Mar del Plata', '901-123456-7', '2015-03-01', "
            + "121.00, 100.00, 21.00, 0, 0, '2026-10-28 13:00:00')", e.ferreteria(), venta);
        long id = jdbc.queryForObject("select max(id) from comprobante_fiscal where ferreteria_id = ?", Long.class,
            e.ferreteria());
        switch (estado) {
            case "AUTORIZADO" -> jdbc.update("update comprobante_fiscal set estado = 'AUTORIZADO', numero = ?, cbte_fch = "
                + "'2026-10-28', cae = '86123456789012', cae_vencimiento = '2026-11-07', resultado = 'A', observaciones = "
                + "'[]', errores = '[]' where id = ?", id, id);
            case "RECHAZADO" -> jdbc.update("update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', "
                + "observaciones = '[{\"code\": 10015, \"msg\": \"x\"}]', errores = '[]' where id = ?", id);
            case "FUERA_DE_FECHA" -> jdbc.update("update comprobante_fiscal set estado = 'FUERA_DE_FECHA' where id = ?", id);
            default -> {
            }
        }
        return id;
    }

    private void reemplazar(long registro) {
        assertThat(jdbc.update("update comprobante_fiscal set reemplazado_en = '2026-10-29 12:00:00' where id = ?",
            registro)).isOne();
    }

    // ---- lectura del esquema ----

    /** Columna -> "tipo nulo default" de la tabla ("-" sin default). */
    private Map<String, String> columnas(String tabla) {
        Map<String, String> columnas = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo, "
            + "coalesce(column_default, '-') as defecto from information_schema.columns where table_schema = database() "
            + "and table_name = ?", tabla)
            .forEach(c -> columnas.put((String) c.get("nombre"), c.get("tipo") + " " + c.get("nulo") + " "
                + c.get("defecto")));
        return columnas;
    }

    /** "nombre tipo nulo default" -> mapa columna -> "tipo nulo default". */
    private static Map<String, String> esperadas(String... columnas) {
        Map<String, String> esperadas = new HashMap<>();
        for (String columna : columnas) {
            int espacio = columna.indexOf(' ');
            esperadas.put(columna.substring(0, espacio), columna.substring(espacio + 1));
        }
        return esperadas;
    }

    /** Las columnas (o expresiones) del indice de comprobante_fiscal, en orden; vacio si no existe. */
    private List<String> indice(String nombre) {
        return jdbc.queryForList("select coalesce(column_name, expression) from information_schema.statistics "
            + "where table_schema = database() and table_name = 'comprobante_fiscal' and index_name = ? "
            + "order by seq_in_index", String.class, nombre);
    }

    /** Todo el esquema, una fila por columna, restriccion, CHECK e indice; cada fila empieza por el nombre de su tabla. */
    private List<String> esquema() {
        List<String> filas = new ArrayList<>();
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'columna', column_name, column_type, is_nullable, "
            + "coalesce(column_default, '-'), extra) from information_schema.columns where table_schema = database()",
            String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, constraint_type, constraint_name) "
            + "from information_schema.table_constraints where constraint_schema = database()", String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', t.table_name, 'check', c.constraint_name, c.check_clause) "
            + "from information_schema.check_constraints c join information_schema.table_constraints t "
            + "on t.constraint_schema = c.constraint_schema and t.constraint_name = c.constraint_name "
            + "and t.constraint_type = 'CHECK' where c.constraint_schema = database()", String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'indice', index_name, non_unique, seq_in_index, "
            + "coalesce(column_name, expression)) from information_schema.statistics where table_schema = database()",
            String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'fk', constraint_name, referenced_table_name) "
            + "from information_schema.referential_constraints where constraint_schema = database()", String.class));
        return filas;
    }

    private void correrRollback() throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }
    }

    /**
     * Como un mysql --force o el script pegado en una consola: sentencia por sentencia (sin los comentarios, cortando en cada
     * punto y coma), sigue aunque una falle. Devuelve los mensajes de las que fallaron.
     */
    private List<String> correrRollbackIgnorandoErrores() throws Exception {
        String sinComentarios = scriptDeRollback().lines().filter(linea -> !linea.strip().startsWith("--"))
            .collect(Collectors.joining("\n"));
        List<String> errores = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "root", MYSQL.getPassword()); Statement s = c.createStatement()) {
            for (String sentencia : sinComentarios.split(";")) {
                if (sentencia.isBlank()) {
                    continue;
                }
                try {
                    s.execute(sentencia);
                } catch (SQLException e) {
                    errores.add(e.getMessage());
                }
            }
        }
        return errores;
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("facturacion/rollback-v36-contingencias.sql").getInputStream(), StandardCharsets.UTF_8);
    }
}
