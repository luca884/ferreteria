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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
 * Migracion V38 (rama 8 de facturacion, cu-08-nota-de-credito T3: S3 y S8, C2): el comprobante asociado de la nota de
 * credito fiscal. Molde de MigracionV36Test: un MySQL de Testcontainers propio de la clase y una base nueva por test,
 * migrada hasta la ultima version anterior a la 38; siembra por JDBC y recien despues migra a la V38.
 *
 * <p>«La ultima anterior a la 38» y no «la 37»: la V37 (comprobante, T2) se escribe en otra rama a la vez que esta, y la
 * clase tiene que valer con ella y sin ella. Se lee de las migraciones del classpath ({@code Flyway.info()}), asi que hoy
 * es la 36 y, integrada la V37, la 37. Por lo mismo, el historial se cuenta contra el de antes y no contra un numero fijo.
 * CUIT: bloque 30-7860136x, con digito verificador valido.
 */
class MigracionV38Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final AtomicInteger COMPROBANTES = new AtomicInteger();
    private static final List<String> COLUMNAS_DEL_ASOCIADO = List.of("asociado_id", "asociado_cbte_tipo",
        "asociado_punto_venta", "asociado_numero", "asociado_cuit", "asociado_cbte_fch");
    private static final String CUIT_A = "30-78601360-3";
    private static final String CUIT_B = "30-78601361-1";

    private String url;
    private JdbcTemplate jdbc;
    /** La ultima version anterior a la 38 que hay en el classpath (36 sin la V37, 37 con ella). */
    private String anterior;

    @BeforeAll
    static void arrancar() {
        MYSQL.start();
    }

    @AfterAll
    static void parar() {
        MYSQL.stop();
    }

    @BeforeEach
    void baseNuevaEnLaVersionAnterior() throws SQLException {
        String base = "v38_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        MigrationVersion la38 = MigrationVersion.fromVersion("38");
        anterior = Arrays.stream(flyway("classpath:db/migration").load().info().all())
            .map(m -> m.getVersion())
            .filter(v -> v != null && v.compareTo(la38) < 0)
            .max(MigrationVersion::compareTo)
            .orElseThrow()
            .getVersion();
        flyway("classpath:db/migration").target(anterior).load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa38() {
        flyway("classpath:db/migration").target("38").load().migrate();
    }

    @Test
    @DisplayName("suma al registro las seis columnas del asociado, todas NULL por defecto: el registro de la factura, su "
        + "CbteTipo, punto de venta, numero, la CUIT de 11 y su CbteFch")
    void columnasNuevas() {
        migrarALa38();

        assertThat(columnas()).containsAllEntriesOf(esperadas("asociado_id bigint YES -", "asociado_cbte_tipo int YES -",
            "asociado_punto_venta int YES -", "asociado_numero bigint YES -", "asociado_cuit varchar(11) YES -",
            "asociado_cbte_fch date YES -"));
    }

    @Test
    @DisplayName("la FK compuesta al registro de la factura, con su indice (ferreteria_id, asociado_id): un asociado que no "
        + "existe o es de otra ferreteria no entra")
    void laFkVaAlRegistroDeLaMismaFerreteria() {
        migrarALa38();
        Escenario a = escenario(CUIT_A);
        Escenario b = escenario(CUIT_B);
        long factura = registro(a, a.venta(), "FACTURA", "AUTORIZADO", null);
        long deOtra = registro(b, b.venta(), "FACTURA", "AUTORIZADO", null);

        assertThat(indice("ix_comprobante_fiscal_asociado")).containsExactly("ferreteria_id", "asociado_id");
        assertThatThrownBy(() -> registro(a, venta(a), "NOTA_CREDITO", "PENDIENTE", factura + 1000))
            .hasMessageContaining("fk_comprobante_fiscal_asociado");
        assertThatThrownBy(() -> registro(a, venta(a), "NOTA_CREDITO", "PENDIENTE", deOtra))
            .hasMessageContaining("fk_comprobante_fiscal_asociado");
        assertThatCode(() -> registro(a, venta(a), "NOTA_CREDITO", "PENDIENTE", factura)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        NC sin asociado                  | update comprobante_fiscal set ASOCIADO_NULO where id = NOTA
        factura con asociado             | update comprobante_fiscal set ASOCIADO_COMPLETO where id = FACTURA
        NC sin la fecha del asociado     | update comprobante_fiscal set asociado_cbte_fch = null where id = NOTA
        NC sin la CUIT del asociado      | update comprobante_fiscal set asociado_cuit = null where id = NOTA
        NC sin el numero del asociado    | update comprobante_fiscal set asociado_numero = null where id = NOTA
        NC sin el tipo del asociado      | update comprobante_fiscal set asociado_cbte_tipo = null where id = NOTA
        NC sin el punto de venta         | update comprobante_fiscal set asociado_punto_venta = null where id = NOTA
        NC sin el registro asociado      | update comprobante_fiscal set asociado_id = null where id = NOTA
        NC B asociada a una Factura A    | update comprobante_fiscal set asociado_cbte_tipo = 1 where id = NOTA
        NC B asociada a una NC B         | update comprobante_fiscal set asociado_cbte_tipo = 8 where id = NOTA
        punto de venta 0                 | update comprobante_fiscal set asociado_punto_venta = 0 where id = NOTA
        numero 0                         | update comprobante_fiscal set asociado_numero = 0 where id = NOTA
        numero de nueve cifras           | update comprobante_fiscal set asociado_numero = 100000000 where id = NOTA
        CUIT con guiones                 | update comprobante_fiscal set asociado_cuit = '30-786013' where id = NOTA
        """)
    @DisplayName("el CHECK rechaza lo incoherente: el asociado completo y solo en una NOTA_CREDITO, con la Factura de su "
        + "misma letra, el punto de venta y el numero de ARCA y la CUIT de 11")
    void elCheckRechazaLoIncoherente(String caso, String sentencia) {
        migrarALa38();
        Escenario a = escenario(CUIT_A);
        long factura = registro(a, a.venta(), "FACTURA", "AUTORIZADO", null);
        long nota = registro(a, venta(a), "NOTA_CREDITO", "PENDIENTE", factura);
        String sql = sentencia
            .replace("ASOCIADO_NULO", "asociado_id = null, asociado_cbte_tipo = null, asociado_punto_venta = null, "
                + "asociado_numero = null, asociado_cuit = null, asociado_cbte_fch = null")
            .replace("ASOCIADO_COMPLETO", "asociado_id = " + factura + ", asociado_cbte_tipo = 6, asociado_punto_venta "
                + "= 1, asociado_numero = 1, asociado_cuit = '30786013603', asociado_cbte_fch = '2026-10-28'")
            .replace("NOTA", String.valueOf(nota))
            .replace("FACTURA", String.valueOf(factura));

        assertThatThrownBy(() -> jdbc.update(sql)).as(caso).hasMessageContaining("ck_comprobante_fiscal_asociado");
    }

    @Test
    @DisplayName("lo coherente entra: una NC A, B y C con la Factura de su letra, en cualquier estado, y la historia de una "
        + "NC rechazada y reintentada apunta a la misma factura")
    void loCoherenteEntra() {
        migrarALa38();
        Escenario a = escenario(CUIT_A);
        long facturaB = registro(a, a.venta(), "FACTURA", "AUTORIZADO", null);
        long nota = registro(a, venta(a), "NOTA_CREDITO", "RECHAZADO", facturaB);
        long notaDeLaNota = jdbc.queryForObject("select comprobante_id from comprobante_fiscal where id = ?", Long.class,
            nota);
        jdbc.update("update comprobante_fiscal set reemplazado_en = '2026-10-29 12:00:00' where id = ?", nota);

        assertThatCode(() -> registro(a, notaDeLaNota, "NOTA_CREDITO", "AUTORIZADO", facturaB)).doesNotThrowAnyException();
        for (String letra : List.of("A", "C")) {
            long factura = registro(a, venta(a), "FACTURA", "AUTORIZADO", null, letra);
            assertThatCode(() -> registro(a, venta(a), "NOTA_CREDITO", "AUTORIZADO", factura, letra)).as(letra)
                .doesNotThrowAnyException();
        }
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where asociado_id = ?", Integer.class,
            facturaB)).isEqualTo(2);
    }

    @Test
    @DisplayName("aditiva para los datos: los registros quedan como estaban, con el asociado en NULL; solo cambia "
        + "comprobante_fiscal y se suma una sola migracion al historial")
    void losDatosQuedanComoEstaban() {
        Escenario a = escenario(CUIT_A);
        registro(a, a.venta(), "FACTURA", "AUTORIZADO", null);
        registro(a, venta(a), "FACTURA", "RECHAZADO", null);
        registro(a, venta(a), "FACTURA", "PENDIENTE", null);
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");
        List<String> esquemaAntes = esquema();
        int historialAntes = historial();

        migrarALa38();

        List<Map<String, Object>> registros = jdbc.queryForList("select * from comprobante_fiscal order by id");
        assertThat(registros).hasSize(3).allSatisfy(fila -> COLUMNAS_DEL_ASOCIADO
            .forEach(columna -> assertThat(fila).containsEntry(columna, null)));
        assertThat(registros.stream().map(MigracionV38Test::sinAsociado).toList()).isEqualTo(registrosAntes);

        List<String> esquemaDespues = esquema();
        assertThat(esquemaAntes.stream().filter(fila -> !esquemaDespues.contains(fila)).toList()).isEmpty();
        assertThat(esquemaDespues.stream().filter(fila -> !esquemaAntes.contains(fila)).toList()).isNotEmpty()
            .allSatisfy(fila -> assertThat(fila).startsWith("comprobante_fiscal ").containsAnyOf("asociado_id",
                "asociado_cbte_tipo", "asociado_punto_venta", "asociado_numero", "asociado_cuit", "asociado_cbte_fch",
                "ck_comprobante_fiscal_asociado", "fk_comprobante_fiscal_asociado", "ix_comprobante_fiscal_asociado"));
        assertThat(historial()).isEqualTo(historialAntes + 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '38' and success = 1",
            Integer.class)).isOne();
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V38 entra por out-of-order y deja los registros como estaban")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data", "classpath:db/dev-only").ignoreMigrationPatterns("*:missing").load().migrate();
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");

        flyway("classpath:db/migration", "classpath:db/dev-data", "classpath:db/dev-only").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '38' and success = 1",
            Integer.class)).isOne();
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id").stream()
            .map(MigracionV38Test::sinAsociado).toList()).isEqualTo(registrosAntes);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v38-nota-de-credito-fiscal.sql, sin notas de credito, deja el esquema como antes, los datos como "
        + "estaban, saca la V38 del historial y la V38 vuelve a entrar")
    void rollbackSinNotas(String modo) throws Exception {
        Escenario a = escenario(CUIT_A);
        registro(a, a.venta(), "FACTURA", "AUTORIZADO", null);
        registro(a, venta(a), "FACTURA", "RECHAZADO", null);
        List<String> esquemaAntes = esquema();
        migrarALa38();
        registro(a, venta(a), "FACTURA", "PENDIENTE", null);
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id").stream()
            .map(MigracionV38Test::sinAsociado).toList();

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id")).isEqualTo(registrosAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '38'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select max(cast(version as unsigned)) from flyway_schema_history", String.class))
            .isEqualTo(anterior);
        // Y V38 se puede volver a aplicar.
        migrarALa38();
        assertThat(columnas()).containsKeys(COLUMNAS_DEL_ASOCIADO.toArray(String[]::new));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v38-nota-de-credito-fiscal.sql se niega con una sola nota de credito y no toca nada, aunque se "
        + "corra sentencia por sentencia ignorando errores (mysql --force, consola)")
    void rollbackConUnaNotaSeNiega(String modo) throws Exception {
        migrarALa38();
        Escenario a = escenario(CUIT_A);
        Escenario b = escenario(CUIT_B);
        registro(b, b.venta(), "FACTURA", "PENDIENTE", null);
        long factura = registro(a, a.venta(), "FACTURA", "AUTORIZADO", null);
        registro(a, venta(a), "NOTA_CREDITO", "PENDIENTE", factura);
        List<String> esquemaAntes = esquema();
        List<Map<String, Object>> registrosAntes = jdbc.queryForList("select * from comprobante_fiscal order by id");

        if (modo.equals("de corrido")) {
            assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_v38_sin_notas");
        } else {
            assertThat(correrRollbackIgnorandoErrores())
                .anySatisfy(error -> assertThat(error).contains("ck_rollback_v38_sin_notas"));
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante_fiscal order by id")).isEqualTo(registrosAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '38'", Integer.class))
            .isOne();
    }

    /** La fila sin las columnas de la V38: la que habia antes. */
    private static Map<String, Object> sinAsociado(Map<String, Object> fila) {
        Map<String, Object> sin = new LinkedHashMap<>(fila);
        COLUMNAS_DEL_ASOCIADO.forEach(sin::remove);
        return sin;
    }

    private int historial() {
        return jdbc.queryForObject("select count(*) from flyway_schema_history where success = 1 and version is not null",
            Integer.class);
    }

    // ---- siembra por JDBC ----

    private record Escenario(long ferreteria, long usuario, long venta) {}

    /** Una ferreteria con su Dueño, una venta y la configuracion del simulador. */
    private Escenario escenario(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V38', "
            + "'DUENO')", f, "v38-" + f + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
            + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-28 13:00:00')", f);
        Escenario sinVenta = new Escenario(f, usuario, 0);
        return new Escenario(f, usuario, venta(sinVenta));
    }

    /** Un comprobante de la ferreteria (la venta, o el de la nota de credito: la tabla comprobante no se mira aca). */
    private long venta(Escenario e) {
        int numero = COMPROBANTES.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)",
            e.ferreteria(), numero, "v38-" + numero, e.usuario());
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
            e.ferreteria(), numero);
    }

    private long registro(Escenario e, long comprobante, String clase, String estado, Long asociado) {
        return registro(e, comprobante, clase, estado, asociado, "B");
    }

    /**
     * Un registro del simulador a Consumidor Final por $ 121 al 21 %, de la clase y la letra pedidas, en el estado pedido
     * con lo que ese estado exige; una NOTA_CREDITO, con el asociado completo (el registro {@code asociado}, numero 1).
     */
    private long registro(Escenario e, long comprobante, String clase, String estado, Long asociado, String letra) {
        int tipoFactura = switch (letra) {
            case "A" -> 1;
            case "B" -> 6;
            default -> 11;
        };
        boolean nota = clase.equals("NOTA_CREDITO");
        int cbteTipo = nota ? tipoFactura + 2 : tipoFactura;
        boolean conIva = !letra.equals("C");
        // Sin las columnas del asociado en la factura: asi la misma siembra sirve antes de la V38.
        String columnasDelAsociado = nota ? ", asociado_id, asociado_cbte_tipo, asociado_punto_venta, asociado_numero, "
            + "asociado_cuit, asociado_cbte_fch" : "";
        String valoresDelAsociado = nota ? ", " + asociado + ", " + tipoFactura + ", 1, 1, '30786013603', '2026-10-28'"
            : "";
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
            + "punto_venta, estado, receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, "
            + "emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, "
            + "emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en"
            + columnasDelAsociado + ") "
            + "values (?, ?, 'SIMULADOR', ?, ?, ?, 1, 'PENDIENTE', ?, ?, ?, '30-78601360-3', 'Ferreteria V38', "
            + "'RESPONSABLE_INSCRIPTO', 'Av. Independencia 1234, Mar del Plata', '901-123456-7', '2015-03-01', "
            + "121.00, ?, ?, 0, 0, '2026-10-28 13:00:00'" + valoresDelAsociado + ")",
            e.ferreteria(), comprobante, clase, letra, cbteTipo, letra.equals("A") ? 80 : 99,
            letra.equals("A") ? 30786013301L : 0L, letra.equals("A") ? 1 : 5, conIva ? "100.00" : "121.00",
            conIva ? "21.00" : "0.00");
        long id = jdbc.queryForObject("select max(id) from comprobante_fiscal where ferreteria_id = ?", Long.class,
            e.ferreteria());
        switch (estado) {
            case "AUTORIZADO" -> jdbc.update("update comprobante_fiscal set estado = 'AUTORIZADO', numero = ?, cbte_fch = "
                + "'2026-10-28', cae = '86123456789012', cae_vencimiento = '2026-11-07', resultado = 'A', observaciones = "
                + "'[]', errores = '[]' where id = ?", id, id);
            case "RECHAZADO" -> jdbc.update("update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', "
                + "observaciones = '[{\"code\": 10015, \"msg\": \"x\"}]', errores = '[]' where id = ?", id);
            default -> {
            }
        }
        return id;
    }

    // ---- lectura del esquema ----

    /** Columna -> "tipo nulo default" de comprobante_fiscal ("-" sin default). */
    private Map<String, String> columnas() {
        Map<String, String> columnas = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo, "
            + "coalesce(column_default, '-') as defecto from information_schema.columns where table_schema = database() "
            + "and table_name = 'comprobante_fiscal'")
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

    /** Las columnas del indice de comprobante_fiscal, en orden; vacio si no existe. */
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
            new ClassPathResource("facturacion/rollback-v38-nota-de-credito-fiscal.sql").getInputStream(),
            StandardCharsets.UTF_8);
    }
}
