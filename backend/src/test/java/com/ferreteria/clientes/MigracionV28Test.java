package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V28 (ajustes de cuenta corriente, GDS-20 tramo 3b-2): nacen el numerador y la tabla de ajustes, el libro enlaza su
 * AJUSTE con el ajuste, y la imputacion pasa a tener un origen (recibo o Descuento) y un destino (comprobante o Recargo).
 * Aditiva: las filas existentes no cambian y ninguna restriccion anterior se debilita. Usa un MySQL de Testcontainers propio y
 * una base nueva por test, con las migraciones hasta la V26 aplicadas. CUIT: bloque 30-8528NNNN-1.
 */
class MigracionV28Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final AtomicInteger CUITS = new AtomicInteger();
    private static final AtomicInteger NUMEROS = new AtomicInteger();

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion26() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v28", "26"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    // ---------------------------------------------------------------- siembra

    /** Una ferreteria con su Dueño, un cliente, un comprobante con su pago y una caja: lo minimo para asentar. */
    private record Escenario(long ferreteria, long usuario, long cliente, long comprobante, long pago, long caja) {}

    private Escenario escenario() {
        String cuit = "30-8528%04d-1".formatted(CUITS.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V28', 'DUENO')",
                f, "v28-" + cuit + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values (?, 'Efectivo', 'EFECTIVO', 1)", f);
        long forma = jdbc.queryForObject("select min(id) from forma_pago where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", f, "Cliente " + cuit);
        long cliente = jdbc.queryForObject("select id from cliente where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
                + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', 1, ?, ?, 100, 21, 121, 121)", f, "v28-" + cuit, usuario);
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                + "values (?, ?, 'EFECTIVO', 60.50, ?, 'Efectivo')", f, comprobante, forma);
        long pago = jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, comprobante);
        jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, usuario_apertura_id, efectivo_inicial) "
                + "values (?, true, now(6), ?, 0)", f, usuario);
        long caja = jdbc.queryForObject("select max(id) from caja where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, usuario, cliente, comprobante, pago, caja);
    }

    private long recibo(Escenario e, String total) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), 'EFECTIVO', ?)", e.ferreteria(),
                numero, "rec-" + e.ferreteria() + "-" + numero, e.cliente(), e.caja(), e.usuario(), new BigDecimal(total));
        return jdbc.queryForObject("select id from recibo where ferreteria_id = ? and numero = ?", Long.class, e.ferreteria(),
                numero);
    }

    private long ajuste(Escenario e, String tipo, String importe) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into ajuste_cuenta (ferreteria_id, cliente_id, numero, tipo, importe, motivo, clave, fecha, usuario_id) "
                + "values (?, ?, ?, ?, ?, 'Deuda vieja', ?, now(6), ?)", e.ferreteria(), e.cliente(), numero, tipo,
                new BigDecimal(importe), "aj-" + e.ferreteria() + "-" + numero, e.usuario());
        return jdbc.queryForObject("select id from ajuste_cuenta where ferreteria_id = ? and numero = ?", Long.class,
                e.ferreteria(), numero);
    }

    private void movimiento(Escenario e, String tipo, String debe, String haber, Long ajusteId, String motivo) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "referencia, motivo, ajuste_id) values (?, ?, ?, ?, ?, now(6), ?, 'ref', ?, ?)", e.ferreteria(), e.cliente(),
                tipo, new BigDecimal(debe), new BigDecimal(haber), e.usuario(), motivo, ajusteId);
    }

    /** Una imputacion con cualquier combinacion de origen y destino (null = NULL). */
    private void imputacion(Escenario e, Long recibo, Long ajusteOrigen, Long comprobante, Long ajusteDestino) {
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, ajuste_origen_id, comprobante_id, "
                + "ajuste_destino_id, importe) values (?, ?, ?, ?, ?, ?, 10)", e.ferreteria(), e.cliente(), recibo, ajusteOrigen,
                comprobante, ajusteDestino);
    }

    private List<String> restriccionesDe(String tabla, String tipo) {
        return jdbc.queryForList("select constraint_name from information_schema.table_constraints "
                + "where table_schema = database() and table_name = ? and constraint_type = ? order by 1", String.class,
                tabla, tipo);
    }

    private List<String> columnasDe(String tabla) {
        return jdbc.queryForList("select column_name from information_schema.columns where table_schema = database() "
                + "and table_name = ? order by ordinal_position", String.class, tabla);
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

    // ---------------------------------------------------------------- esquema

    @Test
    @DisplayName("esquema: nacen numerador_ajuste_cuenta y ajuste_cuenta, el libro gana ajuste_id y la imputacion gana origen/destino de ajuste y deja recibo/comprobante opcionales")
    void esquema() {
        migrarHasta("28");

        assertThat(columnasDe("numerador_ajuste_cuenta")).containsExactly("id", "ferreteria_id", "ultimo_numero");
        assertThat(columnasDe("ajuste_cuenta")).containsExactly("id", "ferreteria_id", "cliente_id", "numero", "tipo",
                "importe", "motivo", "clave", "fecha", "usuario_id");
        assertThat(columna("ajuste_cuenta", "importe")).isEqualTo("decimal(15,2) NO");
        assertThat(columna("ajuste_cuenta", "motivo")).isEqualTo("varchar(200) NO");
        assertThat(columna("ajuste_cuenta", "clave")).isEqualTo("varchar(36) NO");
        assertThat(columna("movimiento_cuenta_cliente", "ajuste_id")).isEqualTo("bigint YES");
        assertThat(columna("imputacion", "recibo_id")).isEqualTo("bigint YES");
        assertThat(columna("imputacion", "comprobante_id")).isEqualTo("bigint YES");
        assertThat(columna("imputacion", "ajuste_origen_id")).isEqualTo("bigint YES");
        assertThat(columna("imputacion", "ajuste_destino_id")).isEqualTo("bigint YES");
        assertThat(columna("imputacion", "automatica")).isEqualTo("tinyint(1) NO");
    }

    @Test
    @DisplayName("aditiva: las filas existentes quedan iguales y ninguna restriccion anterior se debilita")
    void aditiva() {
        Escenario e = escenario();
        long recibo = recibo(e, "100.00");
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, pago_id, "
                + "fecha, usuario_id, referencia) values (?, ?, 'VENTA', 60.50, 0, ?, ?, now(6), ?, 'Venta N.º 1')",
                e.ferreteria(), e.cliente(), e.comprobante(), e.pago(), e.usuario());
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "recibo_id, referencia) values (?, ?, 'COBRO', 0, 100, now(6), ?, ?, 'Recibo N.º 1')", e.ferreteria(),
                e.cliente(), e.usuario(), recibo);
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, 60.50)", e.ferreteria(), e.cliente(), recibo, e.comprobante());
        List<String> checksAntes = restriccionesDe("movimiento_cuenta_cliente", "CHECK");
        List<String> checksImputacionAntes = restriccionesDe("imputacion", "CHECK");
        List<String> uniquesAntes = restriccionesDe("imputacion", "UNIQUE");
        List<Map<String, Object>> imputacionesAntes = jdbc.queryForList(
                "select id, ferreteria_id, cliente_id, recibo_id, comprobante_id, importe, vigente from imputacion order by id");
        List<Map<String, Object>> libroAntes = jdbc.queryForList(
                "select id, ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, pago_id, recibo_id, referencia, motivo "
                        + "from movimiento_cuenta_cliente order by id");

        migrarHasta("28");

        assertThat(jdbc.queryForList("select id, ferreteria_id, cliente_id, recibo_id, comprobante_id, importe, vigente "
                + "from imputacion order by id")).isEqualTo(imputacionesAntes);
        assertThat(jdbc.queryForList("select id, ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, pago_id, "
                + "recibo_id, referencia, motivo from movimiento_cuenta_cliente order by id")).isEqualTo(libroAntes);
        assertThat(jdbc.queryForObject("select automatica from imputacion", Boolean.class)).isFalse();
        assertThat(restriccionesDe("movimiento_cuenta_cliente", "CHECK")).containsAll(checksAntes);
        assertThat(restriccionesDe("imputacion", "CHECK")).containsAll(checksImputacionAntes);
        assertThat(restriccionesDe("imputacion", "UNIQUE")).containsAll(uniquesAntes);
    }

    // ---------------------------------------------------------------- ajuste_cuenta

    @Test
    @DisplayName("ajuste_cuenta: tipo RECARGO/DESCUENTO, importe mayor que cero y motivo de 5 a 200 caracteres")
    void checksDelAjuste() {
        migrarHasta("28");
        Escenario e = escenario();

        ajuste(e, "RECARGO", "10.00");
        ajuste(e, "DESCUENTO", "0.01");
        assertThatThrownBy(() -> ajuste(e, "OTRO", "10.00")).hasMessageContaining("ck_ajuste_cuenta_tipo");
        assertThatThrownBy(() -> ajuste(e, "RECARGO", "0")).hasMessageContaining("ck_ajuste_cuenta_importe");
        assertThatThrownBy(() -> ajuste(e, "RECARGO", "-1")).hasMessageContaining("ck_ajuste_cuenta_importe");
        assertThatThrownBy(() -> jdbc.update("update ajuste_cuenta set motivo = 'abc' where ferreteria_id = ?", e.ferreteria()))
                .hasMessageContaining("ck_ajuste_cuenta_motivo");
        assertThatThrownBy(() -> jdbc.update("update ajuste_cuenta set motivo = ? where ferreteria_id = ?", "x".repeat(201),
                e.ferreteria())).hasMessageContaining("Data too long");
    }

    @Test
    @DisplayName("ajuste_cuenta: el numero y la clave son unicos por ferreteria (y se repiten entre ferreterias)")
    void numeroYClaveUnicosPorFerreteria() {
        migrarHasta("28");
        Escenario a = escenario();
        Escenario b = escenario();
        jdbc.update("insert into ajuste_cuenta (ferreteria_id, cliente_id, numero, tipo, importe, motivo, clave, fecha, usuario_id) "
                + "values (?, ?, 1, 'RECARGO', 5, 'Deuda vieja', 'clave-1', now(6), ?)", a.ferreteria(), a.cliente(), a.usuario());

        String insertar = "insert into ajuste_cuenta (ferreteria_id, cliente_id, numero, tipo, importe, motivo, clave, fecha, "
                + "usuario_id) values (?, ?, ?, 'RECARGO', 5, 'Deuda vieja', ?, now(6), ?)";
        assertThatThrownBy(() -> jdbc.update(insertar, a.ferreteria(), a.cliente(), 1, "clave-2", a.usuario()))
                .hasMessageContaining("uk_ajuste_cuenta_numero");
        assertThatThrownBy(() -> jdbc.update(insertar, a.ferreteria(), a.cliente(), 2, "clave-1", a.usuario()))
                .hasMessageContaining("uk_ajuste_cuenta_clave");
        jdbc.update(insertar, b.ferreteria(), b.cliente(), 1, "clave-1", b.usuario());
        assertThat(jdbc.queryForObject("select count(*) from ajuste_cuenta", Integer.class)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- libro

    @Test
    @DisplayName("libro: un AJUSTE lleva su ajuste y solo el AJUSTE lo lleva; un ajuste tiene un solo movimiento")
    void libroYAjuste() {
        migrarHasta("28");
        Escenario e = escenario();
        long recargo = ajuste(e, "RECARGO", "10.00");
        long descuento = ajuste(e, "DESCUENTO", "5.00");

        movimiento(e, "AJUSTE", "10.00", "0", recargo, "Deuda vieja");
        movimiento(e, "AJUSTE", "0", "5.00", descuento, "Condonacion");
        assertThatThrownBy(() -> movimiento(e, "AJUSTE", "10.00", "0", null, "Deuda vieja"))
                .hasMessageContaining("ck_mcc_ajuste_ref");
        assertThatThrownBy(() -> movimiento(e, "NC", "0", "5.00", recargo, null)).hasMessageContaining("ck_mcc_ajuste_ref");
        assertThatThrownBy(() -> movimiento(e, "AJUSTE", "10.00", "0", recargo, "Deuda vieja"))
                .hasMessageContaining("uk_mcc_ajuste");
    }

    // ---------------------------------------------------------------- imputacion

    @Test
    @DisplayName("imputacion: exactamente un origen (recibo o Descuento) y exactamente un destino (comprobante o Recargo)")
    void origenYDestino() {
        migrarHasta("28");
        Escenario e = escenario();
        long recibo = recibo(e, "100.00");
        long descuento = ajuste(e, "DESCUENTO", "50.00");
        long recargo = ajuste(e, "RECARGO", "20.00");

        imputacion(e, recibo, null, e.comprobante(), null);
        imputacion(e, recibo, null, null, recargo);
        imputacion(e, null, descuento, e.comprobante(), null);
        imputacion(e, null, descuento, null, recargo);
        assertThatThrownBy(() -> imputacion(e, null, null, e.comprobante(), null)).hasMessageContaining("ck_imputacion_origen");
        assertThatThrownBy(() -> imputacion(e, recibo, descuento, e.comprobante(), null))
                .hasMessageContaining("ck_imputacion_origen");
        assertThatThrownBy(() -> imputacion(e, recibo, null, null, null)).hasMessageContaining("ck_imputacion_destino");
        assertThatThrownBy(() -> imputacion(e, recibo, null, e.comprobante(), recargo))
                .hasMessageContaining("ck_imputacion_destino");
    }

    @Test
    @DisplayName("imputacion: cada par (origen, destino) es unico")
    void paresUnicos() {
        migrarHasta("28");
        Escenario e = escenario();
        long recibo = recibo(e, "100.00");
        long descuento = ajuste(e, "DESCUENTO", "50.00");
        long recargo = ajuste(e, "RECARGO", "20.00");
        imputacion(e, recibo, null, e.comprobante(), null);
        imputacion(e, recibo, null, null, recargo);
        imputacion(e, null, descuento, e.comprobante(), null);
        imputacion(e, null, descuento, null, recargo);

        assertThatThrownBy(() -> imputacion(e, recibo, null, e.comprobante(), null))
                .hasMessageContaining("uk_imputacion_recibo_comprobante");
        assertThatThrownBy(() -> imputacion(e, recibo, null, null, recargo)).hasMessageContaining("uk_imputacion_recibo_ajuste");
        assertThatThrownBy(() -> imputacion(e, null, descuento, e.comprobante(), null))
                .hasMessageContaining("uk_imputacion_ajuste_comprobante");
        assertThatThrownBy(() -> imputacion(e, null, descuento, null, recargo))
                .hasMessageContaining("uk_imputacion_ajuste_ajuste");
    }

    // ---------------------------------------------------------------- rollback

    private void correrRollback() throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        String script = StreamUtils.copyToString(new ClassPathResource("clientes/rollback-v28-ajustes-cuenta-corriente.sql")
                .getInputStream(), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(script);
        }
    }

    @Test
    @DisplayName("rollback-v28 sin ajustes cargados: saca todo lo de la V28, deja las filas y la V28 se puede volver a aplicar")
    void rollbackSinAjustes() throws Exception {
        Escenario e = escenario();
        long recibo = recibo(e, "100.00");
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, 60.50)", e.ferreteria(), e.cliente(), recibo, e.comprobante());
        migrarHasta("28");

        correrRollback();

        assertThat(existeLaTabla("ajuste_cuenta")).isFalse();
        assertThat(existeLaTabla("numerador_ajuste_cuenta")).isFalse();
        assertThat(columnasDe("movimiento_cuenta_cliente")).doesNotContain("ajuste_id");
        assertThat(columnasDe("imputacion")).doesNotContain("ajuste_origen_id", "ajuste_destino_id", "automatica");
        assertThat(columna("imputacion", "recibo_id")).isEqualTo("bigint NO");
        assertThat(columna("imputacion", "comprobante_id")).isEqualTo("bigint NO");
        assertThat(restriccionesDe("imputacion", "UNIQUE")).containsExactly("uk_imputacion_recibo_comprobante");
        assertThat(restriccionesDe("imputacion", "CHECK")).containsExactly("ck_imputacion_importe");
        assertThat(restriccionesDe("movimiento_cuenta_cliente", "CHECK")).doesNotContain("ck_mcc_ajuste_ref");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '28'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from imputacion", Integer.class)).isEqualTo(1);

        migrarHasta("28");
        assertThat(existeLaTabla("ajuste_cuenta")).isTrue();
    }

    @Test
    @DisplayName("rollback-v28 con un ajuste cargado: se niega y no cambia nada (con ajustes se conserva todo)")
    void rollbackConAjustes() throws Exception {
        migrarHasta("28");
        Escenario e = escenario();
        long recargo = ajuste(e, "RECARGO", "10.00");
        movimiento(e, "AJUSTE", "10.00", "0", recargo, "Deuda vieja");

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_sin_ajustes");

        assertThat(existeLaTabla("ajuste_cuenta")).isTrue();
        assertThat(columnasDe("movimiento_cuenta_cliente")).contains("ajuste_id");
        assertThat(columnasDe("imputacion")).contains("ajuste_origen_id", "ajuste_destino_id");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '28'", Integer.class))
                .isEqualTo(1);
    }
}
