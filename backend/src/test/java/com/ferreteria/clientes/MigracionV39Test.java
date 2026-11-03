package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V39 (CU-08 y CU-12, cu-08-nc-cuenta-corriente T2: S1): la nota de credito (o la anulacion interna) de una venta a
 * cuenta corriente es el tercer origen de credito de la imputacion. Nace imputacion.nc_origen_id con su FK compuesta al
 * comprobante NOTA_CREDITO, el CHECK de origen pasa a «exactamente uno de recibo, Descuento o NC» con nombre nuevo, y nacen
 * el unico por (NC, Recargo); a un comprobante la NC puede imputar mas de una vez (la reimputacion a la venta anulada cuando
 * se anula el recibo que la pagaba, L10). El libro no cambia: su renglon NC ya entra con los CHECK de V20 a V28. Molde de MigracionV28Test y
 * MigracionV37Test: un MySQL de Testcontainers propio y una base nueva por test, migrada hasta la V38.
 */
class MigracionV39Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final AtomicInteger NUMEROS = new AtomicInteger();

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion38() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v39", "38"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
            .target(version).load().migrate();
    }

    // ---------------------------------------------------------------- esquema

    @Test
    @DisplayName("esquema: imputacion gana nc_origen_id (bigint, nula) con su indice (ferreteria_id, nc_origen_id), el CHECK de "
        + "origen cambia de nombre y nace el unico (NC, Recargo)")
    void columnaIndicesYRestricciones() {
        migrarHasta("39");

        assertThat(columna("imputacion", "nc_origen_id")).isEqualTo("bigint YES");
        assertThat(indice("ix_imputacion_ferreteria_nc_origen")).containsExactly("ferreteria_id", "nc_origen_id");
        assertThat(indice("uk_imputacion_nc_comprobante")).isEmpty();
        assertThat(indice("uk_imputacion_nc_ajuste")).containsExactly("nc_origen_id", "ajuste_destino_id");
        assertThat(restriccionesDe("imputacion", "CHECK")).contains("ck_imputacion_origen_v39", "ck_imputacion_destino")
            .doesNotContain("ck_imputacion_origen");
        assertThat(restriccionesDe("imputacion", "FOREIGN KEY")).contains("fk_imputacion_nc_origen");
    }

    @Test
    @DisplayName("aditiva para los datos: las filas quedan iguales y lo unico que se va del esquema es el CHECK de origen de V28")
    void aditiva() {
        Escenario e = escenario("a");
        long recibo = recibo(e);
        long descuento = ajuste(e, "DESCUENTO");
        imputacion(e, recibo, null, null, e.venta(), null);
        imputacion(e, null, descuento, null, null, ajuste(e, "RECARGO"));
        List<Map<String, Object>> imputacionesAntes = jdbc.queryForList("select * from imputacion order by id");
        List<Map<String, Object>> libroAntes = jdbc.queryForList("select * from movimiento_cuenta_cliente order by id");
        List<String> esquemaAntes = esquema();

        migrarHasta("39");

        assertThat(jdbc.queryForList("select * from imputacion order by id")).allSatisfy(fila ->
            assertThat(fila).containsEntry("nc_origen_id", null));
        assertThat(jdbc.queryForList("select * from imputacion order by id").stream().map(MigracionV39Test::sinLaNueva)
            .toList()).isEqualTo(imputacionesAntes);
        assertThat(jdbc.queryForList("select * from movimiento_cuenta_cliente order by id")).isEqualTo(libroAntes);
        List<String> esquemaDespues = esquema();
        assertThat(esquemaAntes.stream().filter(fila -> !esquemaDespues.contains(fila)).toList()).isNotEmpty()
            .allSatisfy(fila -> assertThat(fila).startsWith("imputacion ").contains("ck_imputacion_origen"));
        assertThat(esquemaDespues.stream().filter(fila -> !esquemaAntes.contains(fila)).toList()).isNotEmpty()
            .allSatisfy(fila -> assertThat(fila).startsWith("imputacion ").containsAnyOf("nc_origen_id",
                "uk_imputacion_nc_", "ix_imputacion_ferreteria_nc_origen", "fk_imputacion_nc_origen",
                "ck_imputacion_origen_v39"));
    }

    // ---------------------------------------------------------------- imputacion

    @Test
    @DisplayName("ck_imputacion_origen_v39: exactamente un origen (recibo, Descuento o NC); cero o dos se rechazan, y el destino "
        + "sigue siendo uno")
    void exactamenteUnOrigen() {
        migrarHasta("39");
        Escenario e = escenario("a");
        long recibo = recibo(e);
        long descuento = ajuste(e, "DESCUENTO");
        long recargo = ajuste(e, "RECARGO");
        long nc = notaDeCredito(e);

        imputacion(e, recibo, null, null, e.venta(), null);
        imputacion(e, null, descuento, null, e.venta(), null);
        imputacion(e, null, null, nc, e.venta(), null);
        imputacion(e, null, null, nc, null, recargo);
        assertThatThrownBy(() -> imputacion(e, null, null, null, e.venta(), null))
            .hasMessageContaining("ck_imputacion_origen_v39");
        assertThatThrownBy(() -> imputacion(e, recibo, null, nc, e.venta(), null))
            .hasMessageContaining("ck_imputacion_origen_v39");
        assertThatThrownBy(() -> imputacion(e, null, descuento, nc, e.venta(), null))
            .hasMessageContaining("ck_imputacion_origen_v39");
        assertThatThrownBy(() -> imputacion(e, recibo, descuento, null, e.venta(), null))
            .hasMessageContaining("ck_imputacion_origen_v39");
        assertThatThrownBy(() -> imputacion(e, recibo, descuento, nc, e.venta(), null))
            .hasMessageContaining("ck_imputacion_origen_v39");
        assertThatThrownBy(() -> imputacion(e, null, null, nc, null, null)).hasMessageContaining("ck_imputacion_destino");
        assertThatThrownBy(() -> imputacion(e, null, null, nc, e.venta(), recargo))
            .hasMessageContaining("ck_imputacion_destino");
    }

    @Test
    @DisplayName("fk_imputacion_nc_origen: la NC tiene que existir y ser de la misma ferreteria")
    void laNcEsDeLaFerreteria() {
        migrarHasta("39");
        Escenario a = escenario("a");
        Escenario b = escenario("b");
        long ncDeB = notaDeCredito(b);

        assertThatThrownBy(() -> imputacion(a, null, null, ncDeB, a.venta(), null))
            .hasMessageContaining("fk_imputacion_nc_origen");
        assertThatThrownBy(() -> imputacion(a, null, null, ncDeB + 1000, a.venta(), null))
            .hasMessageContaining("fk_imputacion_nc_origen");
        assertThatCode(() -> imputacion(b, null, null, ncDeB, b.venta(), null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unicos por par: una NC imputa una sola vez a cada Recargo, y a un comprobante puede volver a imputarle")
    void paresUnicos() {
        migrarHasta("39");
        Escenario e = escenario("a");
        long nc = notaDeCredito(e);
        long recargo = ajuste(e, "RECARGO");
        imputacion(e, null, null, nc, e.venta(), null);
        imputacion(e, null, null, nc, null, recargo);

        assertThatCode(() -> imputacion(e, null, null, nc, e.venta(), null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> imputacion(e, null, null, nc, null, recargo)).hasMessageContaining("uk_imputacion_nc_ajuste");
        assertThatCode(() -> imputacion(e, null, null, nc, venta(e.ferreteria(), e.usuario()), null))
            .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- libro

    @Test
    @DisplayName("libro sin cambios: el renglon NC (haber, pago a cuenta de la venta, comprobante de la NC, sin recibo ni motivo) "
        + "entra, y un segundo NC del mismo pago choca con uk_mcc_pago_tipo")
    void elRenglonNcEntraEnElLibro() {
        migrarHasta("39");
        Escenario e = escenario("a");
        long nc = notaDeCredito(e);

        renglonNc(e, nc, "500.00", null, null);

        assertThatThrownBy(() -> renglonNc(e, nc, "500.00", null, null)).hasMessageContaining("uk_mcc_pago_tipo");
        assertThatThrownBy(() -> renglonNc(e, nc, "500.00", recibo(e), null)).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> renglonNc(e, nc, "500.00", null, "Se cobro dos veces"))
            .hasMessageContaining("ck_mcc_motivo");
        assertThatThrownBy(() -> renglonNc(e, nc, "0", null, null)).hasMessageContaining("ck_mcc_importes");
    }

    // ---------------------------------------------------------------- rollback

    @Test
    @DisplayName("rollback-v39 sin NC: deja el esquema como en la V38, las filas como estaban, saca la 39 del historial y la V39 "
        + "vuelve a entrar")
    void rollbackSinNc() throws Exception {
        Escenario e = escenario("a");
        imputacion(e, recibo(e), null, null, e.venta(), null);
        List<String> esquemaAntes = esquema();
        migrarHasta("39");
        imputacion(e, null, ajuste(e, "DESCUENTO"), null, e.venta(), null);
        List<Map<String, Object>> imputacionesAntes = jdbc.queryForList("select * from imputacion order by id").stream()
            .map(MigracionV39Test::sinLaNueva).toList();

        correrRollback("clientes/rollback-v39-nc-cuenta-corriente.sql");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from imputacion order by id")).isEqualTo(imputacionesAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '39'", Integer.class))
            .isZero();
        migrarHasta("39");
        assertThat(columna("imputacion", "nc_origen_id")).isEqualTo("bigint YES");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"la NC en el libro y su imputacion", "solo la NC en el libro", "solo la imputacion de la NC"})
    @DisplayName("rollback-v39 con una NC se niega y no toca nada")
    void rollbackConNcSeNiega(String caso) {
        migrarHasta("39");
        Escenario e = escenario("a");
        long nc = notaDeCredito(e);
        if (!caso.equals("solo la imputacion de la NC")) {
            renglonNc(e, nc, "500.00", null, null);
        }
        if (!caso.equals("solo la NC en el libro")) {
            imputacion(e, null, null, nc, e.venta(), null);
        }
        List<String> esquemaAntes = esquema();
        List<Map<String, Object>> imputacionesAntes = jdbc.queryForList("select * from imputacion order by id");

        assertThatThrownBy(() -> correrRollback("clientes/rollback-v39-nc-cuenta-corriente.sql"))
            .hasMessageContaining(caso.equals("solo la imputacion de la NC") ? "ck_rollback_v39_sin_nc"
                : "ck_rollback_v39_sin_nc_en_libro");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from imputacion order by id")).isEqualTo(imputacionesAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '39'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("la cadena: despues del rollback de la V39, el de la V28 (que suelta ck_imputacion_origen) sigue andando")
    void cadenaConElRollbackDeV28() throws Exception {
        Escenario e = escenario("a");
        imputacion(e, recibo(e), null, null, e.venta(), null);
        migrarHasta("39");

        correrRollback("clientes/rollback-v39-nc-cuenta-corriente.sql");
        correrRollback("clientes/rollback-v28-ajustes-cuenta-corriente.sql");

        assertThat(existeLaTabla("ajuste_cuenta")).isFalse();
        assertThat(columnasDe("imputacion")).doesNotContain("nc_origen_id", "ajuste_origen_id", "ajuste_destino_id",
            "automatica");
        assertThat(restriccionesDe("imputacion", "CHECK")).containsExactly("ck_imputacion_importe");
        assertThat(jdbc.queryForObject("select count(*) from imputacion", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('28', '39')",
            Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- siembra

    /** Una ferreteria con su Dueño, un cliente, una venta con su pago a cuenta corriente y una caja. */
    private record Escenario(long ferreteria, long usuario, long cliente, long venta, long pago, long caja) {}

    private Escenario escenario(String sufijo) {
        // Cada test tiene su base: alcanza con que la A y la B no choquen (uk_ferreteria_cuit).
        String cuit = "30-78601390-" + sufijo;
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria V39 " + sufijo, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V39', "
            + "'DUENO')", f, "v39-" + f + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values (?, 'A cuenta', 'CUENTA_CORRIENTE', 9)",
            f);
        long forma = jdbc.queryForObject("select max(id) from forma_pago where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", f, "Cliente V39 " + sufijo);
        long cliente = jdbc.queryForObject("select id from cliente where ferreteria_id = ?", Long.class, f);
        long venta = venta(f, usuario);
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
            + "values (?, ?, 'CUENTA_CORRIENTE', 500, ?, 'A cuenta')", f, venta, forma);
        long pago = jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, venta);
        jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, usuario_apertura_id, efectivo_inicial) "
            + "values (?, true, now(6), ?, 0)", f, usuario);
        long caja = jdbc.queryForObject("select max(id) from caja where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, usuario, cliente, venta, pago, caja);
    }

    private long venta(long ferreteria, long usuario) {
        return comprobante(ferreteria, usuario, "VENTA", null);
    }

    /** La NOTA_CREDITO de la venta del escenario (sin motivo: la de ARCA). */
    private long notaDeCredito(Escenario e) {
        return comprobante(e.ferreteria(), e.usuario(), "NOTA_CREDITO", e.venta());
    }

    private long comprobante(long ferreteria, long usuario, String tipo, Long asociado) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal, comprobante_asociado_id) values (?, ?, 'SIN_FACTURAR', ?, ?, ?, 1000, 210, 1210, 1210, ?)",
            ferreteria, tipo, numero, "v39-" + numero, usuario, asociado);
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
            ferreteria, numero);
    }

    private long recibo(Escenario e) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
            + "usuario_id, fecha, medio, total) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), 'EFECTIVO', 100)",
            e.ferreteria(), numero, "rec-" + numero, e.cliente(), e.caja(), e.usuario());
        return jdbc.queryForObject("select id from recibo where ferreteria_id = ? and numero = ?", Long.class, e.ferreteria(),
            numero);
    }

    private long ajuste(Escenario e, String tipo) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into ajuste_cuenta (ferreteria_id, cliente_id, numero, tipo, importe, motivo, clave, fecha, usuario_id) "
            + "values (?, ?, ?, ?, 50, 'Deuda vieja', ?, now(6), ?)", e.ferreteria(), e.cliente(), numero, tipo,
            "aj-" + numero, e.usuario());
        return jdbc.queryForObject("select id from ajuste_cuenta where ferreteria_id = ? and numero = ?", Long.class,
            e.ferreteria(), numero);
    }

    /** Una imputacion con cualquier combinacion de origenes y destinos (null = NULL); sin nc_origen_id antes de la V39. */
    private void imputacion(Escenario e, Long recibo, Long ajusteOrigen, Long nc, Long comprobante, Long ajusteDestino) {
        if (nc == null && !existeLaColumna("imputacion", "nc_origen_id")) {
            jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, ajuste_origen_id, comprobante_id, "
                + "ajuste_destino_id, importe) values (?, ?, ?, ?, ?, ?, 10)", e.ferreteria(), e.cliente(), recibo,
                ajusteOrigen, comprobante, ajusteDestino);
            return;
        }
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, ajuste_origen_id, nc_origen_id, "
            + "comprobante_id, ajuste_destino_id, importe) values (?, ?, ?, ?, ?, ?, ?, 10)", e.ferreteria(), e.cliente(),
            recibo, ajusteOrigen, nc, comprobante, ajusteDestino);
    }

    /** El renglon NC del libro: el haber del pago a cuenta corriente de la venta, con el comprobante de la NC. */
    private void renglonNc(Escenario e, long nc, String haber, Long recibo, String motivo) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
            + "pago_id, recibo_id, fecha, usuario_id, referencia, motivo) values (?, ?, 'NC', 0, ?, ?, ?, ?, now(6), ?, "
            + "'Nota de crédito N.º 2', ?)", e.ferreteria(), e.cliente(), new BigDecimal(haber), nc, e.pago(), recibo,
            e.usuario(), motivo);
    }

    /** La fila de imputacion sin la columna de la V39: la que habia antes. */
    private static Map<String, Object> sinLaNueva(Map<String, Object> fila) {
        Map<String, Object> sin = new LinkedHashMap<>(fila);
        sin.remove("nc_origen_id");
        return sin;
    }

    // ---------------------------------------------------------------- lectura del esquema

    private List<String> restriccionesDe(String tabla, String tipo) {
        return jdbc.queryForList("select constraint_name from information_schema.table_constraints "
            + "where table_schema = database() and table_name = ? and constraint_type = ? order by 1", String.class,
            tabla, tipo);
    }

    private List<String> columnasDe(String tabla) {
        return jdbc.queryForList("select column_name from information_schema.columns where table_schema = database() "
            + "and table_name = ? order by ordinal_position", String.class, tabla);
    }

    private boolean existeLaColumna(String tabla, String columna) {
        return columnasDe(tabla).contains(columna);
    }

    private String columna(String tabla, String columna) {
        Map<String, Object> c = jdbc.queryForMap("select column_type, is_nullable from information_schema.columns "
            + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        return c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE");
    }

    private boolean existeLaTabla(String tabla) {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = database() "
            + "and table_name = ?", Integer.class, tabla) > 0;
    }

    /** Las columnas del indice de imputacion, en orden; vacio si no existe. */
    private List<String> indice(String nombre) {
        return jdbc.queryForList("select column_name from information_schema.statistics where table_schema = database() "
            + "and table_name = 'imputacion' and index_name = ? order by seq_in_index", String.class, nombre);
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

    private void correrRollback(String script) throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        String sql = StreamUtils.copyToString(new ClassPathResource(script).getInputStream(), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
