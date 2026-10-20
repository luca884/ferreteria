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
 * Las invariantes de la cuenta corriente (CC-001, CC-002, CC-040, CC-084). Se llaman al final de CADA escenario de integración
 * y de concurrencia (también tras rollbacks, rechazos y repetidas) y paso a paso en el test de propiedad. Es un oráculo SQL,
 * independiente del servicio: el estado del recibo ({@code recibo.estado}) y la vigencia de las imputaciones se leen de la
 * base, no se derivan del libro.
 *
 * <ul>
 *   <li><b>I1</b>: {@code cliente.saldo} = Σ(debe − haber) del libro (la {@code ANULACION} entra con su débito).
 *   <li><b>I2a</b>: por comprobante, Σ imputaciones VIGENTES ≤ Σ debe de sus VENTA (pendiente ≥ 0). La que rompe la
 *       sobre-imputación; las no vigentes (de recibos anulados) no cuentan.
 *   <li><b>I2b</b>: por recibo, Σ imputaciones vigentes ≤ total, existe exactamente un COBRO, del mismo cliente, con haber =
 *       total; y si el recibo está ANULADO, existe exactamente una ANULACION del mismo cliente con debe = total y motivo.
 *   <li><b>I2c</b>: Σ pendientes (con las imputaciones vigentes) − crédito sin imputar (Σ(total − Σ vigentes) de sus recibos
 *       EMITIDO) + ajustes = saldo. Se cruza contra {@code recibo.estado} y no contra el libro: es otra fuente que el
 *       «aCuenta» del servicio. Un recibo ANULADO no deja crédito (su COBRO y su ANULACION se compensan). El libro puede traer
 *       además AJUSTE y NC (los tests de tramo 2 siembran el saldo inicial con un AJUSTE): se suman al lado de los pendientes.
 *   <li><b>I2d</b>: coherencia de cliente: la imputación, su recibo y el débito VENTA de su comprobante son del mismo cliente.
 *   <li><b>I2e</b> (CC-084): para cada recibo del cliente, {@code estado = ANULADO} ⇔ existe una ANULACION ⇔ todas sus
 *       imputaciones tienen {@code vigente = 0}; ninguna imputación no vigente con el recibo EMITIDO; y la auditoría de la
 *       anulación (caja y usuario) es de la misma ferretería.
 *   <li><b>I-inactivo</b> (CC-020/CC-040): un cliente desactivado tiene saldo 0.
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
        assertI2e(jdbc, clienteId); // la equivalencia estado ⇔ ANULACION ⇔ vigencia antes que el detalle por recibo
        assertI2b(jdbc, clienteId);
        assertI2c(jdbc, clienteId);
        assertInactivo(jdbc, clienteId);
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
                "select comprobante_id, sum(importe) from imputacion where cliente_id = ? and vigente = 1 group by comprobante_id",
                clienteId);
        Set<Long> comprobantes = new HashSet<>(debe.keySet());
        comprobantes.addAll(imputado.keySet());
        for (Long comprobante : comprobantes) {
            BigDecimal d = debe.getOrDefault(comprobante, BigDecimal.ZERO);
            BigDecimal i = imputado.getOrDefault(comprobante, BigDecimal.ZERO);
            assertThat(i).as("I2a: Σ imputaciones vigentes del comprobante %d (cliente %d) ≤ Σ debe de sus VENTA (%s)", comprobante,
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
                "select r.id, r.total, r.estado, "
                        + "(select coalesce(sum(i.importe), 0) from imputacion i where i.recibo_id = r.id and i.vigente = 1) as imputado, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'COBRO') as cobros, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'COBRO' "
                        + "and m.cliente_id = r.cliente_id and m.haber = r.total) as cobros_ok, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'ANULACION') as anulaciones, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'ANULACION' "
                        + "and m.cliente_id = r.cliente_id and m.debe = r.total and m.haber = 0 and m.motivo is not null) as anulaciones_ok "
                        + "from recibo r where r.cliente_id = ?", clienteId);
        for (Map<String, Object> recibo : recibos) {
            Object id = recibo.get("id");
            BigDecimal total = (BigDecimal) recibo.get("total");
            BigDecimal imputado = (BigDecimal) recibo.get("imputado");
            assertThat(imputado).as("I2b: Σ imputaciones vigentes del recibo %s ≤ su total (%s)", id, total)
                    .isLessThanOrEqualTo(total);
            assertThat(((Number) recibo.get("cobros")).longValue())
                    .as("I2b: el recibo %s tiene exactamente un COBRO en el libro", id).isEqualTo(1L);
            assertThat(((Number) recibo.get("cobros_ok")).longValue())
                    .as("I2b: el COBRO del recibo %s es del mismo cliente y su haber vale el total (%s)", id, total)
                    .isEqualTo(1L);
            if ("ANULADO".equals(recibo.get("estado"))) {
                assertThat(((Number) recibo.get("anulaciones")).longValue())
                        .as("I2b: el recibo ANULADO %s tiene exactamente una ANULACION en el libro", id).isEqualTo(1L);
                assertThat(((Number) recibo.get("anulaciones_ok")).longValue()).as("I2b: la ANULACION del recibo %s es del mismo "
                        + "cliente, con debe = total (%s), haber 0 y motivo", id, total).isEqualTo(1L);
            }
        }
    }

    /**
     * I2e (CC-084): el estado del recibo, su ANULACION y la vigencia de sus imputaciones cuentan la misma historia, y la
     * auditoría de la anulación es de la misma ferretería.
     */
    private static void assertI2e(JdbcTemplate jdbc, long clienteId) {
        List<Map<String, Object>> recibos = jdbc.queryForList(
                "select r.id, r.estado, r.ferreteria_id, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.recibo_id = r.id and m.tipo = 'ANULACION') as anulaciones, "
                        + "(select count(*) from imputacion i where i.recibo_id = r.id and i.vigente = 1) as vigentes, "
                        + "(select count(*) from imputacion i where i.recibo_id = r.id and i.vigente = 0) as no_vigentes, "
                        + "(select count(*) from caja c where c.id = r.caja_anulacion_id and c.ferreteria_id = r.ferreteria_id) as caja_propia, "
                        + "(select count(*) from usuario u where u.id = r.usuario_anulacion_id and u.ferreteria_id = r.ferreteria_id) as usuario_propio "
                        + "from recibo r where r.cliente_id = ?", clienteId);
        for (Map<String, Object> recibo : recibos) {
            Object id = recibo.get("id");
            boolean anulado = "ANULADO".equals(recibo.get("estado"));
            long anulaciones = ((Number) recibo.get("anulaciones")).longValue();
            assertThat(anulaciones > 0).as("I2e: el recibo %s está %s y %s ANULACION en el libro", id, recibo.get("estado"),
                    anulaciones > 0 ? "tiene" : "no tiene").isEqualTo(anulado);
            if (anulado) {
                assertThat(((Number) recibo.get("vigentes")).longValue())
                        .as("I2e: el recibo ANULADO %s no tiene imputaciones vigentes", id).isZero();
                assertThat(((Number) recibo.get("caja_propia")).longValue())
                        .as("I2e: la caja de la anulación del recibo %s es de su misma ferretería", id).isEqualTo(1L);
                assertThat(((Number) recibo.get("usuario_propio")).longValue())
                        .as("I2e: el usuario de la anulación del recibo %s es de su misma ferretería", id).isEqualTo(1L);
            } else {
                assertThat(((Number) recibo.get("no_vigentes")).longValue())
                        .as("I2e: el recibo EMITIDO %s no tiene imputaciones no vigentes", id).isZero();
            }
        }
    }

    /** I-inactivo: un cliente desactivado no puede tener saldo (CC-020, CC-040). */
    private static void assertInactivo(JdbcTemplate jdbc, long clienteId) {
        Boolean activo = jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, clienteId);
        BigDecimal saldo = jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
        if (!Boolean.TRUE.equals(activo)) {
            assertThat(saldo).as("I-inactivo: el cliente desactivado %d tiene saldo 0", clienteId).isEqualByComparingTo(BigDecimal.ZERO);
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
        BigDecimal imputado = suma(jdbc, "select coalesce(sum(importe), 0) from imputacion where cliente_id = ? and vigente = 1",
                clienteId);
        BigDecimal pendientes = debitos.subtract(imputado);
        // el crédito sin imputar sale de los recibos EMITIDO (un ANULADO no deja crédito: su COBRO y su ANULACION se compensan)
        BigDecimal recibido = suma(jdbc, "select coalesce(sum(total), 0) from recibo where cliente_id = ? and estado = 'EMITIDO'",
                clienteId);
        BigDecimal imputadoDeRecibos = suma(jdbc, "select coalesce(sum(i.importe), 0) from imputacion i "
                + "join recibo r on r.id = i.recibo_id where r.cliente_id = ? and r.estado = 'EMITIDO' and i.vigente = 1",
                clienteId);
        BigDecimal credito = recibido.subtract(imputadoDeRecibos);
        BigDecimal ajustes = suma(jdbc, "select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo in ('AJUSTE', 'NC')", clienteId);
        assertThat(pendientes.subtract(credito).add(ajustes))
                .as("I2c: Σ pendientes (%s) − a cuenta (%s) + ajustes (%s) == saldo del cliente %d", pendientes, credito,
                        ajustes, clienteId)
                .isEqualByComparingTo(saldo);
    }
}
