package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V20 (CC-001, CC-002, CC-003, CC-022, CC-024): el limite y el saldo del cliente, el libro de cuenta corriente
 * y la forma «Cuenta corriente» sembrada inactiva. Usa un MySQL de Testcontainers propio y una base nueva por test, con
 * V1 a V19 aplicadas; cada test migra a V20 cuando le toca (la siembra necesita datos previos).
 */
class MigracionV20Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion19() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v20", "19"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V20', 'DUENO')", id, "v20-" + cuit + "@test.local");
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
                numero, "v20-" + ferreteria + "-" + numero, usuario(ferreteria));
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }

    private long pago(long ferreteria, long comprobante) {
        long forma = jdbc.queryForObject("select min(id) from forma_pago where ferreteria_id = ?", Long.class, ferreteria);
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                + "values (?, ?, 'EFECTIVO', 121, ?, 'Efectivo')", ferreteria, comprobante, forma);
        return jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, comprobante);
    }

    /** Una ferreteria con 4 formas, un cliente, un comprobante con su pago: lo minimo para asentar en el libro. */
    private record Escenario(long ferreteria, long usuario, long cliente, long comprobante, long pago) {
    }

    private Escenario escenario(String cuit) {
        long f = ferreteria(cuit);
        formasDeSiempre(f);
        long cli = cliente(f, "Cliente " + cuit);
        long comp = comprobante(f, 1);
        return new Escenario(f, usuario(f), cli, comp, pago(f, comp));
    }

    private void movimiento(Escenario e, String tipo, String debe, String haber, Long comprobante, Long pago,
            boolean excedio, Long autorizadoPor) {
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, "
                + "comprobante_id, pago_id, fecha, usuario_id, excedio_limite, autorizado_por) "
                + "values (?, ?, ?, ?, ?, ?, ?, now(6), ?, ?, ?)", e.ferreteria(), e.cliente(), tipo,
                new BigDecimal(debe), new BigDecimal(haber), comprobante, pago, e.usuario(), excedio, autorizadoPor);
    }

    private String columna(String tabla, String columna) {
        Map<String, Object> c = jdbc.queryForMap(
                "select column_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        return c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE") + " " + c.get("COLUMN_DEFAULT");
    }

    private List<Map<String, Object>> formas(long ferreteria) {
        return jdbc.queryForList("select * from forma_pago where ferreteria_id = ? order by orden, id", ferreteria);
    }

    // ---------------------------------------------------------------- columnas

    @Test
    @DisplayName("el cliente suma limite_cuenta_corriente (NULL) y saldo (NOT NULL, default 0)")
    void clienteConLimiteYSaldo() {
        migrarHasta("20");

        assertThat(columna("cliente", "limite_cuenta_corriente")).isEqualTo("decimal(15,2) YES null");
        assertThat(columna("cliente", "saldo")).isEqualTo("decimal(15,2) NO 0.00");
    }

    @Test
    @DisplayName("los clientes que ya existian quedan sin cuenta corriente habilitada y con saldo 0")
    void clientesPreviosSinCuentaCorriente() {
        long f = ferreteria("30-83010001-1");
        long previo = cliente(f, "Previo");

        migrarHasta("20");

        Map<String, Object> c = jdbc.queryForMap("select limite_cuenta_corriente, saldo from cliente where id = ?", previo);
        assertThat(c.get("limite_cuenta_corriente")).isNull();
        assertThat((BigDecimal) c.get("saldo")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el libro movimiento_cuenta_cliente tiene las columnas, tipos y nulabilidad del design")
    void columnasDelLibro() {
        migrarHasta("20");

        assertThat(columna("movimiento_cuenta_cliente", "id")).isEqualTo("bigint NO null");
        assertThat(columna("movimiento_cuenta_cliente", "ferreteria_id")).isEqualTo("bigint NO null");
        assertThat(columna("movimiento_cuenta_cliente", "cliente_id")).isEqualTo("bigint NO null");
        assertThat(columna("movimiento_cuenta_cliente", "tipo")).isEqualTo("varchar(10) NO null");
        assertThat(columna("movimiento_cuenta_cliente", "debe")).isEqualTo("decimal(15,2) NO 0.00");
        assertThat(columna("movimiento_cuenta_cliente", "haber")).isEqualTo("decimal(15,2) NO 0.00");
        assertThat(columna("movimiento_cuenta_cliente", "comprobante_id")).isEqualTo("bigint YES null");
        assertThat(columna("movimiento_cuenta_cliente", "pago_id")).isEqualTo("bigint YES null");
        assertThat(columna("movimiento_cuenta_cliente", "fecha")).isEqualTo("datetime(6) NO null");
        assertThat(columna("movimiento_cuenta_cliente", "usuario_id")).isEqualTo("bigint NO null");
        assertThat(columna("movimiento_cuenta_cliente", "excedio_limite")).isEqualTo("tinyint(1) NO 0");
        assertThat(columna("movimiento_cuenta_cliente", "autorizado_por")).isEqualTo("bigint YES null");
    }

    // ---------------------------------------------------------------- CHECKs

    @Test
    @DisplayName("un movimiento de venta bien formado se asienta (control de los rechazos de abajo)")
    void ventaBienFormada() {
        migrarHasta("20");
        Escenario e = escenario("30-83010002-1");

        movimiento(e, "VENTA", "100.00", "0", e.comprobante(), e.pago(), false, null);

        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("el limite del cliente no puede ser negativo, pero 0 y NULL sí")
    void limiteNegativo() {
        migrarHasta("20");
        long f = ferreteria("30-83010003-1");
        long c = cliente(f, "Ana");

        assertThatThrownBy(() -> jdbc.update("update cliente set limite_cuenta_corriente = -1 where id = ?", c))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_cliente_limite_cc");
        jdbc.update("update cliente set limite_cuenta_corriente = 0 where id = ?", c);
        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", BigDecimal.class, c))
                .isEqualByComparingTo("0");
        jdbc.update("update cliente set limite_cuenta_corriente = null where id = ?", c);
        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", BigDecimal.class, c))
                .isNull();
    }

    @Test
    @DisplayName("un movimiento no puede tener debe y haber a la vez")
    void debeYHaberALaVez() {
        migrarHasta("20");
        Escenario e = escenario("30-83010004-1");

        assertThatThrownBy(() -> movimiento(e, "AJUSTE", "10.00", "5.00", null, null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_importes");
    }

    @Test
    @DisplayName("un movimiento no puede tener debe y haber en cero, ni negativos")
    void ningunoDeLosDosLados() {
        migrarHasta("20");
        Escenario e = escenario("30-83010005-1");

        assertThatThrownBy(() -> movimiento(e, "AJUSTE", "0", "0", null, null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_importes");
        assertThatThrownBy(() -> movimiento(e, "AJUSTE", "-10.00", "0", null, null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_importes");
    }

    @Test
    @DisplayName("una venta sin pago_id o sin comprobante_id se rechaza")
    void ventaSinReferencias() {
        migrarHasta("20");
        Escenario e = escenario("30-83010006-1");

        assertThatThrownBy(() -> movimiento(e, "VENTA", "100.00", "0", e.comprobante(), null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_venta_ref");
        assertThatThrownBy(() -> movimiento(e, "VENTA", "100.00", "0", null, e.pago(), false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_venta_ref");
    }

    @Test
    @DisplayName("una venta no puede tener haber; un cobro o una NC no pueden tener debe")
    void sentidoPorTipo() {
        migrarHasta("20");
        Escenario e = escenario("30-83010007-1");

        assertThatThrownBy(() -> movimiento(e, "VENTA", "0", "100.00", e.comprobante(), e.pago(), false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_sentido");
        assertThatThrownBy(() -> movimiento(e, "COBRO", "100.00", "0", null, null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_sentido");
        assertThatThrownBy(() -> movimiento(e, "NC", "100.00", "0", null, null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_sentido");
        // y los sentidos buenos pasan: cobro y NC al haber, ajuste a cualquier lado
        movimiento(e, "COBRO", "0", "100.00", null, null, false, null);
        movimiento(e, "NC", "0", "50.00", null, null, false, null);
        movimiento(e, "AJUSTE", "10.00", "0", null, null, false, null);
        movimiento(e, "AJUSTE", "0", "10.00", null, null, false, null);
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("excedio_limite y autorizado_por van juntos: uno sin el otro se rechaza (valida el CHECK con igualdad en MySQL 8.4)")
    void excesoYAutorizacionVanJuntos() {
        migrarHasta("20");
        Escenario e = escenario("30-83010008-1");

        assertThatThrownBy(() -> movimiento(e, "COBRO", "0", "10.00", null, null, true, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_exceso");
        assertThatThrownBy(() -> movimiento(e, "COBRO", "0", "10.00", null, null, false, e.usuario()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_mcc_exceso");
        // los dos juntos y ninguno pasan
        movimiento(e, "VENTA", "100.00", "0", e.comprobante(), e.pago(), true, e.usuario());
        movimiento(e, "COBRO", "0", "10.00", null, null, false, null);
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where excedio_limite", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el tipo tiene que ser VENTA, COBRO, AJUSTE o NC")
    void tipoInvalido() {
        migrarHasta("20");
        Escenario e = escenario("30-83010009-1");

        // MySQL no garantiza cual de los CHECK que fallan nombra (ck_mcc_tipo y ck_mcc_sentido rechazan un tipo desconocido)
        assertThatThrownBy(() -> movimiento(e, "OTRO", "10.00", "0", null, null, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("UNIQUE (pago_id, tipo): un pago no se asienta dos veces, pero los NULL no chocan")
    void unPagoNoSeAsientaDosVeces() {
        migrarHasta("20");
        Escenario e = escenario("30-83010010-1");

        movimiento(e, "VENTA", "100.00", "0", e.comprobante(), e.pago(), false, null);
        assertThatThrownBy(() -> movimiento(e, "VENTA", "100.00", "0", e.comprobante(), e.pago(), false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_mcc_pago_tipo");

        // NULL en pago_id no choca: dos cobros sin pago conviven
        movimiento(e, "COBRO", "0", "10.00", null, null, false, null);
        movimiento(e, "COBRO", "0", "10.00", null, null, false, null);
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where tipo = 'COBRO'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("el libro referencia cliente, ferreteria, comprobante y pago reales")
    void clavesForaneas() {
        migrarHasta("20");
        Escenario e = escenario("30-83010011-1");

        assertThatThrownBy(() -> movimiento(e, "VENTA", "100.00", "0", 999999L, e.pago(), false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_mcc_comprobante");
        assertThatThrownBy(() -> movimiento(e, "VENTA", "100.00", "0", e.comprobante(), 999999L, false, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_mcc_pago");
        assertThatThrownBy(() -> jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, "
                + "haber, fecha, usuario_id) values (?, 999999, 'COBRO', 10, now(6), ?)", e.ferreteria(), e.usuario()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_mcc_cliente");
    }

    // ---------------------------------------------------------------- siembra

    @Test
    @DisplayName("una ferreteria con las 4 formas queda con 5: «Cuenta corriente» inactiva, recargo 0 y orden = max + 1")
    void siembraLaFormaInactiva() {
        long f = ferreteria("30-83010012-1");
        formasDeSiempre(f);

        migrarHasta("20");

        List<Map<String, Object>> formas = formas(f);
        assertThat(formas).hasSize(5);
        Map<String, Object> cc = formas.get(4);
        assertThat(cc.get("nombre")).isEqualTo("Cuenta corriente");
        assertThat(cc.get("medio")).isEqualTo("CUENTA_CORRIENTE");
        assertThat((BigDecimal) cc.get("recargo_porcentaje")).isEqualByComparingTo("0");
        assertThat((Boolean) cc.get("activa")).isFalse();
        assertThat(((Number) cc.get("orden")).intValue()).isEqualTo(5);
        // las de siempre no se tocan
        assertThat(formas.subList(0, 4)).extracting(m -> m.get("medio"))
                .containsExactly("EFECTIVO", "DEBITO", "CREDITO", "TRANSFERENCIA");
        assertThat(formas.subList(0, 4)).allSatisfy(m -> assertThat((Boolean) m.get("activa")).isTrue());
    }

    @Test
    @DisplayName("el orden de la forma sembrada es el maximo de la ferreteria + 1, no un 5 fijo")
    void ordenEsMaximoMasUno() {
        long f = ferreteria("30-83010013-1");
        formasDeSiempre(f);
        jdbc.update("update forma_pago set orden = 10 where ferreteria_id = ? and medio = 'TRANSFERENCIA'", f);

        migrarHasta("20");

        assertThat(jdbc.queryForObject("select orden from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Integer.class, f)).isEqualTo(11);
    }

    @Test
    @DisplayName("una ferreteria sin ninguna forma no recibe la sembrada: el servicio le siembra las 5")
    void noSiembraALaFerreteriaSinFormas() {
        long sinFormas = ferreteria("30-83010014-1");
        long conFormas = ferreteria("30-83010015-1");
        formasDeSiempre(conFormas);

        migrarHasta("20");

        assertThat(formas(sinFormas)).isEmpty();
        assertThat(formas(conFormas)).hasSize(5);
    }

    @Test
    @DisplayName("no choca con una forma ya llamada «cuenta corriente» (collation _ai_ci) y no la duplica")
    void noChocaConUnNombreExistente() {
        long f = ferreteria("30-83010016-1");
        formasDeSiempre(f);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values (?, 'CUENTA CORRIENTE', 'TRANSFERENCIA', 5)",
                f);

        migrarHasta("20");

        List<Map<String, Object>> formas = formas(f);
        assertThat(formas).hasSize(5);
        assertThat(formas).noneMatch(m -> "CUENTA_CORRIENTE".equals(m.get("medio")));
    }

    @Test
    @DisplayName("no siembra otra si la ferreteria ya tiene una forma de medio CUENTA_CORRIENTE (con otro nombre)")
    void noChocaConUnMedioExistente() {
        long f = ferreteria("30-83010017-1");
        formasDeSiempre(f);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values (?, 'Fiado', 'CUENTA_CORRIENTE', 5)", f);

        migrarHasta("20");

        assertThat(jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Integer.class, f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select nombre from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                String.class, f)).isEqualTo("Fiado");
    }

    @Test
    @DisplayName("es idempotente: reaplicar la sentencia de siembra no duplica nada")
    void siembraIdempotente() throws IOException {
        long f = ferreteria("30-83010018-1");
        formasDeSiempre(f);
        migrarHasta("20");
        assertThat(formas(f)).hasSize(5);

        String sql = new String(new ClassPathResource("db/migration/V20__cuenta_corriente_clientes.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        String siembra = sql.substring(sql.indexOf("INSERT INTO forma_pago"));
        siembra = siembra.substring(0, siembra.indexOf(';'));
        jdbc.update(siembra);
        jdbc.update(siembra);

        assertThat(formas(f)).hasSize(5);
    }
}
