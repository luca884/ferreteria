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
 *   <li><b>I2a</b>: por partida, Σ imputaciones VIGENTES ≤ su deuda (pendiente ≥ 0): un comprobante debe Σ debe de sus
 *       VENTA y un Recargo (V28) su importe. La que rompe la sobre-imputación; las no vigentes (de recibos anulados) no cuentan.
 *   <li><b>I2b</b>: por recibo, Σ imputaciones vigentes ≤ total, existe exactamente un COBRO, del mismo cliente, con haber =
 *       total; y si el recibo está ANULADO, existe exactamente una ANULACION del mismo cliente con debe = total y motivo.
 *   <li><b>I2c</b>: Σ pendientes (ventas y Recargos, con las imputaciones vigentes) − crédito sin imputar (Σ(total − Σ
 *       vigentes) de sus recibos EMITIDO y Σ(importe − Σ imputado) de sus Descuentos) + NC = saldo. Se cruza contra
 *       {@code recibo.estado} y {@code ajuste_cuenta}, no contra el libro: es otra fuente que el «aCuenta» del servicio. Un recibo
 *       ANULADO no deja crédito (su COBRO y su ANULACION se compensan). Desde V28 todo AJUSTE del libro tiene su
 *       {@code ajuste_cuenta} (ck_mcc_ajuste_ref): un saldo inicial sembrado con un AJUSTE es un Recargo o un Descuento.
 *   <li><b>I2d</b>: coherencia de cliente: la imputación, su origen (recibo o Descuento) y su destino (el débito VENTA del
 *       comprobante o el Recargo) son del mismo cliente, y el origen ajuste es un Descuento y el destino ajuste un Recargo.
 *   <li><b>I2f</b> (V28): cada ajuste tiene exactamente un AJUSTE en el libro, del mismo cliente, con el importe al debe (Recargo)
 *       o al haber (Descuento) y con motivo; Σ imputaciones de un Descuento ≤ su importe y todas vigentes (un ajuste no se anula).
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
        // Los tests de migración llaman al oráculo sobre esquemas anteriores a V28, sin ajustes: ahí esas partes no aplican.
        boolean conAjustes = conAjustes(jdbc);
        assertI1(jdbc, clienteId);
        assertI2d(jdbc, clienteId, conAjustes); // la más específica primero: una imputación ajena también rompe I2a
        assertI2a(jdbc, clienteId, conAjustes);
        assertI2e(jdbc, clienteId); // la equivalencia estado ⇔ ANULACION ⇔ vigencia antes que el detalle por recibo
        assertI2b(jdbc, clienteId);
        if (conAjustes) {
            assertI2f(jdbc, clienteId);
        }
        assertI2c(jdbc, clienteId, conAjustes);
        assertInactivo(jdbc, clienteId);
    }

    private static boolean conAjustes(JdbcTemplate jdbc) {
        Integer tablas = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = database() "
                + "and table_name = 'ajuste_cuenta'", Integer.class);
        return tablas != null && tablas > 0;
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

    private static void assertI2a(JdbcTemplate jdbc, long clienteId, boolean conAjustes) {
        Map<Long, BigDecimal> debe = porComprobante(jdbc, "select comprobante_id, sum(debe) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo = 'VENTA' group by comprobante_id", clienteId);
        Map<Long, BigDecimal> imputado = porComprobante(jdbc, "select comprobante_id, sum(importe) from imputacion "
                + "where cliente_id = ? and vigente = 1 and comprobante_id is not null group by comprobante_id", clienteId);
        assertNoSobreImputa(debe, imputado, "comprobante", clienteId);
        if (!conAjustes) {
            return;
        }
        Map<Long, BigDecimal> recargos = porComprobante(jdbc,
                "select id, importe from ajuste_cuenta where cliente_id = ? and tipo = 'RECARGO'", clienteId);
        Map<Long, BigDecimal> imputadoARecargos = porComprobante(jdbc, "select ajuste_destino_id, sum(importe) from imputacion "
                + "where cliente_id = ? and vigente = 1 and ajuste_destino_id is not null group by ajuste_destino_id", clienteId);
        assertNoSobreImputa(recargos, imputadoARecargos, "Recargo", clienteId);
    }

    private static void assertNoSobreImputa(Map<Long, BigDecimal> deuda, Map<Long, BigDecimal> imputado, String partida,
            long clienteId) {
        Set<Long> ids = new HashSet<>(deuda.keySet());
        ids.addAll(imputado.keySet());
        for (Long id : ids) {
            BigDecimal d = deuda.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal i = imputado.getOrDefault(id, BigDecimal.ZERO);
            assertThat(i).as("I2a: Σ imputaciones vigentes del %s %d (cliente %d) ≤ su deuda (%s)", partida, id, clienteId, d)
                    .isLessThanOrEqualTo(d);
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

    /** I2f (V28): cada ajuste cuenta lo mismo en {@code ajuste_cuenta}, en el libro y en sus imputaciones. */
    private static void assertI2f(JdbcTemplate jdbc, long clienteId) {
        List<Map<String, Object>> ajustes = jdbc.queryForList(
                "select a.id, a.tipo, a.importe, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.ajuste_id = a.id) as movimientos, "
                        + "(select count(*) from movimiento_cuenta_cliente m where m.ajuste_id = a.id and m.tipo = 'AJUSTE' "
                        + "and m.cliente_id = a.cliente_id and m.motivo is not null and ((a.tipo = 'RECARGO' and m.debe = a.importe "
                        + "and m.haber = 0) or (a.tipo = 'DESCUENTO' and m.haber = a.importe and m.debe = 0))) as movimientos_ok, "
                        + "(select coalesce(sum(i.importe), 0) from imputacion i where i.ajuste_origen_id = a.id) as imputado, "
                        + "(select count(*) from imputacion i where i.ajuste_origen_id = a.id and i.vigente = 0) as no_vigentes "
                        + "from ajuste_cuenta a where a.cliente_id = ?", clienteId);
        for (Map<String, Object> ajuste : ajustes) {
            Object id = ajuste.get("id");
            BigDecimal importe = (BigDecimal) ajuste.get("importe");
            assertThat(((Number) ajuste.get("movimientos")).longValue())
                    .as("I2f: el ajuste %s tiene exactamente un AJUSTE en el libro", id).isEqualTo(1L);
            assertThat(((Number) ajuste.get("movimientos_ok")).longValue()).as("I2f: el AJUSTE del %s %s es del mismo cliente, "
                    + "con motivo y con %s en el lado que le toca", ajuste.get("tipo"), id, importe).isEqualTo(1L);
            assertThat((BigDecimal) ajuste.get("imputado"))
                    .as("I2f: Σ imputaciones del ajuste %s ≤ su importe (%s)", id, importe).isLessThanOrEqualTo(importe);
            assertThat(((Number) ajuste.get("no_vigentes")).longValue())
                    .as("I2f: el ajuste %s no tiene imputaciones no vigentes (un ajuste no se anula)", id).isZero();
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

    private static void assertI2d(JdbcTemplate jdbc, long clienteId, boolean conAjustes) {
        Long sinReciboDelCliente = jdbc.queryForObject("select count(*) from imputacion i join recibo r on r.id = i.recibo_id "
                + "where (i.cliente_id = ? or r.cliente_id = ?) and i.cliente_id <> r.cliente_id", Long.class, clienteId,
                clienteId);
        assertThat(sinReciboDelCliente).as("I2d: las imputaciones ligadas al cliente %d son del mismo cliente que su recibo",
                clienteId).isZero();
        if (conAjustes) {
            assertAjustesDelMismoCliente(jdbc, clienteId);
        }
        Long sinDebito = jdbc.queryForObject("select count(*) from imputacion i where i.cliente_id = ? "
                + "and i.comprobante_id is not null and not exists "
                + "(select 1 from movimiento_cuenta_cliente m where m.comprobante_id = i.comprobante_id "
                + "and m.cliente_id = i.cliente_id and m.tipo = 'VENTA')", Long.class, clienteId);
        assertThat(sinDebito).as("I2d: cada imputación del cliente %d apunta a un comprobante con débito VENTA del mismo cliente",
                clienteId).isZero();
    }

    private static void assertAjustesDelMismoCliente(JdbcTemplate jdbc, long clienteId) {
        Long origenAjeno = jdbc.queryForObject("select count(*) from imputacion i join ajuste_cuenta a on a.id = i.ajuste_origen_id "
                + "where (i.cliente_id = ? or a.cliente_id = ?) and (i.cliente_id <> a.cliente_id or a.tipo <> 'DESCUENTO')",
                Long.class, clienteId, clienteId);
        assertThat(origenAjeno).as("I2d: el ajuste origen de cada imputación del cliente %d es un Descuento del mismo cliente",
                clienteId).isZero();
        Long destinoAjeno = jdbc.queryForObject("select count(*) from imputacion i join ajuste_cuenta a on a.id = i.ajuste_destino_id "
                + "where (i.cliente_id = ? or a.cliente_id = ?) and (i.cliente_id <> a.cliente_id or a.tipo <> 'RECARGO')",
                Long.class, clienteId, clienteId);
        assertThat(destinoAjeno).as("I2d: el ajuste destino de cada imputación del cliente %d es un Recargo del mismo cliente",
                clienteId).isZero();
    }

    private static void assertI2c(JdbcTemplate jdbc, long clienteId, boolean conAjustes) {
        BigDecimal saldo = jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
        BigDecimal debitos = suma(jdbc, "select coalesce(sum(debe), 0) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo = 'VENTA'", clienteId).add(!conAjustes ? BigDecimal.ZERO : suma(jdbc,
                "select coalesce(sum(importe), 0) from ajuste_cuenta where cliente_id = ? and tipo = 'RECARGO'", clienteId));
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
        if (conAjustes) {
            BigDecimal descontado = suma(jdbc,
                    "select coalesce(sum(importe), 0) from ajuste_cuenta where cliente_id = ? and tipo = 'DESCUENTO'", clienteId);
            BigDecimal imputadoDeDescuentos = suma(jdbc, "select coalesce(sum(i.importe), 0) from imputacion i "
                    + "join ajuste_cuenta a on a.id = i.ajuste_origen_id where a.cliente_id = ? and i.vigente = 1", clienteId);
            credito = credito.add(descontado.subtract(imputadoDeDescuentos));
        }
        // Antes de V28 un AJUSTE del libro no tenía ajuste_cuenta: se sumaba aparte, al lado de los pendientes, como la NC.
        BigDecimal notasDeCredito = suma(jdbc, "select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo " + (conAjustes ? "= 'NC'" : "in ('AJUSTE', 'NC')"), clienteId);
        assertThat(pendientes.subtract(credito).add(notasDeCredito))
                .as("I2c: Σ pendientes (%s) − a cuenta (%s) + NC (%s) == saldo del cliente %d", pendientes, credito,
                        notasDeCredito, clienteId)
                .isEqualByComparingTo(saldo);
    }
}
