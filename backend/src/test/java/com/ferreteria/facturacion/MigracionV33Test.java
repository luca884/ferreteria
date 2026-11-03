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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
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

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V33 (rama 2 de facturacion, S6): el estado propio del simulador de WSFEv1 (simulador_wsfe_serie y
 * simulador_wsfe_comprobante). Aditiva: ninguna tabla existente cambia. Usa un MySQL de Testcontainers propio de la clase y una
 * base nueva por test: migra hasta la V32, siembra por JDBC y recien despues migra a la V33. CUIT: bloque 30-7860133x, con
 * digito verificador valido.
 */
class MigracionV33Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final List<String> TABLAS_NUEVAS = List.of("simulador_wsfe_serie", "simulador_wsfe_comprobante");
    private static final String CUIT_A = "30-78601330-1";
    private static final String CUIT_B = "30-78601332-6";

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion32() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v33", "32"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa33() {
        flyway("classpath:db/migration").target("33").load().migrate();
    }

    @Test
    @DisplayName("crea las dos tablas con sus columnas: ferreteria_id NOT NULL, importes en DECIMAL(15,2), arrays en TEXT")
    void tablasYColumnas() {
        migrarALa33();

        assertThat(columnas("simulador_wsfe_serie")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "cuit bigint NO", "punto_venta int NO", "cbte_tipo int NO",
            "ultimo_nro bigint NO", "ultima_cbte_fch date YES"));
        assertThat(columnas("simulador_wsfe_comprobante")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "serie_id bigint NO", "cbte_nro bigint NO", "concepto int NO",
            "doc_tipo int NO", "doc_nro bigint NO", "cbte_fch date NO", "imp_total decimal(15,2) NO",
            "imp_tot_conc decimal(15,2) NO", "imp_neto decimal(15,2) NO", "imp_op_ex decimal(15,2) NO",
            "imp_trib decimal(15,2) NO", "imp_iva decimal(15,2) NO", "mon_id varchar(3) NO", "mon_cotiz decimal(10,6) NO",
            "condicion_iva_receptor_id int NO", "iva text NO", "cbtes_asoc text NO", "periodo_asoc_desde date YES",
            "periodo_asoc_hasta date YES", "cae varchar(14) NO", "cae_fch_vto date NO", "observaciones text NO",
            "fch_proceso datetime(6) NO"));
    }

    @Test
    @DisplayName("la FK compuesta no deja cruzar ferreterias: un comprobante simulado de A no apunta a una serie de B")
    void laFkCompuestaNoCruzaFerreterias() {
        migrarALa33();
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        long serieDeB = serie(b, 30786013310L, 1, 6);

        assertThatThrownBy(() -> comprobante(a, serieDeB, 1)).hasMessageContaining("fk_simulador_wsfe_comprobante_serie");
        // Dentro de la misma ferreteria, si.
        assertThatCode(() -> comprobante(b, serieDeB, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una serie por (ferreteria, CUIT, punto de venta, CbteTipo) y un comprobante por numero de cada serie")
    void unicos() {
        migrarALa33();
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        long serie = serie(a, 30786013301L, 1, 6);
        comprobante(a, serie, 1);

        assertThatThrownBy(() -> serie(a, 30786013301L, 1, 6)).hasMessageContaining("uk_simulador_wsfe_serie_clave");
        assertThatThrownBy(() -> comprobante(a, serie, 1)).hasMessageContaining("uk_simulador_wsfe_comprobante_nro");
        // Otra serie (otro punto de venta, otro tipo, otro CUIT u otra ferreteria) tiene su propio 1.
        comprobante(a, serie(a, 30786013301L, 2, 6), 1);
        comprobante(a, serie(a, 30786013301L, 1, 1), 1);
        comprobante(a, serie(a, 20123456786L, 1, 6), 1);
        comprobante(b, serie(b, 30786013301L, 1, 6), 1);
        assertThat(jdbc.queryForObject("select count(*) from simulador_wsfe_comprobante where cbte_nro = 1", Integer.class))
            .isEqualTo(5);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        serie: CUIT 0 | update simulador_wsfe_serie set cuit = 0 | ck_simulador_wsfe_serie_clave
        serie: CUIT de 12 cifras | update simulador_wsfe_serie set cuit = 100000000000 | ck_simulador_wsfe_serie_clave
        serie: punto de venta 0 | update simulador_wsfe_serie set punto_venta = 0 | ck_simulador_wsfe_serie_clave
        serie: punto de venta de 6 cifras | update simulador_wsfe_serie set punto_venta = 100000 | ck_simulador_wsfe_serie_clave
        serie: CbteTipo 0 | update simulador_wsfe_serie set cbte_tipo = 0 | ck_simulador_wsfe_serie_clave
        serie: CbteTipo de 4 cifras | update simulador_wsfe_serie set cbte_tipo = 1000 | ck_simulador_wsfe_serie_clave
        serie: ultimo negativo | update simulador_wsfe_serie set ultimo_nro = -1, ultima_cbte_fch = null | ck_simulador_wsfe_serie_ultimo
        serie: ultimo de 9 cifras | update simulador_wsfe_serie set ultimo_nro = 100000000 | ck_simulador_wsfe_serie_ultimo
        serie: ultimo sin su CbteFch | update simulador_wsfe_serie set ultima_cbte_fch = null | ck_simulador_wsfe_serie_ultimo
        serie: CbteFch con ultimo 0 | update simulador_wsfe_serie set ultimo_nro = 0 | ck_simulador_wsfe_serie_ultimo
        comprobante: numero 0 | update simulador_wsfe_comprobante set cbte_nro = 0 | ck_simulador_wsfe_comprobante_campos
        comprobante: numero de 9 cifras | update simulador_wsfe_comprobante set cbte_nro = 100000000 | ck_simulador_wsfe_comprobante_campos
        comprobante: concepto 0 | update simulador_wsfe_comprobante set concepto = 0 | ck_simulador_wsfe_comprobante_campos
        comprobante: DocTipo de 3 cifras | update simulador_wsfe_comprobante set doc_tipo = 100 | ck_simulador_wsfe_comprobante_campos
        comprobante: DocNro de 12 cifras | update simulador_wsfe_comprobante set doc_nro = 100000000000 | ck_simulador_wsfe_comprobante_campos
        comprobante: condicion de IVA 0 | update simulador_wsfe_comprobante set condicion_iva_receptor_id = 0 | ck_simulador_wsfe_comprobante_campos
        comprobante: MonId de 2 letras | update simulador_wsfe_comprobante set mon_id = 'PE' | ck_simulador_wsfe_comprobante_campos
        comprobante: CAE de 13 digitos | update simulador_wsfe_comprobante set cae = '7643210012345' | ck_simulador_wsfe_comprobante_cae
        comprobante: CAE con letras | update simulador_wsfe_comprobante set cae = '7643210012345X' | ck_simulador_wsfe_comprobante_cae
        comprobante: vencimiento antes de la CbteFch | update simulador_wsfe_comprobante set cae_fch_vto = '2026-10-27' | ck_simulador_wsfe_comprobante_cae
        comprobante: Iva que no es JSON | update simulador_wsfe_comprobante set iva = 'no' | ck_simulador_wsfe_comprobante_json
        comprobante: Iva que no es un array | update simulador_wsfe_comprobante set iva = '{}' | ck_simulador_wsfe_comprobante_json
        comprobante: CbtesAsoc que no es JSON | update simulador_wsfe_comprobante set cbtes_asoc = 'no' | ck_simulador_wsfe_comprobante_json
        comprobante: observaciones que no son un array | update simulador_wsfe_comprobante set observaciones = '1' | ck_simulador_wsfe_comprobante_json
        comprobante: periodo sin hasta | update simulador_wsfe_comprobante set periodo_asoc_desde = '2026-10-01' | ck_simulador_wsfe_comprobante_periodo
        comprobante: periodo sin desde | update simulador_wsfe_comprobante set periodo_asoc_hasta = '2026-10-31' | ck_simulador_wsfe_comprobante_periodo
        """)
    @DisplayName("cada CHECK rechaza lo incoherente (rangos del manual, ultimo con su CbteFch, CAE de 14 digitos, arrays JSON)")
    void cadaCheckRechazaLoIncoherente(String caso, String sentencia, String restriccion) {
        migrarALa33();
        long a = ferreteria(CUIT_A);
        long serie = serie(a, 30786013301L, 1, 6);
        jdbc.update("update simulador_wsfe_serie set ultimo_nro = 1, ultima_cbte_fch = '2026-10-28' where id = ?", serie);
        comprobante(a, serie, 1);

        assertThatThrownBy(() -> jdbc.update(sentencia)).as(caso).hasMessageContaining(restriccion);
    }

    @Test
    @DisplayName("lo coherente entra: serie recien creada en 0, serie con su ultimo, NC con CbtesAsoc y PeriodoAsoc")
    void loCoherenteEntra() {
        migrarALa33();
        long a = ferreteria(CUIT_A);
        long serie = serie(a, 30786013301L, 1, 8);
        comprobante(a, serie, 1);

        assertThat(jdbc.update("update simulador_wsfe_serie set ultimo_nro = 99999999, ultima_cbte_fch = '2026-10-28'"))
            .isEqualTo(1);
        assertThat(jdbc.update("update simulador_wsfe_comprobante set cbtes_asoc = '[{\"tipo\": 6, \"ptoVta\": 1, "
            + "\"nro\": 7}]', periodo_asoc_desde = '2026-10-01', periodo_asoc_hasta = '2026-10-31', observaciones = "
            + "'[{\"code\": 10041, \"msg\": \"Obs\"}]', cae_fch_vto = cbte_fch")).isEqualTo(1);
    }

    @Test
    @DisplayName("aditiva: ninguna tabla existente cambia (columnas, restricciones ni indices), sus filas quedan igual y no se pierde ninguna migracion")
    void noQuitaNada() {
        long f = ferreteria(CUIT_A);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta) values (?, true, 'SIMULADOR', 1)",
            f);
        Map<String, Object> ferreteriaAntes = jdbc.queryForMap("select * from ferreteria where id = ?", f);
        Map<String, Object> configAntes = jdbc.queryForMap("select * from config_arca where ferreteria_id = ?", f);
        List<String> esquemaAntes = esquema();

        migrarALa33();

        List<String> esquemaDespues = esquema();
        assertThat(esquemaDespues.stream().filter(fila -> !TABLAS_NUEVAS.contains(fila.split(" ")[0])).toList())
            .containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(esquemaDespues.stream().map(fila -> fila.split(" ")[0]).distinct()
            .filter(tabla -> esquemaAntes.stream().noneMatch(fila -> fila.startsWith(tabla + " "))).toList())
            .containsExactlyInAnyOrderElementsOf(TABLAS_NUEVAS);
        assertThat(jdbc.queryForMap("select * from ferreteria where id = ?", f)).isEqualTo(ferreteriaAntes);
        assertThat(jdbc.queryForMap("select * from config_arca where ferreteria_id = ?", f)).isEqualTo(configAntes);
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(33);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V33 entra por out-of-order, las ferreterias quedan igual y las tablas nuevas vacias")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String ferreterias = "select id, razon_social, cuit, condicion_iva from ferreteria order by id";
        List<Map<String, Object>> ferreteriasAntes = jdbc.queryForList(ferreterias);
        assertThat(ferreteriasAntes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '33' and success = 1",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(ferreterias)).isEqualTo(ferreteriasAntes);
        assertThat(TABLAS_NUEVAS).allSatisfy(tabla ->
            assertThat(jdbc.queryForObject("select count(*) from " + tabla, Integer.class)).as(tabla).isZero());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v33-simulador.sql, sin datos, saca las dos tablas y la V33, deja el esquema como en la V32, y la V33 vuelve a entrar")
    void rollbackSinDatos(String modo) throws Exception {
        long f = ferreteria(CUIT_A);
        List<String> esquemaAntes = esquema();
        migrarALa33();

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '33'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, f))
            .isEqualTo("Ferreteria " + CUIT_A);
        // Y V33 se puede volver a aplicar.
        migrarALa33();
        assertThat(columnas("simulador_wsfe_comprobante")).isNotEmpty();
    }

    @ParameterizedTest(name = "con una fila en {0}")
    @ValueSource(strings = {"simulador_wsfe_serie", "simulador_wsfe_comprobante"})
    @DisplayName("rollback-v33-simulador.sql se niega si hay una sola fila en las tablas del simulador, y no toca nada")
    void rollbackConDatosSeNiega(String tabla) {
        migrarALa33();
        sembrarUnaFilaEn(tabla);
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filasDelSimulador();

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_sin_datos_del_simulador");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filasDelSimulador()).isEqualTo(filasAntes).containsEntry(tabla, 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '33'", Integer.class))
            .isEqualTo(1);
    }

    @ParameterizedTest(name = "con una fila en {0}")
    @ValueSource(strings = {"simulador_wsfe_serie", "simulador_wsfe_comprobante"})
    @DisplayName("rollback-v33-simulador.sql corrido sentencia por sentencia ignorando errores (mysql --force, consola) con "
        + "una sola fila no borra ninguna tabla ni la 33 del historial")
    void rollbackIgnorandoErroresConDatosNoBorraNada(String tabla) throws Exception {
        migrarALa33();
        sembrarUnaFilaEn(tabla);
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filasDelSimulador();

        List<String> errores = correrRollbackIgnorandoErrores();

        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_sin_datos_del_simulador"));
        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filasDelSimulador()).isEqualTo(filasAntes).containsEntry(tabla, 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '33'", Integer.class))
            .isEqualTo(1);
    }

    /** Una sola fila en la tabla pedida: la serie sola, o un comprobante con su serie. */
    private void sembrarUnaFilaEn(String tabla) {
        long f = ferreteria(CUIT_A);
        long serie = serie(f, 30786013301L, 1, 6);
        if (tabla.equals("simulador_wsfe_comprobante")) {
            comprobante(f, serie, 1);
        }
    }

    private Map<String, Integer> filasDelSimulador() {
        Map<String, Integer> filas = new HashMap<>();
        TABLAS_NUEVAS.forEach(t -> filas.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class)));
        return filas;
    }

    // ---- siembra por JDBC ----

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private long serie(long ferreteria, long cuit, int puntoVenta, int cbteTipo) {
        jdbc.update("insert into simulador_wsfe_serie (ferreteria_id, cuit, punto_venta, cbte_tipo) values (?, ?, ?, ?)",
            ferreteria, cuit, puntoVenta, cbteTipo);
        return jdbc.queryForObject("select id from simulador_wsfe_serie where ferreteria_id = ? and cuit = ? "
            + "and punto_venta = ? and cbte_tipo = ?", Long.class, ferreteria, cuit, puntoVenta, cbteTipo);
    }

    /** Una Factura B a Consumidor Final por $ 121 al 21 %, autorizada el 2026-10-28 con un CAE que vence el 2026-11-07. */
    private void comprobante(long ferreteria, long serie, long numero) {
        jdbc.update("insert into simulador_wsfe_comprobante (ferreteria_id, serie_id, cbte_nro, concepto, doc_tipo, doc_nro, "
            + "cbte_fch, imp_total, imp_tot_conc, imp_neto, imp_op_ex, imp_trib, imp_iva, mon_id, mon_cotiz, "
            + "condicion_iva_receptor_id, iva, cbtes_asoc, cae, cae_fch_vto, observaciones, fch_proceso) "
            + "values (?, ?, ?, 1, 99, 0, '2026-10-28', 121.00, 0, 100.00, 0, 0, 21.00, 'PES', 1, 5, "
            + "'[{\"id\": 5, \"baseImp\": 100.00, \"importe\": 21.00}]', '[]', '76432100123456', '2026-11-07', '[]', "
            + "'2026-10-28 13:00:00')", ferreteria, serie, numero);
    }

    // ---- lectura del esquema ----

    /** Columna -> "tipo nulo" de la tabla. */
    private Map<String, String> columnas(String tabla) {
        Map<String, String> columnas = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo "
            + "from information_schema.columns where table_schema = database() and table_name = ?", tabla)
            .forEach(c -> columnas.put((String) c.get("nombre"), c.get("tipo") + " " + c.get("nulo")));
        return columnas;
    }

    /** "nombre tipo nulo" -> mapa columna -> "tipo nulo". */
    private static Map<String, String> esperadas(String... columnas) {
        Map<String, String> esperadas = new HashMap<>();
        for (String columna : columnas) {
            int espacio = columna.indexOf(' ');
            esperadas.put(columna.substring(0, espacio), columna.substring(espacio + 1));
        }
        return esperadas;
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
            new ClassPathResource("facturacion/rollback-v33-simulador.sql").getInputStream(), StandardCharsets.UTF_8);
    }
}
