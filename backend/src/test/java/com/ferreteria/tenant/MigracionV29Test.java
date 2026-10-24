package com.ferreteria.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V29 (FK por ferreteria): toda FK entre tablas de negocio pasa a ser compuesta, (ferreteria_id, x_id) contra
 * (ferreteria_id, id) del padre, asi la base rechaza sola que una fila apunte a una fila de otra ferreteria. Solo cambia
 * restricciones e indices: ninguna fila cambia. Usa un MySQL de Testcontainers propio y una base nueva por test, con las
 * migraciones hasta la V28 aplicadas. CUIT: bloque 30-8531NNNN-1.
 */
class MigracionV29Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final AtomicInteger CUITS = new AtomicInteger();
    private static final AtomicInteger RENGLONES = new AtomicInteger();

    /** Las tablas a las que apunta alguna FK de negocio. */
    private static final List<String> PADRES = List.of("ajuste_cuenta", "articulo", "caja", "cliente", "compra", "comprobante",
            "forma_pago", "importacion_lista", "pago", "presupuesto", "proveedor", "recibo", "remito", "rubro", "usuario");

    /**
     * Cada FK entre tablas que tienen ferreteria_id (salvo las que apuntan a ferreteria), con sus columnas y las del padre.
     */
    private static final String FKS_DE_NEGOCIO = """
            select k.table_name hijo, k.constraint_name fk, k.referenced_table_name padre,
                   group_concat(k.column_name order by k.ordinal_position) columnas,
                   group_concat(k.referenced_column_name order by k.ordinal_position) columnas_padre
            from information_schema.key_column_usage k
            where k.table_schema = database() and k.referenced_table_name is not null
              and k.referenced_table_name <> 'ferreteria'
              and exists (select 1 from information_schema.columns c where c.table_schema = k.table_schema
                          and c.table_name = k.table_name and c.column_name = 'ferreteria_id')
              and exists (select 1 from information_schema.columns c where c.table_schema = k.table_schema
                          and c.table_name = k.referenced_table_name and c.column_name = 'ferreteria_id')
            group by k.table_name, k.constraint_name, k.referenced_table_name
            order by 1, 2""";

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
    void baseNuevaEnLaVersion28() throws SQLException {
        String base = "v29_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        migrarHasta("28");
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    // ---------------------------------------------------------------- siembra

    /** Una ferreteria con su Dueño, un rubro, un proveedor, un articulo, un cliente y una venta. */
    private record Escenario(long ferreteria, long usuario, long rubro, long proveedor, long articulo, long cliente,
            long comprobante) {}

    private Escenario escenario() {
        String cuit = "30-8531%04d-1".formatted(CUITS.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V29', 'DUENO')",
                f, "v29-" + cuit + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, 'Bulones', 40)", f);
        long rubro = jdbc.queryForObject("select id from rubro where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Bulonera Sur')", f);
        long proveedor = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, f);
        long articulo = articulo(f, "A-1", rubro, proveedor);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", f, "Cliente " + cuit);
        long cliente = jdbc.queryForObject("select id from cliente where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
                + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', 1, ?, ?, 100, 21, 121, 121)", f, "v29-" + cuit, usuario);
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, usuario, rubro, proveedor, articulo, cliente, comprobante);
    }

    private long articulo(long ferreteria, String codigo, Long rubro, Long proveedor) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, stock_actual, "
                + "rubro_id, proveedor_id) values (?, ?, 'Articulo V29', 100, 140, 5, ?, ?)", ferreteria, codigo, rubro, proveedor);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                ferreteria, codigo);
    }

    private void renglon(long ferreteria, long comprobante, long articulo) {
        jdbc.update("insert into item_comprobante (ferreteria_id, comprobante_id, renglon, articulo_id, descripcion, unidad, "
                + "cantidad, precio_unitario, alicuota_iva, total, neto, iva, bruto) "
                + "values (?, ?, ?, ?, 'Articulo V29', 'UNIDAD', 1, 121, 'IVA_21', 121, 100, 21, 121)",
                ferreteria, comprobante, RENGLONES.incrementAndGet(), articulo);
    }

    private List<Map<String, Object>> fksDeNegocio() {
        return jdbc.queryForList(FKS_DE_NEGOCIO);
    }

    /** Las FK y los indices de todas las tablas, para comparar el esquema antes y despues. */
    private List<Map<String, Object>> fksEIndices() {
        List<Map<String, Object>> todo = jdbc.queryForList("""
                select k.table_name tabla, k.constraint_name nombre, k.referenced_table_name padre,
                       group_concat(k.column_name order by k.ordinal_position) columnas,
                       group_concat(k.referenced_column_name order by k.ordinal_position) columnas_padre
                from information_schema.key_column_usage k
                where k.table_schema = database() and k.referenced_table_name is not null
                group by 1, 2, 3 order by 1, 2""");
        todo.addAll(jdbc.queryForList("""
                select table_name tabla, index_name nombre, non_unique, group_concat(column_name order by seq_in_index) columnas
                from information_schema.statistics where table_schema = database() and table_name <> 'flyway_schema_history'
                group by 1, 2, 3 order by 1, 2"""));
        return todo;
    }

    private boolean tieneUniquePorFerreteria(String tabla) {
        return jdbc.queryForObject("""
                select count(*) from (
                  select index_name from information_schema.statistics
                  where table_schema = database() and table_name = ? and non_unique = 0
                  group by index_name having group_concat(column_name order by seq_in_index) = 'ferreteria_id,id') u""",
                Integer.class, tabla) > 0;
    }

    // ---------------------------------------------------------------- esquema

    @Test
    @DisplayName("esquema: las 56 FK de negocio son (ferreteria_id, x_id) contra (ferreteria_id, id) y los 15 padres tienen UNIQUE (ferreteria_id, id)")
    void fksPorFerreteria() {
        assertThat(fksDeNegocio()).hasSize(56).allSatisfy(fk -> assertThat(fk.get("columnas_padre")).isEqualTo("id"));

        migrarHasta("29");

        List<Map<String, Object>> fks = fksDeNegocio();
        assertThat(fks).hasSize(56).allSatisfy(fk -> {
            assertThat((String) fk.get("columnas")).as("%s", fk.get("fk")).startsWith("ferreteria_id,").doesNotContain(",ferreteria_id");
            assertThat(fk.get("columnas_padre")).as("%s", fk.get("fk")).isEqualTo("ferreteria_id,id");
        });
        assertThat(fks.stream().map(fk -> (String) fk.get("padre")).distinct().sorted().toList()).isEqualTo(PADRES);
        assertThat(PADRES).allSatisfy(padre -> assertThat(tieneUniquePorFerreteria(padre)).as(padre).isTrue());
    }

    @Test
    @DisplayName("esquema actual: ninguna migracion posterior deja una FK de negocio solo por id")
    void ningunaFkDeNegocioSoloPorId() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();

        assertThat(fksDeNegocio()).isNotEmpty().allSatisfy(fk -> {
            assertThat((String) fk.get("columnas")).as("%s", fk.get("fk")).startsWith("ferreteria_id,");
            assertThat(fk.get("columnas_padre")).as("%s", fk.get("fk")).isEqualTo("ferreteria_id,id");
        });
    }

    // ---------------------------------------------------------------- referencias

    @Test
    @DisplayName("referencias: apuntar a una fila de otra ferreteria falla al insertar y al actualizar; NULL y la propia siguen valiendo")
    void referenciasAOtraFerreteria() {
        Escenario a = escenario();
        Escenario b = escenario();
        migrarHasta("29");

        // NULL en una referencia opcional y la propia ferreteria siguen valiendo.
        articulo(a.ferreteria(), "A-2", null, null);
        renglon(a.ferreteria(), a.comprobante(), a.articulo());

        assertThatThrownBy(() -> articulo(a.ferreteria(), "A-3", b.rubro(), null)).hasMessageContaining("fk_articulo_rubro");
        assertThatThrownBy(() -> articulo(a.ferreteria(), "A-4", null, b.proveedor()))
                .hasMessageContaining("fk_articulo_proveedor");
        assertThatThrownBy(() -> renglon(a.ferreteria(), a.comprobante(), b.articulo()))
                .hasMessageContaining("fk_item_comprobante_articulo");
        assertThatThrownBy(() -> renglon(a.ferreteria(), b.comprobante(), a.articulo()))
                .hasMessageContaining("fk_item_comprobante_comprobante");
        assertThatThrownBy(() -> jdbc.update("update articulo set rubro_id = ? where id = ?", b.rubro(), a.articulo()))
                .hasMessageContaining("fk_articulo_rubro");
        assertThatThrownBy(() -> jdbc.update("update comprobante set cliente_id = ? where id = ?", b.cliente(), a.comprobante()))
                .hasMessageContaining("fk_comprobante_cliente");
        assertThatThrownBy(() -> jdbc.update("update comprobante set usuario_id = ? where id = ?", b.usuario(), a.comprobante()))
                .hasMessageContaining("fk_comprobante_usuario");
        // Mudar una fila a otra ferreteria tampoco: rompe las referencias de sus hijos o las suyas.
        // Cual FK salta primero lo decide MySQL.
        assertThatThrownBy(() -> jdbc.update("update rubro set ferreteria_id = ?, nombre = 'Mudado' where id = ?",
                b.ferreteria(), a.rubro())).hasMessageContaining("fk_articulo_rubro");
        assertThatThrownBy(() -> jdbc.update("update articulo set ferreteria_id = ?, codigo_interno = 'MUDADO' where id = ?",
                b.ferreteria(), a.articulo())).hasMessageContaining("a foreign key constraint fails");

        jdbc.update("update comprobante set cliente_id = ? where id = ?", a.cliente(), a.comprobante());
        assertThat(jdbc.queryForObject("select count(*) from item_comprobante", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("referencias: un usuario SUPER_ADMIN (sin ferreteria) no puede quedar en datos de negocio")
    void superAdminFueraDeLosDatosDeNegocio() {
        Escenario a = escenario();
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (null, 'admin-v29@test.local', 'x', 'Admin', 'SUPER_ADMIN')");
        long admin = jdbc.queryForObject("select id from usuario where email = 'admin-v29@test.local'", Long.class);
        migrarHasta("29");

        assertThatThrownBy(() -> jdbc.update("update comprobante set usuario_id = ? where id = ?", admin, a.comprobante()))
                .hasMessageContaining("fk_comprobante_usuario");
    }

    // ---------------------------------------------------------------- datos existentes

    @Test
    @DisplayName("datos existentes: sin referencias cruzadas la V29 corre y ninguna fila cambia")
    void datosExistentesIntactos() {
        Escenario a = escenario();
        Escenario b = escenario();
        renglon(a.ferreteria(), a.comprobante(), a.articulo());
        renglon(b.ferreteria(), b.comprobante(), b.articulo());
        String articulos = "select * from articulo order by id";
        String renglones = "select * from item_comprobante order by id";
        String comprobantes = "select * from comprobante order by id";
        List<Map<String, Object>> articulosAntes = jdbc.queryForList(articulos);
        List<Map<String, Object>> renglonesAntes = jdbc.queryForList(renglones);
        List<Map<String, Object>> comprobantesAntes = jdbc.queryForList(comprobantes);

        migrarHasta("29");

        assertThat(jdbc.queryForList(articulos)).isEqualTo(articulosAntes);
        assertThat(jdbc.queryForList(renglones)).isEqualTo(renglonesAntes);
        assertThat(jdbc.queryForList(comprobantes)).isEqualTo(comprobantesAntes);
    }

    @Test
    @DisplayName("datos existentes: con una referencia a otra ferreteria la V29 se niega antes de tocar el esquema")
    void guardaConReferenciaCruzada() {
        Escenario a = escenario();
        Escenario b = escenario();
        articulo(a.ferreteria(), "A-9", b.rubro(), null);
        List<Map<String, Object>> esquemaAntes = fksEIndices();

        assertThatThrownBy(() -> migrarHasta("29")).hasStackTraceContaining("ck_v29_sin_referencias_a_otra_ferreteria");

        assertThat(fksEIndices()).isEqualTo(esquemaAntes);
    }

    @Test
    @DisplayName("guarda: revisa cada una de las 56 FK de negocio de la V28")
    void guardaCubreTodasLasFks() throws Exception {
        String v29 = StreamUtils.copyToString(new ClassPathResource("db/migration/V29__fk_por_ferreteria.sql").getInputStream(),
                StandardCharsets.UTF_8);
        String guarda = v29.substring(v29.indexOf("INSERT INTO guarda_v29"), v29.indexOf("DROP TEMPORARY TABLE guarda_v29"));

        assertThat(jdbc.queryForList("""
                select table_name hijo, column_name columna, referenced_table_name padre
                from information_schema.key_column_usage
                where table_schema = database() and referenced_table_name is not null
                  and referenced_table_name <> 'ferreteria'""")).hasSize(56).allSatisfy(fk -> assertThat(guarda)
                .contains("FROM %s h JOIN %s p ON p.id = h.%s WHERE".formatted(fk.get("hijo"), fk.get("padre"), fk.get("columna"))));
    }

    @Test
    @DisplayName("guarda: un dato de negocio que apunta a un SUPER_ADMIN tambien frena la V29")
    void guardaConSuperAdmin() {
        Escenario a = escenario();
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (null, 'admin-guarda-v29@test.local', 'x', 'Admin', 'SUPER_ADMIN')");
        long admin = jdbc.queryForObject("select id from usuario where email = 'admin-guarda-v29@test.local'", Long.class);
        jdbc.update("update comprobante set usuario_id = ? where id = ?", admin, a.comprobante());
        List<Map<String, Object>> esquemaAntes = fksEIndices();

        assertThatThrownBy(() -> migrarHasta("29")).hasStackTraceContaining("ck_v29_sin_referencias_a_otra_ferreteria");

        assertThat(fksEIndices()).isEqualTo(esquemaAntes);
    }

    @Test
    @DisplayName("indices: la V29 no saca ningun indice de la V28 (las consultas del padre a los hijos los siguen usando)")
    void conservaLosIndicesDeLaV28() {
        String indices = "select table_name, index_name, non_unique, group_concat(column_name order by seq_in_index) columnas "
                + "from information_schema.statistics where table_schema = database() "
                + "and table_name <> 'flyway_schema_history' group by 1, 2, 3";
        List<Map<String, Object>> antes = jdbc.queryForList(indices);

        migrarHasta("29");

        assertThat(jdbc.queryForList(indices)).containsAll(antes);
    }

    // ---------------------------------------------------------------- rollback

    private void correrRollback() throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        String script = StreamUtils.copyToString(new ClassPathResource("tenant/rollback-v29-fk-por-ferreteria.sql")
                .getInputStream(), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(script);
        }
    }

    @Test
    @DisplayName("rollback-v29: deja las FK y los indices como en la V28, conserva las filas y la V29 se puede volver a aplicar")
    void rollback() throws Exception {
        Escenario a = escenario();
        renglon(a.ferreteria(), a.comprobante(), a.articulo());
        List<Map<String, Object>> esquemaV28 = fksEIndices();
        migrarHasta("29");

        correrRollback();

        assertThat(fksEIndices()).isEqualTo(esquemaV28);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '29'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from item_comprobante", Integer.class)).isEqualTo(1);

        migrarHasta("29");
        assertThat(fksDeNegocio()).allSatisfy(fk -> assertThat(fk.get("columnas_padre")).isEqualTo("ferreteria_id,id"));
    }
}
