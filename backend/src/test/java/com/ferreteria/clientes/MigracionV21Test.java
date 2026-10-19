package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V21 (CC-052, CC-001, CC-002, CC-026, CC-036): el libro admite COBRO con recibo y la referencia copiada (con el
 * contra-asiento ANULACION previsto para 3b), nacen recibo, numerador_recibo e imputacion, y la referencia de las ventas
 * existentes se rellena. Usa un MySQL de Testcontainers propio y una base nueva por test, con V1 a V20 aplicadas; los
 * tests que necesitan datos previos los siembran en V20 y migran a V21 despues.
 */
class MigracionV21Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final AtomicInteger CUITS = new AtomicInteger();

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
    void baseNuevaEnLaVersion20() throws SQLException {
        String base = "v21_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        migrarHasta("20");
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    // ---------------------------------------------------------------- siembra (sirve en V20 y en V21)

    private String cuitNuevo() {
        return String.format("30-84%02d%04d-1", 0, CUITS.incrementAndGet());
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V21', 'DUENO')", id, "v21-" + cuit + "@test.local");
        return id;
    }

    private long usuario(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private void formasDeSiempre(long ferreteria) {
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values "
                + "(?, 'Efectivo', 'EFECTIVO', 1), (?, 'Débito', 'DEBITO', 2), (?, 'Crédito', 'CREDITO', 3), "
                + "(?, 'Transferencia', 'TRANSFERENCIA', 4)", ferreteria, ferreteria, ferreteria, ferreteria);
    }

    private long cliente(long ferreteria, String razonSocial) {
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", ferreteria, razonSocial);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                ferreteria, razonSocial);
    }

    private long comprobante(long ferreteria, int numero) {
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)", ferreteria,
                numero, "v21-" + ferreteria + "-" + numero, usuario(ferreteria));
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

    /** Escenario ya en V21 (con caja). */
    private Escenario escenario21() {
        String cuit = cuitNuevo();
        long f = ferreteria(cuit);
        formasDeSiempre(f);
        long cli = cliente(f, "Cliente " + cuit);
        long comp = comprobante(f, 1);
        return new Escenario(f, usuario(f), cli, comp, pago(f, comp), pago(f, comp), caja(f));
    }

    /** Un movimiento de V21 (lleva referencia y recibo_id). */
    private void mov(Escenario e, String tipo, String debe, String haber, Long comprobante, Long pago, Long recibo) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                + "pago_id, fecha, usuario_id, recibo_id, referencia) values (?, ?, ?, ?, ?, ?, ?, now(6), ?, ?, 'ref')",
                e.ferreteria(), e.cliente(), tipo, new BigDecimal(debe), new BigDecimal(haber), comprobante, pago,
                e.usuario(), recibo);
    }

    private long recibo(Escenario e, int numero, String clave, String medio, String total) {
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), ?, ?)", e.ferreteria(),
                numero, clave, e.cliente(), e.caja(), e.usuario(), medio, new BigDecimal(total));
        return jdbc.queryForObject("select id from recibo where ferreteria_id = ? and numero = ?", Long.class,
                e.ferreteria(), numero);
    }

    private void imputacion(Escenario e, long recibo, long comprobante, String importe) {
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, ?)", e.ferreteria(), e.cliente(), recibo, comprobante, new BigDecimal(importe));
    }

    private String columna(String tabla, String columna) {
        Map<String, Object> c = jdbc.queryForMap(
                "select column_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        return c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE") + " " + c.get("COLUMN_DEFAULT");
    }

    private List<String> checksDelLibro() {
        return jdbc.queryForList("select cc.constraint_name from information_schema.check_constraints cc "
                + "join information_schema.table_constraints tc on tc.constraint_schema = cc.constraint_schema "
                + "and tc.constraint_name = cc.constraint_name where tc.table_schema = database() "
                + "and tc.table_name = 'movimiento_cuenta_cliente' and tc.constraint_type = 'CHECK' order by 1",
                String.class);
    }

    private String sqlDeV21() throws IOException {
        String crudo = new String(new ClassPathResource("db/migration/V21__cobranza_cuenta_corriente.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Arrays.stream(crudo.split("\n")).filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
    }

    // ---------------------------------------------------------------- filas existentes (el requisito del proposal)

    @Test
    @DisplayName("con filas existentes: mismas filas y saldos, I1 intacta, referencia rellenada «Venta N.º n» y NOT NULL")
    void conFilasExistentes() {
        // ferreteria A: cliente 1 con un comprobante con DOS pagos a cuenta corriente, cliente 2 con otro comprobante
        long a = ferreteria("30-84000101-1");
        formasDeSiempre(a);
        long usuarioA = usuario(a);
        long cli1 = cliente(a, "Uno");
        long cli2 = cliente(a, "Dos");
        long comp1 = comprobante(a, 7);
        long comp2 = comprobante(a, 8);
        long p1 = pago(a, comp1);
        long p2 = pago(a, comp1); // dos pagos CC del mismo comprobante
        long p3 = pago(a, comp2);
        // ferreteria B: sin ningun movimiento
        long b = ferreteria("30-84000102-1");
        long cliB = cliente(b, "Sin movimientos");
        asentarV20(a, usuarioA, cli1, comp1, p1, "60.50");
        asentarV20(a, usuarioA, cli1, comp1, p2, "60.50");
        asentarV20(a, usuarioA, cli2, comp2, p3, "60.50");
        jdbc.update("update cliente set saldo = 121.00 where id = ?", cli1);
        jdbc.update("update cliente set saldo = 60.50 where id = ?", cli2);

        String columnasViejas = "id, ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, pago_id, fecha, "
                + "usuario_id, excedio_limite, autorizado_por";
        List<Map<String, Object>> libroAntes = jdbc.queryForList(
                "select " + columnasViejas + " from movimiento_cuenta_cliente order by id");
        List<Map<String, Object>> saldosAntes = jdbc.queryForList("select id, saldo from cliente order by id");

        migrarHasta("21");

        assertThat(libroAntes).hasSize(3);
        assertThat(jdbc.queryForList("select " + columnasViejas + " from movimiento_cuenta_cliente order by id"))
                .isEqualTo(libroAntes);
        assertThat(jdbc.queryForList("select id, saldo from cliente order by id")).isEqualTo(saldosAntes);
        assertThat(jdbc.queryForList("select cliente_id from movimiento_cuenta_cliente where ferreteria_id = ?",
                Long.class, b)).isEmpty();
        // I1: saldo = Σ(debe - haber) por cliente
        for (long cli : new long[] {cli1, cli2, cliB}) {
            BigDecimal saldo = jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, cli);
            BigDecimal libro = jdbc.queryForObject("select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente "
                    + "where cliente_id = ?", BigDecimal.class, cli);
            assertThat(saldo).isEqualByComparingTo(libro);
        }
        // backfill: cada VENTA copia el numero de su comprobante, incluidos los dos pagos del mismo comprobante
        assertThat(jdbc.queryForList("select referencia from movimiento_cuenta_cliente order by id", String.class))
                .containsExactly("Venta N.º 7", "Venta N.º 7", "Venta N.º 8");
        assertThat(jdbc.queryForList("select recibo_id from movimiento_cuenta_cliente", Long.class))
                .containsOnlyNulls();
        // NOT NULL al final
        assertThat(columna("movimiento_cuenta_cliente", "referencia")).isEqualTo("varchar(60) NO null");
        assertThatThrownBy(() -> jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, "
                + "debe, haber, fecha, usuario_id) values (?, ?, 'AJUSTE', 10, 0, now(6), ?)", a, cli2, usuarioA))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, "
                + "debe, haber, fecha, usuario_id, referencia) values (?, ?, 'AJUSTE', 10, 0, now(6), ?, null)",
                a, cli2, usuarioA)).isInstanceOf(DataAccessException.class);
    }

    private void asentarV20(long f, long usuario, long cliente, long comprobante, long pago, String monto) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                + "pago_id, fecha, usuario_id) values (?, ?, 'VENTA', ?, 0, ?, ?, now(6), ?)", f, cliente,
                new BigDecimal(monto), comprobante, pago, usuario);
    }

    // ---------------------------------------------------------------- CHECKs: DROP + ADD en UNA sola ALTER

    @Test
    @DisplayName("el DROP+ADD de los CHECK aplica sobre una base con datos y deja los nombres _v21 (los viejos de tipo y sentido, fuera)")
    void checksReemplazadosConDatos() {
        long f = ferreteria("30-84000103-1");
        formasDeSiempre(f);
        long cli = cliente(f, "Con datos");
        long comp = comprobante(f, 1);
        asentarV20(f, usuario(f), cli, comp, pago(f, comp), "60.50");

        migrarHasta("21");

        assertThat(checksDelLibro()).doesNotContain("ck_mcc_tipo", "ck_mcc_sentido")
                .contains("ck_mcc_tipo_v21", "ck_mcc_sentido_v21", "ck_mcc_recibo_ref")
                .contains("ck_mcc_importes", "ck_mcc_venta_ref", "ck_mcc_exceso");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema = "
                + "database() and table_name = 'movimiento_cuenta_cliente' and constraint_name = 'uk_mcc_pago_tipo'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("contrato del texto: UNA sentencia ALTER del libro lleva a la vez DROP CHECK ck_mcc_tipo, DROP CHECK ck_mcc_sentido y ADD CONSTRAINT ck_mcc_tipo_v21")
    void unaSolaAlterParaLosChecks() throws IOException {
        String sql = sqlDeV21();

        List<String> conLosTres = Arrays.stream(sql.split(";"))
                .filter(s -> Pattern.compile("ALTER\\s+TABLE\\s+movimiento_cuenta_cliente", Pattern.CASE_INSENSITIVE)
                        .matcher(s).find())
                .filter(s -> Pattern.compile("DROP\\s+CHECK\\s+ck_mcc_tipo\\b", Pattern.CASE_INSENSITIVE).matcher(s).find()
                        && Pattern.compile("DROP\\s+CHECK\\s+ck_mcc_sentido\\b", Pattern.CASE_INSENSITIVE).matcher(s)
                                .find()
                        && Pattern.compile("ADD\\s+CONSTRAINT\\s+ck_mcc_tipo_v21\\b", Pattern.CASE_INSENSITIVE)
                                .matcher(s).find())
                .toList();

        assertThat(conLosTres).hasSize(1);
    }

    // ---------------------------------------------------------------- tablas y columnas

    @Test
    @DisplayName("recibo, numerador_recibo, imputacion y las columnas nuevas del libro tienen los tipos y la nulabilidad del design")
    void columnas() {
        migrarHasta("21");

        assertThat(columna("recibo", "id")).isEqualTo("bigint NO null");
        assertThat(columna("recibo", "ferreteria_id")).isEqualTo("bigint NO null");
        assertThat(columna("recibo", "numero")).isEqualTo("bigint NO null");
        assertThat(columna("recibo", "clave_idempotencia")).isEqualTo("varchar(36) NO null");
        assertThat(columna("recibo", "cliente_id")).isEqualTo("bigint NO null");
        assertThat(columna("recibo", "cliente_nombre")).isEqualTo("varchar(120) NO null");
        assertThat(columna("recibo", "caja_id")).isEqualTo("bigint NO null");
        assertThat(columna("recibo", "usuario_id")).isEqualTo("bigint NO null");
        assertThat(columna("recibo", "fecha")).isEqualTo("datetime(6) NO null");
        assertThat(columna("recibo", "medio")).isEqualTo("varchar(20) NO null");
        assertThat(columna("recibo", "total")).isEqualTo("decimal(15,2) NO null");

        assertThat(columna("numerador_recibo", "id")).isEqualTo("bigint NO null");
        assertThat(columna("numerador_recibo", "ferreteria_id")).isEqualTo("bigint NO null");
        assertThat(columna("numerador_recibo", "ultimo_numero")).isEqualTo("bigint NO null");

        assertThat(columna("imputacion", "id")).isEqualTo("bigint NO null");
        assertThat(columna("imputacion", "ferreteria_id")).isEqualTo("bigint NO null");
        assertThat(columna("imputacion", "cliente_id")).isEqualTo("bigint NO null");
        assertThat(columna("imputacion", "recibo_id")).isEqualTo("bigint NO null");
        assertThat(columna("imputacion", "comprobante_id")).isEqualTo("bigint NO null");
        assertThat(columna("imputacion", "importe")).isEqualTo("decimal(15,2) NO null");

        assertThat(columna("movimiento_cuenta_cliente", "recibo_id")).isEqualTo("bigint YES null");
        assertThat(columna("movimiento_cuenta_cliente", "referencia")).isEqualTo("varchar(60) NO null");
    }

    @Test
    @DisplayName("los indices de concurrencia ya estan en V21: partidas por cliente, recibo por cliente y caja, imputacion por cliente y comprobante")
    void indices() {
        migrarHasta("21");

        assertThat(columnasDelIndice("recibo", "ix_recibo_cliente")).containsExactly("ferreteria_id", "cliente_id", "id");
        assertThat(columnasDelIndice("recibo", "ix_recibo_caja")).containsExactly("ferreteria_id", "caja_id");
        assertThat(columnasDelIndice("imputacion", "ix_imputacion_cliente"))
                .containsExactly("ferreteria_id", "cliente_id", "comprobante_id");
        assertThat(columnasDelIndice("movimiento_cuenta_cliente", "uk_mcc_recibo_tipo"))
                .containsExactly("recibo_id", "tipo");
        assertThat(columnasDelIndice("movimiento_cuenta_cliente", "ix_mcc_cliente"))
                .containsExactly("ferreteria_id", "cliente_id", "id");
        assertThat(columnasDelIndice("movimiento_cuenta_cliente", "ix_mcc_comprobante")).containsExactly("comprobante_id");
    }

    private List<String> columnasDelIndice(String tabla, String indice) {
        return jdbc.queryForList("select column_name from information_schema.statistics where table_schema = database() "
                + "and table_name = ? and index_name = ? order by seq_in_index", String.class, tabla, indice);
    }

    // ---------------------------------------------------------------- libro: rechazos y aceptados

    @Test
    @DisplayName("aceptados: COBRO con recibo y haber, ANULACION con recibo y debe, y los CHECK de VENTA de V20 siguen vigentes")
    void libroAceptados() {
        migrarHasta("21");
        Escenario e = escenario21();
        long r = recibo(e, 1, "c-1", "EFECTIVO", "100.00");

        mov(e, "COBRO", "0", "100.00", null, null, r);
        mov(e, "ANULACION", "100.00", "0", null, null, r); // un COBRO y una ANULACION del mismo recibo sí entran
        mov(e, "VENTA", "60.50", "0", e.comprobante(), e.pago(), null);
        mov(e, "VENTA", "60.50", "0", e.comprobante(), e.pago2(), null); // dos pagos del mismo comprobante
        mov(e, "AJUSTE", "10.00", "0", null, null, null);
        mov(e, "NC", "0", "10.00", null, null, null);
        mov(e, "NC", "0", "5.00", null, null, null); // NULL en recibo_id no choca

        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(7);
        // regresión de V20: la VENTA sigue exigiendo comprobante y pago
        assertThatThrownBy(() -> mov(e, "VENTA", "10.00", "0", e.comprobante(), null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_venta_ref");
        assertThatThrownBy(() -> mov(e, "VENTA", "10.00", "0", null, e.pago(), null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_venta_ref");
    }

    @Test
    @DisplayName("un COBRO sin recibo_id se rechaza; una VENTA, un AJUSTE o una NC con recibo_id también")
    void reciboIdPorTipo() {
        migrarHasta("21");
        Escenario e = escenario21();
        long r = recibo(e, 1, "c-2", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "COBRO", "0", "100.00", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "VENTA", "60.50", "0", e.comprobante(), e.pago(), r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "AJUSTE", "10.00", "0", null, null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "NC", "0", "10.00", null, null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
    }

    @Test
    @DisplayName("un COBRO o una ANULACION no llevan comprobante_id ni pago_id")
    void cobroSinComprobanteNiPago() {
        migrarHasta("21");
        Escenario e = escenario21();
        long r = recibo(e, 1, "c-3", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "COBRO", "0", "100.00", e.comprobante(), null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "COBRO", "0", "100.00", null, e.pago(), r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", e.comprobante(), null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_recibo_ref");
    }

    @Test
    @DisplayName("un COBRO no puede tener debe y una ANULACION no puede tener haber (sentido)")
    void sentidoCobroYAnulacion() {
        migrarHasta("21");
        Escenario e = escenario21();
        long r = recibo(e, 1, "c-4", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> mov(e, "COBRO", "100.00", "0", null, null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_sentido_v21");
        assertThatThrownBy(() -> mov(e, "ANULACION", "0", "100.00", null, null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_sentido_v21");
    }

    @Test
    @DisplayName("el tipo tiene que ser VENTA, COBRO, AJUSTE, NC o ANULACION")
    void tipoInvalido() {
        migrarHasta("21");
        Escenario e = escenario21();

        // MySQL no garantiza cual de los CHECK que fallan nombra (ck_mcc_tipo_v21 y ck_mcc_sentido_v21 rechazan un tipo desconocido)
        assertThatThrownBy(() -> mov(e, "OTRO", "10.00", "0", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("UNIQUE (recibo_id, tipo): dos COBRO del mismo recibo se rechazan; un COBRO y una ANULACION entran")
    void unCobroPorRecibo() {
        migrarHasta("21");
        Escenario e = escenario21();
        long r = recibo(e, 1, "c-5", "EFECTIVO", "100.00");
        long r2 = recibo(e, 2, "c-6", "EFECTIVO", "50.00");

        mov(e, "COBRO", "0", "100.00", null, null, r);
        assertThatThrownBy(() -> mov(e, "COBRO", "0", "100.00", null, null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_mcc_recibo_tipo");
        mov(e, "ANULACION", "100.00", "0", null, null, r);
        assertThatThrownBy(() -> mov(e, "ANULACION", "100.00", "0", null, null, r))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_mcc_recibo_tipo");
        mov(e, "COBRO", "0", "50.00", null, null, r2); // otro recibo sí
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where recibo_id is not null",
                Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("fk_mcc_recibo: el recibo_id del libro tiene que existir")
    void libroReferenciaReciboReal() {
        migrarHasta("21");
        Escenario e = escenario21();

        assertThatThrownBy(() -> mov(e, "COBRO", "0", "100.00", null, null, 999999L))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_mcc_recibo");
    }

    // ---------------------------------------------------------------- recibo

    @Test
    @DisplayName("recibo: UNIQUE (ferreteria, numero) y (ferreteria, clave); la misma clave y el mismo numero en OTRA ferreteria entran")
    void reciboUnicos() {
        migrarHasta("21");
        Escenario e = escenario21();
        Escenario otra = escenario21();

        recibo(e, 1, "clave-a", "EFECTIVO", "100.00");
        assertThatThrownBy(() -> recibo(e, 1, "clave-b", "EFECTIVO", "100.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_recibo_numero");
        assertThatThrownBy(() -> recibo(e, 2, "clave-a", "EFECTIVO", "100.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_recibo_clave");
        recibo(otra, 1, "clave-a", "TRANSFERENCIA", "100.00");

        assertThat(jdbc.queryForObject("select count(*) from recibo", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("recibo: total <= 0 se rechaza y el medio solo puede ser EFECTIVO o TRANSFERENCIA (CUENTA_CORRIENTE, no)")
    void reciboTotalYMedio() {
        migrarHasta("21");
        Escenario e = escenario21();

        assertThatThrownBy(() -> recibo(e, 1, "t-1", "EFECTIVO", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_total");
        assertThatThrownBy(() -> recibo(e, 1, "t-2", "EFECTIVO", "-5.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_total");
        assertThatThrownBy(() -> recibo(e, 1, "t-3", "CUENTA_CORRIENTE", "10.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_medio");
        assertThatThrownBy(() -> recibo(e, 1, "t-4", "DEBITO", "10.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_recibo_medio");
        recibo(e, 1, "t-5", "EFECTIVO", "0.01");
        recibo(e, 2, "t-6", "TRANSFERENCIA", "10.00");
        assertThat(jdbc.queryForObject("select count(*) from recibo", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("recibo: referencia ferreteria, cliente, caja y usuario reales")
    void reciboClavesForaneas() {
        migrarHasta("21");
        Escenario e = escenario21();
        String insert = "insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total) values (?, 1, 'fk', ?, 'C', ?, ?, now(6), 'EFECTIVO', 10)";

        assertThatThrownBy(() -> jdbc.update(insert, 999999L, e.cliente(), e.caja(), e.usuario()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recibo_ferreteria");
        assertThatThrownBy(() -> jdbc.update(insert, e.ferreteria(), 999999L, e.caja(), e.usuario()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recibo_cliente");
        assertThatThrownBy(() -> jdbc.update(insert, e.ferreteria(), e.cliente(), 999999L, e.usuario()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recibo_caja");
        assertThatThrownBy(() -> jdbc.update(insert, e.ferreteria(), e.cliente(), e.caja(), 999999L))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recibo_usuario");
    }

    // ---------------------------------------------------------------- imputacion y numerador

    @Test
    @DisplayName("imputacion: importe <= 0 se rechaza; UNIQUE (recibo, comprobante); un recibo imputa a varios comprobantes")
    void imputacionImporteYUnicidad() {
        migrarHasta("21");
        Escenario e = escenario21();
        long comp2 = comprobante(e.ferreteria(), 2);
        long r = recibo(e, 1, "i-1", "EFECTIVO", "100.00");
        long r2 = recibo(e, 2, "i-2", "EFECTIVO", "100.00");

        assertThatThrownBy(() -> imputacion(e, r, e.comprobante(), "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_imputacion_importe");
        assertThatThrownBy(() -> imputacion(e, r, e.comprobante(), "-1.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_imputacion_importe");
        imputacion(e, r, e.comprobante(), "60.00");
        imputacion(e, r, comp2, "40.00");
        assertThatThrownBy(() -> imputacion(e, r, e.comprobante(), "1.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_imputacion_recibo_comprobante");
        imputacion(e, r2, e.comprobante(), "1.00"); // otro recibo al mismo comprobante sí

        assertThat(jdbc.queryForObject("select count(*) from imputacion", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("imputacion: referencia ferreteria, cliente, recibo y comprobante reales")
    void imputacionClavesForaneas() {
        migrarHasta("21");
        Escenario e = escenario21();
        long r = recibo(e, 1, "i-3", "EFECTIVO", "100.00");
        String insert = "insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, 10)";

        assertThatThrownBy(() -> jdbc.update(insert, 999999L, e.cliente(), r, e.comprobante()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_imputacion_ferreteria");
        assertThatThrownBy(() -> jdbc.update(insert, e.ferreteria(), 999999L, r, e.comprobante()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_imputacion_cliente");
        assertThatThrownBy(() -> jdbc.update(insert, e.ferreteria(), e.cliente(), 999999L, e.comprobante()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_imputacion_recibo");
        assertThatThrownBy(() -> jdbc.update(insert, e.ferreteria(), e.cliente(), r, 999999L))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_imputacion_comprobante");
    }

    @Test
    @DisplayName("numerador_recibo: uno por ferreteria, con FK a ferreteria")
    void numeradorUnicoPorFerreteria() {
        migrarHasta("21");
        long f = ferreteria(cuitNuevo());
        long g = ferreteria(cuitNuevo());

        jdbc.update("insert into numerador_recibo (ferreteria_id, ultimo_numero) values (?, 1)", f);
        assertThatThrownBy(() -> jdbc.update("insert into numerador_recibo (ferreteria_id, ultimo_numero) values (?, 5)", f))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_numerador_recibo_ferreteria");
        jdbc.update("insert into numerador_recibo (ferreteria_id, ultimo_numero) values (?, 1)", g);
        assertThatThrownBy(() -> jdbc.update("insert into numerador_recibo (ferreteria_id, ultimo_numero) values (999999, 1)"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_numerador_recibo_ferreteria");
    }
}
