package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V7 (corte 3): base de lista guardada en el articulo (bonificacion1..3 y lista_incluye_iva) con backfill
 * solo de los articulos con proveedor y precio de lista. Aditiva. Usa un MySQL de Testcontainers propio de la clase y
 * una base nueva por test: migra hasta la V6, siembra por JDBC y recien despues migra a la V7.
 * CUIT: bloque 30-7840001x.
 */
class MigracionV7Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();

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
    void baseNuevaEnLaVersion6() throws SQLException {
        String base = "v7_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("6").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa7() {
        flyway("classpath:db/migration").load().migrate();
    }

    // ---- columnas ----

    @Test
    @DisplayName("articulo suma bonificacion1..3 DECIMAL(5,2) NULL y lista_incluye_iva BOOLEAN NULL")
    void columnasNuevas() {
        migrarALa7();

        for (String columna : List.of("bonificacion1", "bonificacion2", "bonificacion3")) {
            assertThat(tipoYNulabilidad("articulo", columna)).isEqualTo("decimal(5,2) NULL");
        }
        assertThat(tipoYNulabilidad("articulo", "lista_incluye_iva")).isEqualTo("tinyint(1) NULL");
    }

    @Test
    @DisplayName("el CHECK de cada bonificacion rechaza 100,01 y -1 y acepta NULL, 0 y 100")
    void checkDeRango() {
        migrarALa7();
        Long f = ferreteria("30-78400010-1");

        for (int n = 1; n <= 3; n++) {
            String columna = "bonificacion" + n;
            insertarConBonificacion(f, "CK" + n + "-NULL", columna, null);
            insertarConBonificacion(f, "CK" + n + "-CERO", columna, new BigDecimal("0"));
            insertarConBonificacion(f, "CK" + n + "-CIEN", columna, new BigDecimal("100"));
            int indice = n;
            assertThatThrownBy(() -> insertarConBonificacion(f, "CK" + indice + "-MAS", columna, new BigDecimal("100.01")))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_articulo_bonificacion" + n);
            assertThatThrownBy(() -> insertarConBonificacion(f, "CK" + indice + "-NEG", columna, new BigDecimal("-1")))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_articulo_bonificacion" + n);
            assertThat(jdbc.queryForObject("select count(*) from articulo where codigo_interno like ?", Integer.class,
                "CK" + n + "-%")).isEqualTo(3);
        }
    }

    // ---- backfill ----

    @Test
    @DisplayName("articulo con proveedor y precio de lista: toma las bonificaciones y lista_incluye_iva del proveedor; costo, precio y ajustes no cambian")
    void backfillDeUnArticuloConLista() {
        Long f = ferreteria("30-78400010-2");
        Long proveedor = proveedor(f, "Proveedor con lista", true, "10.00", "5.00", null);
        Long articulo = articulo(f, "BF-LISTA", proveedor, "1000.00", "880.65", "1390.00", "-5.00", "3.00");

        migrarALa7();

        Map<String, Object> fila = jdbc.queryForMap("select * from articulo where id = ?", articulo);
        assertThat((BigDecimal) fila.get("bonificacion1")).isEqualByComparingTo("10");
        assertThat((BigDecimal) fila.get("bonificacion2")).isEqualByComparingTo("5");
        assertThat(fila.get("bonificacion3")).isNull();
        assertThat(fila.get("lista_incluye_iva")).isIn(true, 1);
        assertThat((BigDecimal) fila.get("costo")).isEqualByComparingTo("880.65");
        assertThat((BigDecimal) fila.get("precio_final")).isEqualByComparingTo("1390");
        assertThat((BigDecimal) fila.get("precio_lista_proveedor")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) fila.get("ajuste1")).isEqualByComparingTo("-5");
        assertThat((BigDecimal) fila.get("ajuste2")).isEqualByComparingTo("3");
        assertThat(fila.get("ajuste3")).isNull();
    }

    @Test
    @DisplayName("un proveedor sin incluir IVA da lista_incluye_iva = false (no NULL)")
    void backfillConListaSinIva() {
        Long f = ferreteria("30-78400010-3");
        Long proveedor = proveedor(f, "Proveedor sin IVA", false, null, null, null);
        Long articulo = articulo(f, "BF-SIN-IVA", proveedor, "100.00", "100.00", "170.00", null, null);

        migrarALa7();

        Map<String, Object> fila = jdbc.queryForMap(
            "select bonificacion1, bonificacion2, bonificacion3, lista_incluye_iva from articulo where id = ?", articulo);
        assertThat(fila.get("bonificacion1")).isNull();
        assertThat(fila.get("lista_incluye_iva")).isIn(false, 0);
    }

    @Test
    @DisplayName("decision 46: articulo con precio de lista pero SIN proveedor queda con toda la base en NULL")
    void sinProveedorQuedaEnNull() {
        Long f = ferreteria("30-78400010-4");
        Long articulo = articulo(f, "BF-SIN-PROV", null, "1000.00", "900.00", "1420.00", null, null);

        migrarALa7();

        assertBaseNula(articulo);
    }

    @Test
    @DisplayName("articulo sin precio de lista (alta manual) queda con toda la base en NULL aunque tenga proveedor")
    void sinPrecioDeListaQuedaEnNull() {
        Long f = ferreteria("30-78400010-5");
        Long proveedor = proveedor(f, "Proveedor con bonificaciones", true, "10.00", "5.00", "2.00");
        Long articulo = articulo(f, "BF-MANUAL", proveedor, null, "50.00", "85.00", null, null);

        migrarALa7();

        assertBaseNula(articulo);
        assertThat((BigDecimal) jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, articulo))
            .isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("un proveedor de otra ferreteria no se mezcla: el JOIN compara ferreteria_id")
    void noMezclaFerreterias() {
        Long a = ferreteria("30-78400010-6");
        Long b = ferreteria("30-78400010-7");
        Long proveedorDeB = proveedor(b, "Proveedor de B", true, "30.00", "20.00", "10.00");
        Long proveedorDeA = proveedor(a, "Proveedor de A", false, "7.00", null, null);
        // El articulo de A apunta (por un dato roto) a un proveedor de B: no hereda su base.
        Long cruzado = articulo(a, "BF-CRUZADO", proveedorDeB, "1000.00", "900.00", "1420.00", null, null);
        Long propio = articulo(a, "BF-PROPIO", proveedorDeA, "1000.00", "930.00", "1470.00", null, null);

        migrarALa7();

        assertBaseNula(cruzado);
        assertThat((BigDecimal) jdbc.queryForObject("select bonificacion1 from articulo where id = ?", BigDecimal.class,
            propio)).isEqualByComparingTo("7");
    }

    // ---- aditiva ----

    @Test
    @DisplayName("aditiva: ajuste1..3, ultima_importacion_id y el resto de la fila del corte 2 quedan identicos; el indice sigue ahi")
    void noQuitaNadaDelCorte2() {
        Long f = ferreteria("30-78400010-0");
        Long proveedor = proveedor(f, "Proveedor corte 2", false, "10.00", null, null);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
            + "values (?, 'v7-corte2@test.local', 'sin-login', 'Usuario', 'DUENO')", f);
        Long usuario = jdbc.queryForObject("select id from usuario where email = 'v7-corte2@test.local'", Long.class);
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, "
            + "nuevos, sin_cambio, no_estan, con_error, estado) values (?, ?, ?, 'v7.xlsx', 0, 0, 0, 0, 0, 0, 'APLICADA')",
            f, proveedor, usuario);
        Long importacion = jdbc.queryForObject("select max(id) from importacion_lista where ferreteria_id = ?",
            Long.class, f);
        Long articulo = articulo(f, "C2-1", proveedor, "1000.00", "855.00", "1350.00", "-5.00", "3.00");
        jdbc.update("update articulo set ajuste3 = 12.50, ultima_importacion_id = ?, moneda = 'USD' where id = ?",
            importacion, articulo);
        Map<String, Object> antes = jdbc.queryForMap("select * from articulo where id = ?", articulo);
        assertThat(antes.get("ultima_importacion_id")).isNotNull();

        migrarALa7();

        Map<String, Object> despues = new java.util.HashMap<>(jdbc.queryForMap("select * from articulo where id = ?", articulo));
        for (String nueva : List.of("bonificacion1", "bonificacion2", "bonificacion3", "lista_incluye_iva")) {
            assertThat(despues).containsKey(nueva);
            despues.remove(nueva);
        }
        assertThat(despues).isEqualTo(antes);
        assertThat(jdbc.queryForList(
            "select column_name from information_schema.statistics where table_schema = database() "
                + "and table_name = 'articulo' and index_name = 'ix_articulo_ultima_importacion' order by seq_in_index",
            String.class)).containsExactly("ferreteria_id", "ultima_importacion_id");
        assertThat(jdbc.queryForList(
            "select version from flyway_schema_history where success = 1 order by installed_rank", String.class))
            .containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    @DisplayName("datos demo: en una base dev existente (V1000+ ya aplicados) V7 entra por out-of-order, rellena los articulos con lista y no cambia costo, precio ni ajustes")
    void losDatosDemoNoCambian() {
        // Una base dev existente: V1..V6 y los datos demo (V1000+) ya aplicados, V7 todavia no.
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        Long f = ferreteria("30-78400010-9");
        Long proveedor = proveedor(f, "Proveedor dev con lista", true, "10.00", "5.00", null);
        Long conLista = articulo(f, "DEV-LISTA", proveedor, "1000.00", "855.00", "1350.00", "-5.00", null);
        Long sinProveedor = articulo(f, "DEV-SIN-PROV", null, "1000.00", "900.00", "1420.00", null, null);
        String consulta = "select id, costo, precio_final, ajuste1, ajuste2, ajuste3, precio_lista_proveedor "
            + "from articulo order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes.size()).isGreaterThan(2);

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        Map<String, Object> relleno = jdbc.queryForMap(
            "select bonificacion1, bonificacion2, bonificacion3, lista_incluye_iva from articulo where id = ?", conLista);
        assertThat((BigDecimal) relleno.get("bonificacion1")).isEqualByComparingTo("10");
        assertThat((BigDecimal) relleno.get("bonificacion2")).isEqualByComparingTo("5");
        assertThat(relleno.get("bonificacion3")).isNull();
        assertThat(relleno.get("lista_incluye_iva")).isIn(true, 1);
        assertBaseNula(sinProveedor);
        // Los demas (los demo, sin precio de lista) siguen sin base.
        assertThat(jdbc.queryForObject(
            "select count(*) from articulo where id not in (?, ?) and (bonificacion1 is not null "
                + "or bonificacion2 is not null or bonificacion3 is not null or lista_incluye_iva is not null)",
            Integer.class, conLista, sinProveedor)).isZero();
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version = '7'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("documenta el rollback: con V1000+ el codigo anterior (sin V7) no valida; sin datos demo (prod) V7 queda como future y valida")
    void versionSieteSinArchivo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path sinV7) throws Exception {
        java.nio.file.Path origen = java.nio.file.Path.of("src/main/resources/db/migration");
        try (var archivos = java.nio.file.Files.list(origen)) {
            for (java.nio.file.Path archivo : archivos.filter(a -> !a.getFileName().toString().startsWith("V7__")).toList()) {
                java.nio.file.Files.copy(archivo, sinV7.resolve(archivo.getFileName()));
            }
        }
        String codigoAnterior = "filesystem:" + sinV7;

        // Prod: la ultima migracion es la V7, que para el codigo anterior es future (se ignora por defecto).
        migrarALa7();
        flyway(codigoAnterior).load().validate();

        // Dev/test: hay V1000+ aplicados despues, asi que la V7 es "missing" y la validacion falla.
        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();
        assertThatThrownBy(() -> flyway(codigoAnterior, "classpath:db/dev-data").load().validate())
            .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class)
            .hasMessageContaining("Detected applied migration not resolved locally: 7");
    }

    // ---- rollback ----

    @Test
    @DisplayName("rollback-v7-base-de-lista.sql saca las cuatro columnas y la V7 del historial, y deja lo demas")
    void rollback() throws Exception {
        Long f = ferreteria("30-78400010-8");
        Long proveedor = proveedor(f, "Proveedor rollback", true, "10.00", null, null);
        Long articulo = articulo(f, "RB-1", proveedor, "1000.00", "900.00", "1420.00", "-5.00", null);
        migrarALa7();

        java.util.Properties propiedades = new java.util.Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades);
             Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }

        for (String columna : List.of("bonificacion1", "bonificacion2", "bonificacion3", "lista_incluye_iva")) {
            assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = database() "
                    + "and table_name = 'articulo' and column_name = ?", Integer.class, columna)).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '7'", Integer.class))
            .isZero();
        Map<String, Object> fila = jdbc.queryForMap(
            "select costo, precio_final, precio_lista_proveedor, ajuste1 from articulo where id = ?", articulo);
        assertThat((BigDecimal) fila.get("costo")).isEqualByComparingTo("900");
        assertThat((BigDecimal) fila.get("ajuste1")).isEqualByComparingTo("-5");
        // Y V7 se puede volver a aplicar.
        migrarALa7();
        assertThat((BigDecimal) jdbc.queryForObject("select bonificacion1 from articulo where id = ?", BigDecimal.class,
            articulo)).isEqualByComparingTo("10");
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(new ClassPathResource("catalogo/rollback-v7-base-de-lista.sql").getInputStream(),
            StandardCharsets.UTF_8);
    }

    // ---- ayudas ----

    private void assertBaseNula(Long articuloId) {
        Map<String, Object> fila = jdbc.queryForMap(
            "select bonificacion1, bonificacion2, bonificacion3, lista_incluye_iva from articulo where id = ?",
            articuloId);
        assertThat(fila.values()).containsOnlyNulls();
    }

    private String tipoYNulabilidad(String tabla, String columna) {
        List<Map<String, Object>> filas = jdbc.queryForList(
            "select column_type as tipo, is_nullable as nulo from information_schema.columns "
                + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        assertThat(filas).as(tabla + "." + columna).hasSize(1);
        return filas.get(0).get("tipo") + ("NO".equals(filas.get(0).get("nulo")) ? " NOT NULL" : " NULL");
    }

    private Long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long proveedor(Long ferreteriaId, String nombre, boolean incluyeIva, String b1, String b2, String b3) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre, lista_incluye_iva, bonificacion1, bonificacion2, "
            + "bonificacion3) values (?, ?, ?, ?, ?, ?)", ferreteriaId, nombre, incluyeIva, decimal(b1), decimal(b2),
            decimal(b3));
        return jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class,
            ferreteriaId, nombre);
    }

    private Long articulo(Long ferreteriaId, String codigo, Long proveedorId, String precioLista, String costo,
                          String precioFinal, String ajuste1, String ajuste2) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, "
            + "precio_lista_proveedor, costo, precio_final, ajuste1, ajuste2) values (?, ?, 'Articulo de prueba', ?, ?, "
            + "?, ?, ?, ?)", ferreteriaId, codigo, proveedorId, decimal(precioLista), decimal(costo),
            decimal(precioFinal), decimal(ajuste1), decimal(ajuste2));
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
            ferreteriaId, codigo);
    }

    private void insertarConBonificacion(Long ferreteriaId, String codigo, String columna, BigDecimal valor) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, " + columna
            + ") values (?, ?, 'Articulo con bonificacion', 10.00, 17.00, ?)", ferreteriaId, codigo, valor);
    }

    private static BigDecimal decimal(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }
}
