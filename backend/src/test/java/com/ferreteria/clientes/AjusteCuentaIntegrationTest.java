package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GDS-20 contra MySQL real y por HTTP (S1 a S7): {@code POST /api/clientes/{id}/cuenta-corriente/ajustes} (un Recargo sube la
 * deuda y es una partida pendiente; un Descuento la baja y se imputa o queda a cuenta), el cobro de un Recargo con {@code ajusteId},
 * el consumo automático del crédito por una venta a cuenta y por un Recargo, la baja de todo eso al anular el recibo y el
 * aislamiento por ferretería. La invariante Σ pendiente − aCuenta = saldo se comprueba con un oráculo SQL propio, aparte del
 * servicio, tras cada paso. CUIT 30-8529NNNN-1.
 */
class AjusteCuentaIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CuentaCorrienteParaVenta cuentas;

    @Override
    protected String prefijoDeCuit() {
        return "30-8529";
    }

    /** Una ferretería con su Dueño y su Vendedor ya logueados, caja abierta y un cliente con cuenta corriente. */
    private record Caso(Ferreteria f, String dueno, String vendedor, long cliente) {}

    private Caso caso() throws Exception {
        Ferreteria f = ferreteria();
        Logins logins = logins(f);
        cajaAbiertaHoy(f.id());
        return new Caso(f, login(logins.dueno()), login(logins.vendedor()), cliente(f, "1000000.00"));
    }

    // ---- armado de pedidos ----

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private static String url(long cliente) {
        return "/api/clientes/" + cliente + "/cuenta-corriente/ajustes";
    }

    private static String aComprobante(long comprobanteId, String importe) {
        return "{\"comprobanteId\":" + comprobanteId + ",\"importe\":" + importe + "}";
    }

    private static String aRecargo(long ajusteId, String importe) {
        return "{\"ajusteId\":" + ajusteId + ",\"importe\":" + importe + "}";
    }

    private static String pedido(String clave, String tipo, String importe, String motivo, String... imputaciones) {
        return "{\"clave\":\"" + clave + "\",\"tipo\":\"" + tipo + "\",\"importe\":" + importe + ",\"motivo\":\"" + motivo
                + "\",\"imputaciones\":[" + String.join(",", imputaciones) + "]}";
    }

    private ResultActions ajustar(String token, long cliente, String cuerpo) throws Exception {
        return postJson(url(cliente), token, cuerpo);
    }

    private JsonNode recargo(Caso c, String importe) throws Exception {
        return json(ajustar(c.dueno(), c.cliente(), pedido(clave(), "RECARGO", importe, "Interés por mora"))
                .andExpect(status().isCreated()));
    }

    private JsonNode descuento(Caso c, String importe, String... imputaciones) throws Exception {
        return json(ajustar(c.dueno(), c.cliente(), pedido(clave(), "DESCUENTO", importe, "Bonificación del Dueño", imputaciones))
                .andExpect(status().isCreated()));
    }

    private static String cobro(long cliente, String total, String... imputaciones) {
        return "{\"clave\":\"" + clave() + "\",\"clienteId\":" + cliente + ",\"medio\":\"EFECTIVO\",\"total\":" + total
                + ",\"imputaciones\":[" + String.join(",", imputaciones) + "]}";
    }

    private JsonNode cobrar(Caso c, String total, String... imputaciones) throws Exception {
        return json(postJson("/api/cobranzas", c.dueno(), cobro(c.cliente(), total, imputaciones)).andExpect(status().isCreated()));
    }

    private JsonNode anular(Caso c, long recibo) throws Exception {
        return json(postJson("/api/recibos/" + recibo + "/anulacion", c.dueno(), "{\"motivo\":\"Cobro mal cargado\"}")
                .andExpect(status().isOk()));
    }

    private JsonNode pendientes(Caso c) throws Exception {
        return getJson("/api/clientes/" + c.cliente() + "/cuenta-corriente/pendientes", c.dueno());
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> claves(JsonNode nodo) {
        List<String> claves = new ArrayList<>();
        nodo.fieldNames().forEachRemaining(claves::add);
        return claves;
    }

    /** Una venta a cuenta corriente asentada por el puerto, como RegistroVenta: toma al cliente y asienta (consume el crédito). */
    private VentaSuelta vender(Caso c, String monto) {
        VentaSuelta venta = ventaSuelta(c.f(), c.cliente(), monto);
        TenantContext.setCurrentFerreteriaId(c.f().id());
        new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(c.cliente()).orElseThrow();
            cuentas.asentarVenta(new AsientoVenta(c.cliente(), venta.comprobanteId(), venta.numero(),
                    List.of(new CargoPago(venta.pagoId(), new BigDecimal(monto))), c.f().usuarioId(), null));
            return null;
        });
        return venta;
    }

    // ---- consultas de la base ----

    private Long ultimoNumeroDeAjuste(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_ajuste_cuenta where ferreteria_id = ?",
                Long.class, ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private long ajustes(long cliente) {
        return jdbc.queryForObject("select count(*) from ajuste_cuenta where cliente_id = ?", Long.class, cliente);
    }

    private long vigentes(long cliente) {
        return jdbc.queryForObject("select count(*) from imputacion where cliente_id = ? and vigente = 1", Long.class, cliente);
    }

    /** Las imputaciones del cliente como «origen -> destino: importe (automática)», en el orden en que se guardaron. */
    private List<String> imputacionesDe(long cliente) {
        return jdbc.query("select recibo_id, ajuste_origen_id, comprobante_id, ajuste_destino_id, importe, automatica, vigente "
                + "from imputacion where cliente_id = ? order by id", (rs, i) -> (rs.getObject("recibo_id") != null
                        ? "R" + rs.getLong("recibo_id") : "D" + rs.getLong("ajuste_origen_id")) + " -> "
                        + (rs.getObject("comprobante_id") != null ? "C" + rs.getLong("comprobante_id")
                                : "A" + rs.getLong("ajuste_destino_id"))
                        + ": " + rs.getBigDecimal("importe").toPlainString() + (rs.getBoolean("automatica") ? " auto" : "")
                        + (rs.getBoolean("vigente") ? "" : " baja"), cliente);
    }

    // ---- oráculo: Σ pendiente − aCuenta = saldo, calculado desde el libro y las imputaciones, sin el servicio ----

    private Map<Long, BigDecimal> porId(String sql, long cliente) {
        Map<Long, BigDecimal> mapa = new HashMap<>();
        jdbc.query(sql, rs -> {
            mapa.put(rs.getLong(1), rs.getBigDecimal(2));
        }, cliente);
        return mapa;
    }

    /** Cada partida (venta o Recargo) con su pendiente = debe − Σ imputado vigente; cada origen (recibo o Descuento) con lo que le queda. */
    private record Cuenta(BigDecimal pendientes, BigDecimal aCuenta) {}

    private Cuenta oraculo(long cliente) {
        BigDecimal pendientes = BigDecimal.ZERO;
        pendientes = pendientes.add(restos(
                porId("select comprobante_id, sum(debe) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'VENTA' "
                        + "group by comprobante_id", cliente),
                porId("select comprobante_id, sum(importe) from imputacion where cliente_id = ? and vigente = 1 "
                        + "and comprobante_id is not null group by comprobante_id", cliente), "partida"));
        pendientes = pendientes.add(restos(
                porId("select ajuste_id, sum(debe) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'AJUSTE' "
                        + "and debe > 0 group by ajuste_id", cliente),
                porId("select ajuste_destino_id, sum(importe) from imputacion where cliente_id = ? and vigente = 1 "
                        + "and ajuste_destino_id is not null group by ajuste_destino_id", cliente), "recargo"));
        BigDecimal aCuenta = restos(
                porId("select recibo_id, sum(haber - debe) from movimiento_cuenta_cliente where cliente_id = ? "
                        + "and tipo in ('COBRO', 'ANULACION') group by recibo_id", cliente),
                porId("select recibo_id, sum(importe) from imputacion where cliente_id = ? and vigente = 1 "
                        + "and recibo_id is not null group by recibo_id", cliente), "recibo");
        aCuenta = aCuenta.add(restos(
                porId("select ajuste_id, sum(haber) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'AJUSTE' "
                        + "and haber > 0 group by ajuste_id", cliente),
                porId("select ajuste_origen_id, sum(importe) from imputacion where cliente_id = ? and vigente = 1 "
                        + "and ajuste_origen_id is not null group by ajuste_origen_id", cliente), "descuento"));
        return new Cuenta(pendientes, aCuenta);
    }

    /** Σ (total − usado) por clave, exigiendo que ninguna quede en negativo (nada sobre-imputado, nada sobre-consumido). */
    private static BigDecimal restos(Map<Long, BigDecimal> totales, Map<Long, BigDecimal> usados, String que) {
        BigDecimal suma = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : totales.entrySet()) {
            BigDecimal queda = e.getValue().subtract(usados.getOrDefault(e.getKey(), BigDecimal.ZERO));
            assertThat(queda).as("%s %d no queda en negativo", que, e.getKey()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            suma = suma.add(queda);
        }
        usados.keySet().forEach(id -> assertThat(totales).as("%s %d usado sin existir", que, id).containsKey(id));
        return suma;
    }

    /** Σ pendiente − aCuenta = saldo en la base, y el servicio de pendientes devuelve las mismas tres cifras. */
    private void assertCuenta(Caso c) throws Exception {
        Cuenta cuenta = oraculo(c.cliente());
        assertThat(cuenta.pendientes().subtract(cuenta.aCuenta())).as("Σ pendiente − aCuenta = saldo")
                .isEqualByComparingTo(saldo(c.cliente()));
        JsonNode p = pendientes(c);
        BigDecimal pendiente = BigDecimal.ZERO;
        for (JsonNode partida : p.get("comprobantes")) {
            pendiente = pendiente.add(partida.get("pendiente").decimalValue());
        }
        assertThat(p.get("saldo").decimalValue()).isEqualByComparingTo(saldo(c.cliente()));
        assertThat(pendiente).as("Σ pendiente del servicio").isEqualByComparingTo(cuenta.pendientes());
        assertThat(p.get("aCuenta").decimalValue()).as("aCuenta del servicio").isEqualByComparingTo(cuenta.aCuenta());
    }

    // ================================================================== S1: Recargo

    @Test
    @DisplayName("S1: un Recargo sube la deuda, queda en el libro y en el saldo, numera y devuelve 201 con su forma")
    void recargo() throws Exception {
        Caso c = caso();
        ventaEnLibro(c.f(), c.cliente(), "1000.00");

        JsonNode r = recargo(c, "150.50");

        assertThat(claves(r)).containsExactlyInAnyOrder("ajusteId", "numero", "referencia", "tipo", "importe", "motivo", "fecha",
                "saldo", "imputaciones");
        assertThat(r.get("numero").asLong()).isEqualTo(1L);
        assertThat(r.get("referencia").asText()).isEqualTo("Recargo N.º 1");
        assertThat(r.get("tipo").asText()).isEqualTo("RECARGO");
        assertThat(r.get("importe").decimalValue()).isEqualByComparingTo("150.50");
        assertThat(r.get("motivo").asText()).isEqualTo("Interés por mora");
        assertThat(r.get("saldo").decimalValue()).isEqualByComparingTo("1150.50");
        assertThat(r.get("imputaciones")).isEmpty();
        Map<String, Object> asiento = jdbc.queryForMap("select tipo, debe, haber, referencia, motivo, ajuste_id, usuario_id "
                + "from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'AJUSTE'", c.cliente());
        assertThat((BigDecimal) asiento.get("debe")).isEqualByComparingTo("150.50");
        assertThat((BigDecimal) asiento.get("haber")).isEqualByComparingTo("0");
        assertThat(asiento.get("referencia")).isEqualTo("Recargo N.º 1");
        assertThat(asiento.get("motivo")).isEqualTo("Interés por mora");
        assertThat(((Number) asiento.get("ajuste_id")).longValue()).isEqualTo(r.get("ajusteId").asLong());
        assertThat(((Number) asiento.get("usuario_id")).longValue()).isEqualTo(c.f().usuarioId());
        assertThat(saldo(c.cliente())).isEqualByComparingTo("1150.50");
        assertThat(ultimoNumeroDeAjuste(c.f().id())).isEqualTo(1L);
        assertCuenta(c);
    }

    @Test
    @DisplayName("S1: la numeración es una por ferretería y correlativa entre Recargos y Descuentos; no usa la caja")
    void numeracion() throws Exception {
        Ferreteria f = ferreteria(); // sin caja abierta a propósito: ajustar la cuenta no mueve efectivo
        Logins logins = logins(f);
        Caso c = new Caso(f, login(logins.dueno()), login(logins.vendedor()), cliente(f, "1000.00"));
        Caso otro = new Caso(f, c.dueno(), c.vendedor(), cliente(f, "1000.00"));

        JsonNode a = recargo(c, "10.00");
        JsonNode b = descuento(otro, "5.00");
        JsonNode d = recargo(c, "1.00");

        assertThat(a.get("numero").asLong()).isEqualTo(1L);
        assertThat(b.get("numero").asLong()).isEqualTo(2L);
        assertThat(b.get("referencia").asText()).isEqualTo("Descuento N.º 2");
        assertThat(d.get("numero").asLong()).isEqualTo(3L);
        assertThat(ultimoNumeroDeAjuste(f.id())).isEqualTo(3L);
    }

    @Test
    @DisplayName("S1: el Recargo aparece en pendientes como una partida (ajusteId, comprobanteId null) y la venta sigue con su comprobanteId")
    void recargoEnPendientes() throws Exception {
        Caso c = caso();
        VentaSuelta venta = ventaEnLibro(c.f(), c.cliente(), "1000.00");
        JsonNode r = recargo(c, "150.50");

        JsonNode p = pendientes(c);

        assertThat(p.get("comprobantes")).hasSize(2);
        JsonNode deVenta = p.get("comprobantes").get(0);
        JsonNode deRecargo = p.get("comprobantes").get(1);
        assertThat(deVenta.get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(deVenta.get("ajusteId").isNull()).isTrue();
        assertThat(deVenta.get("referencia").asText()).isEqualTo("Venta N.º " + venta.numero());
        assertThat(deVenta.get("pendiente").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(deRecargo.get("comprobanteId").isNull()).isTrue();
        assertThat(deRecargo.get("ajusteId").asLong()).isEqualTo(r.get("ajusteId").asLong());
        assertThat(deRecargo.get("referencia").asText()).isEqualTo("Recargo N.º 1");
        assertThat(deRecargo.get("importe").decimalValue()).isEqualByComparingTo("150.50");
        assertThat(deRecargo.get("pendiente").decimalValue()).isEqualByComparingTo("150.50");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S1: un cobro con ajusteId cancela un Recargo (recibo e imputación con ajusteId); la reimpresión del recibo lo muestra igual")
    void cobroDeUnRecargo() throws Exception {
        Caso c = caso();
        JsonNode r = recargo(c, "200.00");
        long ajuste = r.get("ajusteId").asLong();

        JsonNode recibo = cobrar(c, "150.00", aRecargo(ajuste, "150.00"));

        assertThat(recibo.get("imputaciones")).hasSize(1);
        JsonNode imputacion = recibo.get("imputaciones").get(0);
        assertThat(imputacion.get("ajusteId").asLong()).isEqualTo(ajuste);
        assertThat(imputacion.get("comprobanteId").isNull()).isTrue();
        assertThat(imputacion.get("referencia").asText()).isEqualTo("Recargo N.º 1");
        assertThat(imputacion.get("importe").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(saldo(c.cliente())).isEqualByComparingTo("50.00");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes")).hasSize(1);
        assertThat(p.get("comprobantes").get(0).get("imputado").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(p.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("50.00");
        JsonNode reimpreso = getJson("/api/recibos/" + recibo.get("id").asLong(), c.dueno());
        assertThat(reimpreso.get("imputaciones")).isEqualTo(recibo.get("imputaciones"));
        assertCuenta(c);
    }

    @Test
    @DisplayName("S1: cobrar de más un Recargo es 409 IMPUTACION_EXCEDE_DEUDA con su ajusteId y la deuda actual; un renglón con comprobanteId y ajusteId es 400")
    void cobroDeUnRecargoRechazado() throws Exception {
        Caso c = caso();
        long ajuste = recargo(c, "200.00").get("ajusteId").asLong();
        cobrar(c, "150.00", aRecargo(ajuste, "150.00"));
        long recibos = recibos(c.cliente());

        JsonNode excede = json(postJson("/api/cobranzas", c.dueno(), cobro(c.cliente(), "100.00", aRecargo(ajuste, "100.00")))
                .andExpect(status().isConflict()));
        JsonNode ambos = json(postJson("/api/cobranzas", c.dueno(), cobro(c.cliente(), "10.00",
                "{\"comprobanteId\":1,\"ajusteId\":" + ajuste + ",\"importe\":10}")).andExpect(status().isBadRequest()));

        assertThat(excede.get("motivo").asText()).isEqualTo("IMPUTACION_EXCEDE_DEUDA");
        assertThat(excede.get("comprobantes")).hasSize(1);
        assertThat(excede.get("comprobantes").get(0).get("ajusteId").asLong()).isEqualTo(ajuste);
        assertThat(excede.get("comprobantes").get(0).get("comprobanteId").isNull()).isTrue();
        assertThat(excede.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(ambos.get("errors").has("imputaciones[0].ajusteId")).isTrue();
        assertThat(recibos(c.cliente())).as("no se emitió ningún recibo más").isEqualTo(recibos);
        assertCuenta(c);
    }

    @Test
    @DisplayName("regresión: un cobro con solo comprobanteId sigue igual (ajusteId null en el recibo) y pendientes de una venta trae ajusteId null")
    void cobroDeUnComprobanteSigueIgual() throws Exception {
        Caso c = caso();
        VentaSuelta venta = ventaEnLibro(c.f(), c.cliente(), "300.00");

        JsonNode recibo = cobrar(c, "100.00", aComprobante(venta.comprobanteId(), "100.00"));

        JsonNode imputacion = recibo.get("imputaciones").get(0);
        assertThat(imputacion.get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(imputacion.get("ajusteId").isNull()).isTrue();
        assertThat(imputacion.get("referencia").asText()).isEqualTo("Venta N.º " + venta.numero());
        JsonNode partida = pendientes(c).get("comprobantes").get(0);
        assertThat(partida.get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(partida.get("ajusteId").isNull()).isTrue();
        assertThat(partida.get("pendiente").decimalValue()).isEqualByComparingTo("200.00");
        assertCuenta(c);
    }

    // ================================================================== S2: Descuento

    @Test
    @DisplayName("S2: un Descuento imputado a un comprobante baja su pendiente y el saldo; el resto queda a cuenta")
    void descuentoImputado() throws Exception {
        Caso c = caso();
        VentaSuelta venta = ventaEnLibro(c.f(), c.cliente(), "1000.00");

        JsonNode d = descuento(c, "300.00", aComprobante(venta.comprobanteId(), "200.00"));

        assertThat(d.get("referencia").asText()).isEqualTo("Descuento N.º 1");
        assertThat(d.get("saldo").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(d.get("imputaciones")).hasSize(1);
        JsonNode imputacion = d.get("imputaciones").get(0);
        assertThat(claves(imputacion)).containsExactlyInAnyOrder("comprobanteId", "ajusteId", "referencia", "importe");
        assertThat(imputacion.get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(imputacion.get("ajusteId").isNull()).isTrue();
        assertThat(imputacion.get("referencia").asText()).isEqualTo("Venta N.º " + venta.numero());
        assertThat(imputacion.get("importe").decimalValue()).isEqualByComparingTo("200.00");
        Map<String, Object> asiento = jdbc.queryForMap("select debe, haber, referencia from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo = 'AJUSTE'", c.cliente());
        assertThat((BigDecimal) asiento.get("haber")).isEqualByComparingTo("300.00");
        assertThat((BigDecimal) asiento.get("debe")).isEqualByComparingTo("0");
        assertThat(imputacionesDe(c.cliente())).containsExactly(
                "D" + d.get("ajusteId").asLong() + " -> C" + venta.comprobanteId() + ": 200.00");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("100.00");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S2: un Descuento puede imputarse a un Recargo; sin renglones queda entero a cuenta")
    void descuentoAUnRecargoYSinImputar() throws Exception {
        Caso c = caso();
        long recargo = recargo(c, "100.00").get("ajusteId").asLong();

        JsonNode aRecargo = descuento(c, "60.00", aRecargo(recargo, "60.00"));
        JsonNode aCuenta = descuento(c, "25.00");

        assertThat(aRecargo.get("imputaciones").get(0).get("ajusteId").asLong()).isEqualTo(recargo);
        assertThat(aRecargo.get("imputaciones").get(0).get("comprobanteId").isNull()).isTrue();
        assertThat(aRecargo.get("imputaciones").get(0).get("referencia").asText()).isEqualTo("Recargo N.º 1");
        assertThat(aCuenta.get("imputaciones")).isEmpty();
        assertThat(aCuenta.get("saldo").decimalValue()).isEqualByComparingTo("15.00");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("25.00");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S2: un Descuento sin imputar queda como crédito y una venta a cuenta posterior lo consume sola")
    void descuentoConsumidoPorUnaVenta() throws Exception {
        Caso c = caso();
        long descuento = descuento(c, "200.00").get("ajusteId").asLong();

        VentaSuelta venta = vender(c, "300.00");

        assertThat(imputacionesDe(c.cliente())).containsExactly("D" + descuento + " -> C" + venta.comprobanteId() + ": 200.00 auto");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes")).hasSize(1);
        assertThat(p.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
        assertThat(saldo(c.cliente())).isEqualByComparingTo("100.00");
        assertCuenta(c);
    }

    // ================================================================== S3: consumo automático de crédito

    @Test
    @DisplayName("S3: una venta a cuenta consume el crédito de los recibos más viejos primero y deja pendiente solo lo que no cubrió")
    void ventaConsumeCreditoFifo() throws Exception {
        Caso c = caso();
        long primero = cobrar(c, "200.00").get("id").asLong();
        long segundo = cobrar(c, "200.00").get("id").asLong();

        VentaSuelta venta = vender(c, "300.00");

        assertThat(imputacionesDe(c.cliente())).containsExactly(
                "R" + primero + " -> C" + venta.comprobanteId() + ": 200.00 auto",
                "R" + segundo + " -> C" + venta.comprobanteId() + ": 100.00 auto");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes")).isEmpty();
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(saldo(c.cliente())).isEqualByComparingTo("-100.00");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S3: con menos crédito que deuda, consume todo el crédito y la venta queda pendiente por la diferencia")
    void ventaConCreditoParcial() throws Exception {
        Caso c = caso();
        cobrar(c, "200.00");

        VentaSuelta venta = vender(c, "500.00");

        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes")).hasSize(1);
        assertThat(p.get("comprobantes").get(0).get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(p.get("comprobantes").get(0).get("imputado").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(p.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
        assertThat(saldo(c.cliente())).isEqualByComparingTo("300.00");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S3: el crédito se consume en orden de fecha entre recibos y Descuentos (el más viejo primero)")
    void consumoFifoEntreDescuentoYRecibo() throws Exception {
        Caso c = caso();
        long descuento = descuento(c, "200.00").get("ajusteId").asLong();
        long recibo = cobrar(c, "200.00").get("id").asLong();

        VentaSuelta venta = vender(c, "300.00");

        assertThat(imputacionesDe(c.cliente())).containsExactly(
                "D" + descuento + " -> C" + venta.comprobanteId() + ": 200.00 auto",
                "R" + recibo + " -> C" + venta.comprobanteId() + ": 100.00 auto");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S3: un Recargo consume el crédito a cuenta; el Recargo queda pendiente por el resto y su respuesta no trae imputaciones")
    void recargoConsumeCredito() throws Exception {
        Caso c = caso();
        long recibo = cobrar(c, "500.00").get("id").asLong();

        JsonNode r = recargo(c, "300.00");

        assertThat(r.get("imputaciones")).isEmpty();
        assertThat(r.get("saldo").decimalValue()).isEqualByComparingTo("-200.00");
        assertThat(imputacionesDe(c.cliente())).containsExactly("R" + recibo + " -> A" + r.get("ajusteId").asLong() + ": 300.00 auto");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes")).isEmpty();
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("200.00");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S3: el crédito consumido no cambia el recibo: su reimpresión no suma las imputaciones automáticas y repetir el cobro devuelve 200")
    void elReciboNoCambiaAlConsumirseSuCredito() throws Exception {
        Caso c = caso();
        String cuerpo = cobro(c.cliente(), "500.00");
        JsonNode recibo = json(postJson("/api/cobranzas", c.dueno(), cuerpo).andExpect(status().isCreated()));
        vender(c, "300.00");
        recargo(c, "100.00");

        JsonNode reimpreso = getJson("/api/recibos/" + recibo.get("id").asLong(), c.dueno());
        JsonNode repetido = json(postJson("/api/cobranzas", c.dueno(), cuerpo).andExpect(status().isOk()));

        assertThat(reimpreso.get("imputaciones")).isEmpty();
        assertThat(reimpreso.get("aCuenta").decimalValue()).isEqualByComparingTo(recibo.get("aCuenta").decimalValue());
        assertThat(repetido.get("id").asLong()).isEqualTo(recibo.get("id").asLong());
        assertThat(recibos(c.cliente())).isEqualTo(1);
        assertCuenta(c);
    }

    @Test
    @DisplayName("S4: anular el recibo da de baja lo que se imputó a mano y lo que se consumió solo; vuelve la deuda y el crédito queda en 0")
    void anularLiberaTodo() throws Exception {
        Caso c = caso();
        VentaSuelta anterior = ventaEnLibro(c.f(), c.cliente(), "400.00");
        long recibo = cobrar(c, "700.00", aComprobante(anterior.comprobanteId(), "400.00")).get("id").asLong();
        VentaSuelta venta = vender(c, "200.00"); // consume los 300 a cuenta del recibo
        recargo(c, "50.00");
        assertThat(saldo(c.cliente())).isEqualByComparingTo("-50.00");

        anular(c, recibo);

        assertThat(vigentes(c.cliente())).as("ninguna imputación sigue vigente").isZero();
        assertThat(imputacionesDe(c.cliente())).allMatch(i -> i.endsWith(" baja"));
        assertThat(saldo(c.cliente())).isEqualByComparingTo("650.00");
        JsonNode p = pendientes(c);
        assertThat(p.get("comprobantes")).hasSize(3);
        assertThat(p.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
        assertThat(p.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(p.get("comprobantes").get(1).get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(p.get("comprobantes").get(1).get("pendiente").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(p.get("comprobantes").get(2).get("pendiente").decimalValue()).isEqualByComparingTo("50.00");
        assertCuenta(c);
    }

    @Test
    @DisplayName("S3, S4: tras una secuencia mezclada de ventas, cobros, Recargos, Descuentos y anulaciones, Σ pendiente − aCuenta = saldo en cada paso")
    void secuenciaMezclada() throws Exception {
        Caso c = caso();
        VentaSuelta a = ventaEnLibro(c.f(), c.cliente(), "1000.00");
        assertCuenta(c);
        long recargo = recargo(c, "100.00").get("ajusteId").asLong();
        assertCuenta(c);
        long recibo = cobrar(c, "450.00", aComprobante(a.comprobanteId(), "400.00")).get("id").asLong();
        assertCuenta(c);
        descuento(c, "80.00", aRecargo(recargo, "50.00"), aComprobante(a.comprobanteId(), "20.00"));
        assertCuenta(c);
        vender(c, "200.00"); // consume los 50 a cuenta del recibo y los 10 del Descuento
        assertCuenta(c);
        cobrar(c, "300.00"); // un adelanto
        assertCuenta(c);
        recargo(c, "75.00"); // lo consume el adelanto
        assertCuenta(c);
        anular(c, recibo);
        assertCuenta(c);
        descuento(c, "40.00");
        assertCuenta(c);
        vender(c, "90.00");
        assertCuenta(c);
    }

    // ================================================================== S5: idempotencia

    @Test
    @DisplayName("S5: repetir la clave con el mismo contenido devuelve 200 con el mismo ajuste y no vuelve a asentar; con otro contenido, 409")
    void idempotencia() throws Exception {
        Caso c = caso();
        VentaSuelta venta = ventaEnLibro(c.f(), c.cliente(), "500.00");
        String clave = clave();
        String cuerpo = pedido(clave, "DESCUENTO", "100.00", "Bonificación del Dueño", aComprobante(venta.comprobanteId(), "100.00"));
        JsonNode primero = json(ajustar(c.dueno(), c.cliente(), cuerpo).andExpect(status().isCreated()));
        long movimientos = movimientos(c.cliente());

        JsonNode repetido = json(ajustar(c.dueno(), c.cliente(), cuerpo).andExpect(status().isOk()));
        JsonNode otroContenido = json(ajustar(c.dueno(), c.cliente(),
                pedido(clave, "DESCUENTO", "120.00", "Bonificación del Dueño", aComprobante(venta.comprobanteId(), "120.00")))
                .andExpect(status().isConflict()));

        assertThat(repetido.get("ajusteId")).isEqualTo(primero.get("ajusteId"));
        assertThat(repetido.get("numero")).isEqualTo(primero.get("numero"));
        assertThat(repetido.get("saldo")).isEqualTo(primero.get("saldo"));
        assertThat(repetido.get("imputaciones")).isEqualTo(primero.get("imputaciones"));
        assertThat(otroContenido.get("message").asText()).isEqualTo("Este ajuste ya se registró con otros datos");
        assertThat(ajustes(c.cliente())).isEqualTo(1);
        assertThat(movimientos(c.cliente())).isEqualTo(movimientos);
        assertThat(ultimoNumeroDeAjuste(c.f().id())).isEqualTo(1L);
        assertCuenta(c);
    }

    @Test
    @DisplayName("S5: la repetida sigue valiendo aunque después una venta haya consumido crédito del cliente (no cambia lo que se imputó a mano)")
    void idempotenciaConImputacionesAutomaticasPosteriores() throws Exception {
        Caso c = caso();
        String cuerpo = pedido(clave(), "DESCUENTO", "100.00", "Bonificación del Dueño");
        JsonNode primero = json(ajustar(c.dueno(), c.cliente(), cuerpo).andExpect(status().isCreated()));
        vender(c, "60.00"); // consume 60 del Descuento

        JsonNode repetido = json(ajustar(c.dueno(), c.cliente(), cuerpo).andExpect(status().isOk()));

        assertThat(repetido.get("ajusteId")).isEqualTo(primero.get("ajusteId"));
        assertThat(repetido.get("imputaciones")).isEmpty();
    }

    // ================================================================== S6: rechazos

    @Test
    @DisplayName("S6: imputar más que la deuda actual de una partida es 409 IMPUTACION_EXCEDE_DEUDA con la deuda de esa partida; no guarda nada ni consume número")
    void imputacionQueExcedeLaDeuda() throws Exception {
        Caso c = caso();
        VentaSuelta venta = ventaEnLibro(c.f(), c.cliente(), "300.00");
        cobrar(c, "250.00", aComprobante(venta.comprobanteId(), "250.00"));
        long movimientos = movimientos(c.cliente());

        ResultActions respuesta = ajustar(c.dueno(), c.cliente(),
                pedido(clave(), "DESCUENTO", "100.00", "Bonificación del Dueño", aComprobante(venta.comprobanteId(), "100.00")));
        String crudo = respuesta.andExpect(status().isConflict()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode error = objectMapper.readTree(crudo);

        assertThat(claves(error)).containsExactlyInAnyOrder("status", "message", "timestamp", "motivo", "comprobantes");
        assertThat(error.get("status").asInt()).isEqualTo(409);
        assertThat(error.get("motivo").asText()).isEqualTo("IMPUTACION_EXCEDE_DEUDA");
        assertThat(error.get("comprobantes")).hasSize(1);
        assertThat(error.get("comprobantes").get(0).get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(error.get("comprobantes").get(0).get("ajusteId").isNull()).isTrue();
        assertThat(error.get("comprobantes").get(0).get("referencia").asText()).isEqualTo("Venta N.º " + venta.numero());
        assertThat(crudo).contains("\"pendiente\":50.00");
        assertThat(ajustes(c.cliente())).isZero();
        assertThat(movimientos(c.cliente())).isEqualTo(movimientos);
        assertThat(ultimoNumeroDeAjuste(c.f().id())).as("el número no se consumió").isNull();
        assertCuenta(c);
    }

    @Test
    @DisplayName("S6: imputar a un Recargo ya saldado es 409 con ese Recargo y su ajusteId")
    void imputarAUnRecargoSaldado() throws Exception {
        Caso c = caso();
        long recargo = recargo(c, "100.00").get("ajusteId").asLong();
        cobrar(c, "100.00", aRecargo(recargo, "100.00"));

        JsonNode error = json(ajustar(c.dueno(), c.cliente(),
                pedido(clave(), "DESCUENTO", "10.00", "Bonificación del Dueño", aRecargo(recargo, "10.00")))
                .andExpect(status().isConflict()));

        assertThat(error.get("comprobantes").get(0).get("ajusteId").asLong()).isEqualTo(recargo);
        assertThat(error.get("comprobantes").get(0).get("comprobanteId").isNull()).isTrue();
        assertThat(error.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("0");
        assertThat(ajustes(c.cliente())).isEqualTo(1);
    }

    @Test
    @DisplayName("S6: un pedido mal formado es 400 con un error por campo y no guarda nada ni consume número")
    void validaciones() throws Exception {
        Caso c = caso();
        VentaSuelta venta = ventaEnLibro(c.f(), c.cliente(), "300.00");
        String cuerpoSinNada = "{}";
        Map<String, String> casos = new java.util.LinkedHashMap<>();
        casos.put("clave", pedido("no-es-un-uuid", "RECARGO", "10.00", "Interés por mora"));
        casos.put("tipo", pedido(clave(), "OTRO", "10.00", "Interés por mora"));
        casos.put("importe", pedido(clave(), "RECARGO", "0", "Interés por mora"));
        casos.put("motivo", pedido(clave(), "RECARGO", "10.00", "cort"));
        casos.put("imputaciones", pedido(clave(), "RECARGO", "10.00", "Interés por mora", aComprobante(venta.comprobanteId(), "5")));
        for (Map.Entry<String, String> caso : casos.entrySet()) {
            JsonNode error = json(ajustar(c.dueno(), c.cliente(), caso.getValue()).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").has(caso.getKey())).as(caso.getKey()).isTrue();
        }

        JsonNode sumaMayor = json(ajustar(c.dueno(), c.cliente(), pedido(clave(), "DESCUENTO", "10.00", "Bonificación del Dueño",
                aComprobante(venta.comprobanteId(), "8"), aRecargo(99, "8"))).andExpect(status().isBadRequest()));
        JsonNode ambos = json(ajustar(c.dueno(), c.cliente(), pedido(clave(), "DESCUENTO", "10.00", "Bonificación del Dueño",
                "{\"comprobanteId\":" + venta.comprobanteId() + ",\"ajusteId\":99,\"importe\":5}"))
                .andExpect(status().isBadRequest()));
        JsonNode ninguno = json(ajustar(c.dueno(), c.cliente(), pedido(clave(), "DESCUENTO", "10.00", "Bonificación del Dueño",
                "{\"importe\":5}")).andExpect(status().isBadRequest()));
        JsonNode vacio = json(ajustar(c.dueno(), c.cliente(), cuerpoSinNada).andExpect(status().isBadRequest()));

        assertThat(sumaMayor.get("errors").get("imputaciones").asText()).isEqualTo("Lo imputado supera el importe del Descuento.");
        assertThat(ambos.get("errors").has("imputaciones[0].ajusteId")).isTrue();
        assertThat(ninguno.get("errors").has("imputaciones[0].comprobanteId")).isTrue();
        assertThat(claves(vacio.get("errors"))).contains("clave", "tipo", "importe", "motivo");
        assertThat(ajustes(c.cliente())).isZero();
        assertThat(ultimoNumeroDeAjuste(c.f().id())).isNull();
    }

    @Test
    @DisplayName("S6: un cliente desactivado es 422 y no guarda nada")
    void clienteDesactivado() throws Exception {
        Caso c = caso();
        jdbc.update("update cliente set activo = false where id = ?", c.cliente());

        JsonNode error = json(ajustar(c.dueno(), c.cliente(), pedido(clave(), "RECARGO", "10.00", "Interés por mora"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("message").asText()).isEqualTo("El cliente está desactivado.");
        assertThat(ajustes(c.cliente())).isZero();
        assertThat(ultimoNumeroDeAjuste(c.f().id())).isNull();
    }

    // ================================================================== S7: roles y aislamiento por ferretería

    @Test
    @DisplayName("S7: el Vendedor recibe 403 aunque el cuerpo no valide, sin consumir número; sin token, 401")
    void soloElDueno() throws Exception {
        Caso c = caso();

        ajustar(c.vendedor(), c.cliente(), pedido(clave(), "RECARGO", "10.00", "Interés por mora")).andExpect(status().isForbidden());
        ajustar(c.vendedor(), c.cliente(), "{}").andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url(c.cliente()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(pedido(clave(), "RECARGO", "10.00", "Interés por mora"))).andExpect(status().isUnauthorized());

        assertThat(ajustes(c.cliente())).isZero();
        assertThat(ultimoNumeroDeAjuste(c.f().id())).isNull();
    }

    @Test
    @DisplayName("S7: el cliente de otra ferretería (o inexistente) es 404 al ajustarlo; su cuenta, pendientes y extracto no se ven; la clave de A no choca en B")
    void aislamientoPorFerreteria() throws Exception {
        Caso a = caso();
        Caso b = caso();
        VentaSuelta ventaA = ventaEnLibro(a.f(), a.cliente(), "500.00");
        String clave = clave();
        JsonNode recargoA = json(ajustar(a.dueno(), a.cliente(), pedido(clave, "RECARGO", "100.00", "Interés por mora"))
                .andExpect(status().isCreated()));

        ajustar(b.dueno(), a.cliente(), pedido(clave, "RECARGO", "10.00", "Interés por mora")).andExpect(status().isNotFound());
        ajustar(b.dueno(), 999_999_999L, pedido(clave, "RECARGO", "10.00", "Interés por mora")).andExpect(status().isNotFound());
        getResult("/api/clientes/" + a.cliente() + "/cuenta-corriente/pendientes", b.dueno()).andExpect(status().isNotFound());
        getResult("/api/clientes/" + a.cliente() + "/cuenta-corriente/extracto", b.dueno()).andExpect(status().isNotFound());
        ResultActions imputandoAjeno = ajustar(b.dueno(), b.cliente(), pedido(clave(), "DESCUENTO", "10.00",
                "Bonificación del Dueño", aComprobante(ventaA.comprobanteId(), "10.00")));
        ResultActions imputandoRecargoAjeno = ajustar(b.dueno(), b.cliente(), pedido(clave(), "DESCUENTO", "10.00",
                "Bonificación del Dueño", aRecargo(recargoA.get("ajusteId").asLong(), "10.00")));

        imputandoAjeno.andExpect(status().isNotFound());
        imputandoRecargoAjeno.andExpect(status().isNotFound());
        assertThat(ajustes(a.cliente())).as("A conserva solo su Recargo").isEqualTo(1);
        assertThat(ajustes(b.cliente())).isZero();
        assertThat(ultimoNumeroDeAjuste(b.f().id())).isNull();
        assertThat(saldo(a.cliente())).isEqualByComparingTo("600.00");
        assertThat(pendientes(b).get("comprobantes")).isEmpty();
        // la misma clave en la otra ferretería es independiente: es un ajuste nuevo
        JsonNode propio = json(ajustar(b.dueno(), b.cliente(), pedido(clave, "RECARGO", "10.00", "Interés por mora"))
                .andExpect(status().isCreated()));
        assertThat(propio.get("numero").asLong()).isEqualTo(1L);
        assertCuenta(a);
        assertCuenta(b);
    }
}
