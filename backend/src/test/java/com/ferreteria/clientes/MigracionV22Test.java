package com.ferreteria.clientes;

import static com.ferreteria.support.InvarianteCuenta.assertInvariante;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V22 (CC-094, CC-095, CC-061, CC-001): el recibo gana estado y auditoria de anulacion, el libro gana el motivo
 * (obligatorio en ANULACION y AJUSTE) y la imputacion gana la vigencia. Usa un MySQL de Testcontainers propio y una base nueva
 * por test, con V1 a V21 aplicadas; el test de filas previas siembra en V21 y migra a V22 despues.
 */
class MigracionV22Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final AtomicInteger CUITS = new AtomicInteger();
    private static final AtomicInteger NUMEROS = new AtomicInteger();

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion21() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v22", "21"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private MigrateResult migrarHasta(String version) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    // ---------------------------------------------------------------- siembra

    private String cuitNuevo() {
        return String.format("30-85%02d%04d-1", 0, CUITS.incrementAndGet());
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V22', 'DUENO')", id, "v22-" + cuit + "@test.local");
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values "
                + "(?, 'Efectivo', 'EFECTIVO', 1), (?, 'Débito', 'DEBITO', 2), (?, 'Crédito', 'CREDITO', 3), "
                + "(?, 'Transferencia', 'TRANSFERENCIA', 4)", id, id, id, id);
        return id;
    }

    private long usuario(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private long cliente(long ferreteria, String razonSocial) {
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", ferreteria, razonSocial);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                ferreteria, razonSocial);
    }

    private long comprobante(long ferreteria, int numero) {
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)", ferreteria,
                numero, "v22-" + ferreteria + "-" + numero, usuario(ferreteria));
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }

    private long pago(long ferreteria, long comprobante) {
        long forma = jdbc.queryForObject("select min(id) from forma_pago where ferreteria_id = ?", Long.class, ferreteria);
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                + "values (?, ?, 'EFECTIVO', 60.50, ?, 'Efectivo')", ferreteria, comprobante, forma);
        return jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, comprobante);
    }

    private long caja(long ferreteria) {
        jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, usuario_apertura_id, efectivo_inicial) "
                + "values (?, true, now(6), ?, 0)", ferreteria, usuario(ferreteria));
        return jdbc.queryForObject("select max(id) from caja where ferreteria_id = ?", Long.class, ferreteria);
    }

    /** Una ferreteria con 4 formas, un cliente, un comprobante con dos pagos y una caja: lo minimo para asentar. */
    private record Escenario(long ferreteria, long usuario, long cliente, long comprobante, long pago, long pago2,
            long caja) {
    }

    private Escenario escenario() {
        String cuit = cuitNuevo();
        long f = ferreteria(cuit);
        long cli = cliente(f, "Cliente " + cuit);
        long comp = comprobante(f, 1);
        return new Escenario(f, usuario(f), cli, comp, pago(f, comp), pago(f, comp), caja(f));
    }

    /** Recibo como lo siembra V21 (sin las columnas de V22): en V22 queda EMITIDO por default. */
    private long recibo(Escenario e, String clave, String medio, String total) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), ?, ?)", e.ferreteria(),
                numero, clave, e.cliente(), e.caja(), e.usuario(), medio, new BigDecimal(total));
        return jdbc.queryForObject("select id from recibo where ferreteria_id = ? and numero = ?", Long.class,
                e.ferreteria(), numero);
    }

    /** Recibo de V22 con estado y las cuatro columnas de auditoria explicitas (null = NULL). */
    private void reciboAuditado(Escenario e, String estado, Object fecha, Object usuario, Object caja, String motivo) {
        int numero = NUMEROS.incrementAndGet();
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total, estado, fecha_anulacion, usuario_anulacion_id, caja_anulacion_id, "
                + "motivo_anulacion) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), 'EFECTIVO', 100, ?, ?, ?, ?, ?)",
                e.ferreteria(), numero, "aud-" + numero, e.cliente(), e.caja(), e.usuario(), estado, fecha, usuario,
                caja, motivo);
    }

    /** Un movimiento de V22 (lleva recibo_id, referencia y motivo). */
    private void mov(Escenario e, String tipo, String debe, String haber, Long comprobante, Long pago, Long recibo,
            String motivo) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                + "pago_id, fecha, usuario_id, recibo_id, referencia, motivo) values (?, ?, ?, ?, ?, ?, ?, now(6), ?, ?, "
                + "'ref', ?)", e.ferreteria(), e.cliente(), tipo, new BigDecimal(debe), new BigDecimal(haber), comprobante,
                pago, e.usuario(), recibo, motivo);
    }

    /** Un movimiento tal como lo siembra V21 (sin motivo). */
    private void mov21(long f, long usuario, long cliente, String tipo, String debe, String haber, Long comprobante,
            Long pago, Long recibo, String referencia) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                + "pago_id, fecha, usuario_id, recibo_id, referencia) values (?, ?, ?, ?, ?, ?, ?, now(6), ?, ?, ?)", f,
                cliente, tipo, new BigDecimal(debe), new BigDecimal(haber), comprobante, pago, usuario, recibo, referencia);
    }

    private void imputacion(long f, long cliente, long recibo, long comprobante, String importe) {
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, ?)", f, cliente, recibo, comprobante, new BigDecimal(importe));
    }

    private String columna(String tabla, String columna) {
        Map<String, Object> c = jdbc.queryForMap(
                "select column_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        return c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE") + " " + c.get("COLUMN_DEFAULT");
    }

    private List<String> checksDe(String tabla) {
        return jdbc.queryForList("select cc.constraint_name from information_schema.check_constraints cc "
                + "join information_schema.table_constraints tc on tc.constraint_schema = cc.constraint_schema "
                + "and tc.constraint_name = cc.constraint_name where tc.table_schema = database() "
                + "and tc.table_name = ? and tc.constraint_type = 'CHECK' order by 1", String.class, tabla);
    }

    private List<String> restriccionesDe(String tabla, String tipo) {
        return jdbc.queryForList("select constraint_name from information_schema.table_constraints "
                + "where table_schema = database() and table_name = ? and constraint_type = ? order by 1", String.class,
                tabla, tipo);
    }

    private List<String> columnasDelIndice(String tabla, String indice) {
        return jdbc.queryForList("select column_name from information_schema.statistics where table_schema = database() "
                + "and table_name = ? and index_name = ? order by seq_in_index", String.class, tabla, indice);
    }

    // ---------------------------------------------------------------- filas existentes (CC-094)

    @Test
    @DisplayName("con filas existentes (CC-094): mismas filas y saldos, todo recibo EMITIDO con auditoria NULL, toda imputacion vigente, motivo NULL e invariantes intactas")
    void conFilasExistentes() {
        // ferreteria A: cliente1 con dos comprobantes y un recibo que imputa a los dos, cliente2 con un recibo que imputa
        // a uno, cliente3 con un adelanto (recibo sin imputaciones)
        long a = ferreteria("30-85000101-1");
        long usuarioA = usuario(a);
        long cajaA = caja(a);
        long cli1 = cliente(a, "Uno");
        long cli2 = cliente(a, "Dos");
        long cli3 = cliente(a, "Adelanto");
        long comp1 = comprobante(a, 7);
        long comp2 = comprobante(a, 8);
        long comp3 = comprobante(a, 9);
        mov21(a, usuarioA, cli1, "VENTA", "60.50", "0", comp1, pago(a, comp1), null, "Venta N.º 7");
        mov21(a, usuarioA, cli1, "VENTA", "60.50", "0", comp2, pago(a, comp2), null, "Venta N.º 8");
        mov21(a, usuarioA, cli2, "VENTA", "60.50", "0", comp3, pago(a, comp3), null, "Venta N.º 9");
        long r1 = reciboV21(a, usuarioA, cli1, cajaA, 1, "100.00");
        long r2 = reciboV21(a, usuarioA, cli2, cajaA, 2, "30.00");
        long r3 = reciboV21(a, usuarioA, cli3, cajaA, 3, "50.00");
        mov21(a, usuarioA, cli1, "COBRO", "0", "100.00", null, null, r1, "Recibo N.º 1");
        mov21(a, usuarioA, cli2, "COBRO", "0", "30.00", null, null, r2, "Recibo N.º 2");
        mov21(a, usuarioA, cli3, "COBRO", "0", "50.00", null, null, r3, "Recibo N.º 3");
        imputacion(a, cli1, r1, comp1, "60.50");
        imputacion(a, cli1, r1, comp2, "39.50");
        imputacion(a, cli2, r2, comp3, "30.00");
        jdbc.update("update cliente set saldo = 21.00 where id = ?", cli1);
        jdbc.update("update cliente set saldo = 30.50 where id = ?", cli2);
        jdbc.update("update cliente set saldo = -50.00 where id = ?", cli3);
        // ferreteria B: un cliente con un recibo imputado
        long b = ferreteria("30-85000102-1");
        long usuarioB = usuario(b);
        long cajaB = caja(b);
        long cliB = cliente(b, "De B");
        long compB = comprobante(b, 1);
        mov21(b, usuarioB, cliB, "VENTA", "60.50", "0", compB, pago(b, compB), null, "Venta N.º 1");
        long rB = reciboV21(b, usuarioB, cliB, cajaB, 1, "10.00");
        mov21(b, usuarioB, cliB, "COBRO", "0", "10.00", null, null, rB, "Recibo N.º 1");
        imputacion(b, cliB, rB, compB, "10.00");
        jdbc.update("update cliente set saldo = 50.50 where id = ?", cliB);

        String columnasDelLibro = "id, ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, pago_id, fecha, "
                + "usuario_id, excedio_limite, autorizado_por, recibo_id, referencia";
        String columnasDelRecibo = "id, ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total";
        List<Map<String, Object>> libroAntes = jdbc.queryForList(
                "select " + columnasDelLibro + " from movimiento_cuenta_cliente order by id");
        List<Map<String, Object>> recibosAntes = jdbc.queryForList("select " + columnasDelRecibo + " from recibo order by id");
        List<Map<String, Object>> imputacionesAntes = jdbc.queryForList(
                "select id, ferreteria_id, cliente_id, recibo_id, comprobante_id, importe from imputacion order by id");
        List<Map<String, Object>> saldosAntes = jdbc.queryForList("select id, saldo from cliente order by id");

        migrarHasta("22");

        assertThat(libroAntes).hasSize(8); // 4 VENTA + 4 COBRO
        assertThat(recibosAntes).hasSize(4);
        assertThat(imputacionesAntes).hasSize(4);
        assertThat(jdbc.queryForList("select " + columnasDelLibro + " from movimiento_cuenta_cliente order by id"))
                .isEqualTo(libroAntes);
        assertThat(jdbc.queryForList("select " + columnasDelRecibo + " from recibo order by id")).isEqualTo(recibosAntes);
        assertThat(jdbc.queryForList(
                "select id, ferreteria_id, cliente_id, recibo_id, comprobante_id, importe from imputacion order by id"))
                .isEqualTo(imputacionesAntes);
        assertThat(jdbc.queryForList("select id, saldo from cliente order by id")).isEqualTo(saldosAntes);
        // todo recibo previo es EMITIDO y sin auditoria (incluido el adelanto)
        assertThat(jdbc.queryForList("select estado from recibo", String.class)).hasSize(4).containsOnly("EMITIDO");
        assertThat(jdbc.queryForObject("select count(*) from recibo where fecha_anulacion is not null "
                + "or usuario_anulacion_id is not null or caja_anulacion_id is not null or motivo_anulacion is not null",
                Integer.class)).isZero();
        // toda imputacion previa es vigente; el libro previo no tiene motivo
        assertThat(jdbc.queryForList("select vigente from imputacion", Integer.class)).hasSize(4).containsOnly(1);
        assertThat(jdbc.queryForList("select motivo from movimiento_cuenta_cliente", String.class)).hasSize(8)
                .containsOnlyNulls();
        for (long cli : new long[] {cli1, cli2, cli3, cliB}) {
            assertInvariante(jdbc, cli);
        }
    }

    private long reciboV21(long f, long usuario, long cliente, long caja, int numero, String total) {
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), 'EFECTIVO', ?)", f, numero,
                "pre-" + f + "-" + numero, cliente, caja, usuario, new BigDecimal(total));
        return jdbc.queryForObject("select id from recibo where ferreteria_id = ? and numero = ?", Long.class, f, numero);
    }

    // ---------------------------------------------------------------- esquema

    @Test
    @DisplayName("esquema: tipos, nulabilidad y defaults de las columnas nuevas de recibo, libro e imputacion")
    void columnas() {
        migrarHasta("22");

        assertThat(columna("recibo", "estado")).isEqualTo("varchar(10) NO EMITIDO");
        assertThat(columna("recibo", "fecha_anulacion")).isEqualTo("datetime(6) YES null");
        assertThat(columna("recibo", "usuario_anulacion_id")).isEqualTo("bigint YES null");
        assertThat(columna("recibo", "caja_anulacion_id")).isEqualTo("bigint YES null");
        assertThat(columna("recibo", "motivo_anulacion")).isEqualTo("varchar(200) YES null");
        assertThat(columna("movimiento_cuenta_cliente", "motivo")).isEqualTo("varchar(200) YES null");
        assertThat(columna("imputacion", "vigente")).isEqualTo("tinyint(1) NO 1");
    }

    @Test
    @DisplayName("esquema: FK de usuario y caja de anulacion, CHECKs nuevos, indice por caja de anulacion y los CHECK/UNIQUE de V21 intactos")
    void restricciones() {
        migrarHasta("22");

        assertThat(restriccionesDe("recibo", "FOREIGN KEY")).contains("fk_recibo_usuario_anulacion",
                "fk_recibo_caja_anulacion");
        assertThat(checksDe("recibo")).contains("ck_recibo_estado", "ck_recibo_anulacion", "ck_recibo_total",
                "ck_recibo_medio");
        assertThat(checksDe("movimiento_cuenta_cliente")).contains("ck_mcc_motivo", "ck_mcc_tipo_v21", "ck_mcc_sentido_v21",
                "ck_mcc_recibo_ref", "ck_mcc_importes", "ck_mcc_venta_ref", "ck_mcc_exceso");
        assertThat(columnasDelIndice("recibo", "ix_recibo_caja_anulacion")).containsExactly("ferreteria_id",
                "caja_anulacion_id");
        assertThat(columnasDelIndice("movimiento_cuenta_cliente", "uk_mcc_recibo_tipo")).containsExactly("recibo_id", "tipo");
        assertThat(restriccionesDe("movimiento_cuenta_cliente", "UNIQUE")).contains("uk_mcc_recibo_tipo");
    }

    // ---------------------------------------------------------------- recibo: rechazos (CC-095)

    @Test
    @DisplayName("recibo: un estado fuera de EMITIDO/ANULADO se rechaza")
    void estadoFueraDeDominio() {
        migrarHasta("22");
        Escenario e = escenario();

        // un estado desconocido rompe a la vez ck_recibo_estado y ck_recibo_anulacion: MySQL nombra cualquiera de los dos
        assertThatThrownBy(() -> reciboAuditado(e, "PENDIENTE", null, null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint 'ck_recibo_");
        assertThatThrownBy(() -> reciboAuditado(e, "ANULADA", LocalDateTime.now(), e.usuario(), e.caja(), "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint 'ck_recibo_");
    }

    @ParameterizedTest(name = "ANULADO sin {0} se rechaza")
    @ValueSource(strings = {"fecha_anulacion", "usuario_anulacion_id", "caja_anulacion_id", "motivo_anulacion"})
    void anuladoConUnaColumnaNula(String faltante) {
        migrarHasta("22");
        Escenario e = escenario();
        Object fecha = faltante.equals("fecha_anulacion") ? null : LocalDateTime.now();
        Object usuario = faltante.equals("usuario_anulacion_id") ? null : e.usuario();
        Object caja = faltante.equals("caja_anulacion_id") ? null : e.caja();
        String motivo = faltante.equals("motivo_anulacion") ? null : "Cobro mal cargado";

        assertThatThrownBy(() -> reciboAuditado(e, "ANULADO", fecha, usuario, caja, motivo))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_anulacion");
    }

    @ParameterizedTest(name = "EMITIDO con {0} presente se rechaza")
    @ValueSource(strings = {"fecha_anulacion", "usuario_anulacion_id", "caja_anulacion_id", "motivo_anulacion"})
    void emitidoConUnaColumnaPresente(String presente) {
        migrarHasta("22");
        Escenario e = escenario();
        Object fecha = presente.equals("fecha_anulacion") ? LocalDateTime.now() : null;
        Object usuario = presente.equals("usuario_anulacion_id") ? e.usuario() : null;
        Object caja = presente.equals("caja_anulacion_id") ? e.caja() : null;
        String motivo = presente.equals("motivo_anulacion") ? "Cobro mal cargado" : null;

        assertThatThrownBy(() -> reciboAuditado(e, "EMITIDO", fecha, usuario, caja, motivo))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_anulacion");
    }

    @Test
    @DisplayName("recibo: ANULADO con un motivo de 4 caracteres (o de solo espacios) se rechaza; con 5 entra; de 201 no entra")
    void motivoDelRecibo() {
        migrarHasta("22");
        Escenario e = escenario();

        assertThatThrownBy(() -> reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), e.caja(), "abcd"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_anulacion");
        assertThatThrownBy(() -> reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), e.caja(), "      "))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_anulacion");
        assertThatThrownBy(() -> reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), e.caja(), "x".repeat(201)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Data too long");
        reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), e.caja(), "12345");
        reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), e.caja(), "x".repeat(200));

        assertThat(jdbc.queryForObject("select count(*) from recibo where estado = 'ANULADO'", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("recibo: las FK de usuario y caja de anulacion exigen filas reales")
    void clavesForaneasDeLaAnulacion() {
        migrarHasta("22");
        Escenario e = escenario();

        assertThatThrownBy(() -> reciboAuditado(e, "ANULADO", LocalDateTime.now(), 999999L, e.caja(), "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recibo_usuario_anulacion");
        assertThatThrownBy(() -> reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), 999999L, "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recibo_caja_anulacion");
    }

    // ---------------------------------------------------------------- libro: rechazos (CC-095, CC-061)

    @Test
    @DisplayName("libro: una ANULACION o un AJUSTE sin motivo se rechazan; con motivo entran")
    void motivoObligatorioEnAnulacionYAjuste() {
        migrarHasta("22");
        Escenario e = escenario();
        long r = recibo(e, "m-1", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, r, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
        assertThatThrownBy(() -> mov(e, "AJUSTE", "10.00", "0", null, null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
        mov(e, "ANULACION", "100.00", "0", null, null, r, "Cobro mal cargado");
        mov(e, "AJUSTE", "10.00", "0", null, null, null, "Saldo inicial de test");

        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("libro: VENTA, COBRO y NC con motivo se rechazan")
    void motivoProhibidoEnLosDemasTipos() {
        migrarHasta("22");
        Escenario e = escenario();
        long r = recibo(e, "m-2", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "VENTA", "60.50", "0", e.comprobante(), e.pago(), null, "Motivo de más"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
        assertThatThrownBy(() -> mov(e, "COBRO", "0", "100.00", null, null, r, "Motivo de más"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
        assertThatThrownBy(() -> mov(e, "NC", "0", "10.00", null, null, null, "Motivo de más"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
    }

    @Test
    @DisplayName("libro: un motivo de 4 caracteres o de solo espacios se rechaza; uno de 201 no entra")
    void motivoDelLibro() {
        migrarHasta("22");
        Escenario e = escenario();
        long r = recibo(e, "m-3", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, r, "abcd"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
        assertThatThrownBy(() -> mov(e, "AJUSTE", "10.00", "0", null, null, null, "      "))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_motivo");
        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, r, "x".repeat(201)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Data too long");
        mov(e, "ANULACION", "100.00", "0", null, null, r, "x".repeat(200));

        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("libro: una ANULACION sin recibo, con haber, sin debe o con comprobante/pago se rechaza (CHECK de V21 vigentes)")
    void anulacionConFormaInvalida() {
        migrarHasta("22");
        Escenario e = escenario();
        long r = recibo(e, "a-1", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, null, "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "ANULACION", "0", "100.00", null, null, r, "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_sentido_v21");
        assertThatThrownBy(() -> mov(e, "ANULACION", "0", "0", null, null, r, "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_importes");
        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", e.comprobante(), null, r, "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, e.pago(), r, "Cobro mal cargado"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        mov(e, "ANULACION", "100.00", "0", null, null, r, "Cobro mal cargado"); // la bien formada entra: los rechazos son por forma
    }

    @Test
    @DisplayName("libro: la segunda ANULACION del mismo recibo choca con uk_mcc_recibo_tipo (respaldo de unicidad, CC-061)")
    void segundaAnulacionDelMismoRecibo() {
        migrarHasta("22");
        Escenario e = escenario();
        long r = recibo(e, "u-1", "EFECTIVO", "100.00");
        long r2 = recibo(e, "u-2", "EFECTIVO", "50.00");

        mov(e, "ANULACION", "100.00", "0", null, null, r, "Cobro mal cargado");

        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, r, "Otra vez lo mismo"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_mcc_recibo_tipo");
        mov(e, "ANULACION", "50.00", "0", null, null, r2, "Cobro de otro recibo"); // otro recibo sí
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where tipo = 'ANULACION'",
                Integer.class)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- imputacion

    @Test
    @DisplayName("imputacion: una fila nueva queda vigente por default; vigente NULL se rechaza; se puede marcar no vigente")
    void vigencia() {
        migrarHasta("22");
        Escenario e = escenario();
        long r = recibo(e, "v-1", "EFECTIVO", "100.00");
        imputacion(e.ferreteria(), e.cliente(), r, e.comprobante(), "40.00");

        assertThat(jdbc.queryForObject("select vigente from imputacion", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, "
                + "importe, vigente) values (?, ?, ?, ?, 10, null)", e.ferreteria(), e.cliente(), r,
                comprobante(e.ferreteria(), 2))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("vigente");
        jdbc.update("update imputacion set vigente = false");
        assertThat(jdbc.queryForObject("select vigente from imputacion", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- aceptaciones

    @Test
    @DisplayName("aceptaciones: un recibo sin auditoria entra EMITIDO por default; ANULADO completo entra; COBRO y VENTA nuevos entran como en V21")
    void aceptaciones() {
        migrarHasta("22");
        Escenario e = escenario();

        long emitido = recibo(e, "ok-1", "EFECTIVO", "100.00");
        reciboAuditado(e, "ANULADO", LocalDateTime.now(), e.usuario(), e.caja(), "Cobro mal cargado");
        mov(e, "COBRO", "0", "100.00", null, null, emitido, null);
        mov(e, "VENTA", "60.50", "0", e.comprobante(), e.pago(), null, null);
        mov(e, "VENTA", "60.50", "0", e.comprobante(), e.pago2(), null, null);

        assertThat(jdbc.queryForObject("select estado from recibo where id = ?", String.class, emitido))
                .isEqualTo("EMITIDO");
        assertThat(jdbc.queryForList("select estado from recibo order by id", String.class)).containsExactly("EMITIDO",
                "ANULADO");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("reaplicar V22 sobre una base ya migrada no ejecuta ninguna migracion")
    void reaplicarNoHaceNada() {
        MigrateResult primera = migrarHasta("22");
        MigrateResult segunda = migrarHasta("22");

        assertThat(primera.migrationsExecuted).isEqualTo(1);
        assertThat(segunda.migrationsExecuted).isZero();
    }
}
