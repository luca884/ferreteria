package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Las invariantes de la cuenta corriente (CC-001, CC-002, CC-040). Se llaman al final de CADA escenario de integración y de
 * concurrencia (también tras rollbacks, rechazos y repetidas) y paso a paso en el test de propiedad:
 *
 * <ul>
 *   <li><b>I1</b>: {@code cliente.saldo} = Σ(debe − haber) del libro.
 *   <li><b>I2a</b>: por comprobante, Σ imputaciones ≤ Σ debe de sus VENTA (pendiente ≥ 0). La que rompe la sobre-imputación.
 *   <li><b>I2b</b>: por recibo, Σ imputaciones ≤ total y existe exactamente un COBRO, del mismo cliente, con haber = total.
 *   <li><b>I2c</b>: Σ pendientes − crédito sin imputar (Σ(total − Σ imputaciones) de sus recibos) = saldo. El libro puede traer
 *       además AJUSTE y NC (los tests de tramo 2 siembran el saldo inicial con un AJUSTE): se suman al lado de los pendientes.
 *       Un ANULACION (que 3a no escribe) NO se compensa a propósito: la vigencia de las imputaciones de un recibo anulado es de
 *       3b, y hasta que la implemente este oráculo tiene que fallar en vez de pasar en silencio.
 *   <li><b>I2d</b>: coherencia de cliente: la imputación, su recibo y el débito VENTA de su comprobante son del mismo cliente.
 * </ul>
 *
 * Todo con {@code compareTo} (escala 2): nunca {@code equals} entre importes. Con un adelanto (recibo sin imputaciones, sin
 * deuda) el saldo es negativo y todo cierra: 0 pendientes − crédito = saldo.
 */
public final class InvarianteCuenta {

    private InvarianteCuenta() {}

    public static void assertInvariante(JdbcTemplate jdbc, long clienteId) {
        assertI1(jdbc, clienteId);
        assertI2d(jdbc, clienteId); // la más específica primero: una imputación ajena también rompe I2a
        assertI2a(jdbc, clienteId);
        assertI2b(jdbc, clienteId);
        assertI2c(jdbc, clienteId);
    }

    private static BigDecimal suma(JdbcTemplate jdbc, String sql, Object... args) {
        BigDecimal n = jdbc.queryForObject(sql, BigDecimal.class, args);
        return n == null ? BigDecimal.ZERO : n;
    }

    private static void assertI1(JdbcTemplate jdbc, long clienteId) {
        BigDecimal saldo = jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
        BigDecimal libro = suma(jdbc,
                "select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente where cliente_id = ?", clienteId);
        assertThat(saldo).as("I1: saldo del cliente %d == Σ(debe − haber) del libro", clienteId).isEqualByComparingTo(libro);
    }

    private static void assertI2a(JdbcTemplate jdbc, long clienteId) {
        Map<Long, BigDecimal> debe = porComprobante(jdbc, "select comprobante_id, sum(debe) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo = 'VENTA' group by comprobante_id", clienteId);
        Map<Long, BigDecimal> imputado = porComprobante(jdbc,
                "select comprobante_id, sum(importe) from imputacion where cliente_id = ? group by comprobante_id", clienteId);
        Set<Long> comprobantes = new HashSet<>(debe.keySet());
        comprobantes.addAll(imputado.keySet());
        for (Long comprobante : comprobantes) {
            BigDecimal d = debe.getOrDefault(comprobante, BigDecimal.ZERO);
            BigDecimal i = imputado.getOrDefault(comprobante, BigDecimal.ZERO);
            assertThat(i).as("I2a: Σ imputaciones del comprobante %d (cliente %d) ≤ Σ debe de sus VENTA (%s)", comprobante,
                    clienteId, d).isLessThanOrEqualTo(d);
        }
    }

    private static Map<Long, BigDecimal> porComprobante(JdbcTemplate jdbc, String sql, long clienteId) {
        Map<Long, BigDecimal> mapa = new TreeMap<>();
        jdbc.query(sql, rs -> {
            mapa.put(rs.getLong(1), rs.getBigDecimal(2));
        }, clienteId);
        return mapa;
    }

    private static void assertI2b(JdbcTemplate jdbc, long clienteId) {
        List<Map<String, Object>> recibos = jdbc.queryForList(
                "select r.id, r.total, (select coalesce(sum(i.importe), 0) from imputacion i where i.recibo_id = r.id) as imputado, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'COBRO') as cobros, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'COBRO' "
                        + "and m.cliente_id = r.cliente_id and m.haber = r.total) as cobros_ok "
                        + "from recibo r where r.cliente_id = ?", clienteId);
        for (Map<String, Object> recibo : recibos) {
            Object id = recibo.get("id");
            BigDecimal total = (BigDecimal) recibo.get("total");
            BigDecimal imputado = (BigDecimal) recibo.get("imputado");
            assertThat(imputado).as("I2b: Σ imputaciones del recibo %s ≤ su total (%s)", id, total).isLessThanOrEqualTo(total);
            assertThat(((Number) recibo.get("cobros")).longValue())
                    .as("I2b: el recibo %s tiene exactamente un COBRO en el libro", id).isEqualTo(1L);
            assertThat(((Number) recibo.get("cobros_ok")).longValue())
                    .as("I2b: el COBRO del recibo %s es del mismo cliente y su haber vale el total (%s)", id, total)
                    .isEqualTo(1L);
        }
    }

    private static void assertI2d(JdbcTemplate jdbc, long clienteId) {
        Long sinReciboDelCliente = jdbc.queryForObject("select count(*) from imputacion i join recibo r on r.id = i.recibo_id "
                + "where (i.cliente_id = ? or r.cliente_id = ?) and i.cliente_id <> r.cliente_id", Long.class, clienteId,
                clienteId);
        assertThat(sinReciboDelCliente).as("I2d: las imputaciones ligadas al cliente %d son del mismo cliente que su recibo",
                clienteId).isZero();
        Long sinDebito = jdbc.queryForObject("select count(*) from imputacion i where i.cliente_id = ? and not exists "
                + "(select 1 from movimiento_cuenta_cliente m where m.comprobante_id = i.comprobante_id "
                + "and m.cliente_id = i.cliente_id and m.tipo = 'VENTA')", Long.class, clienteId);
        assertThat(sinDebito).as("I2d: cada imputación del cliente %d apunta a un comprobante con débito VENTA del mismo cliente",
                clienteId).isZero();
    }

    private static void assertI2c(JdbcTemplate jdbc, long clienteId) {
        BigDecimal saldo = jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
        BigDecimal debitos = suma(jdbc, "select coalesce(sum(debe), 0) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo = 'VENTA'", clienteId);
        BigDecimal imputado = suma(jdbc, "select coalesce(sum(importe), 0) from imputacion where cliente_id = ?", clienteId);
        BigDecimal pendientes = debitos.subtract(imputado);
        BigDecimal recibido = suma(jdbc, "select coalesce(sum(total), 0) from recibo where cliente_id = ?", clienteId);
        BigDecimal imputadoDeRecibos = suma(jdbc, "select coalesce(sum(i.importe), 0) from imputacion i "
                + "join recibo r on r.id = i.recibo_id where r.cliente_id = ?", clienteId);
        BigDecimal credito = recibido.subtract(imputadoDeRecibos);
        BigDecimal ajustes = suma(jdbc, "select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo in ('AJUSTE', 'NC')", clienteId);
        assertThat(pendientes.subtract(credito).add(ajustes))
                .as("I2c: Σ pendientes (%s) − a cuenta (%s) + ajustes (%s) == saldo del cliente %d", pendientes, credito,
                        ajustes, clienteId)
                .isEqualByComparingTo(saldo);
    }
}
