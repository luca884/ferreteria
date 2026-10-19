package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.InvarianteCuenta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CC-005 a CC-016 contra MySQL real: la venta a cuenta corriente por HTTP. El libro, el saldo, los rechazos con su cuerpo
 * (cifras solo al Dueño), el 403 al Vendedor que autoriza un exceso, la idempotencia y el aislamiento por ferretería. Cada
 * escenario termina con la invariante saldo == Σ(debe − haber).
 */
class VentaCuentaCorrienteIntegrationTest extends VentasTestBase {

    private static final String VENTAS = "/api/ventas";
    private static final String MENSAJE_CLIENTE = "Elegí el cliente para vender a cuenta corriente.";

    /** Una ferretería con la caja abierta, la forma «Cuenta corriente» activa y un artículo de $ 1,00 con stock de sobra. */
    private record Escenario(Tienda tienda, long articulo, String dueno, String vendedor) {}

    private Escenario escenario() throws Exception {
        Tienda t = tienda();
        activarFormaCuentaCorriente(t);
        long articulo = articulo(t, "Tornillo", "UNIDAD", "1.00", "100000");
        return new Escenario(t, articulo, login(t.emailDueno()), login(t.emailVendedor()));
    }

    /** Un cliente de la ferretería con ese límite (null = sin cuenta corriente habilitada). */
    private long cliente(Tienda t, String limite) {
        String razon = "Cliente " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, ?)",
                t.id(), razon, limite == null ? null : new BigDecimal(limite));
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);
    }

    private void ponerLimite(long clienteId, String limite) {
        jdbc.update("update cliente set limite_cuenta_corriente = ? where id = ?",
                limite == null ? null : new BigDecimal(limite), clienteId);
    }

    /** El cuerpo de una venta de {@code unidades} del artículo a $ 1,00 pagada con la forma dada. */
    private Map<String, Object> cuerpo(Escenario e, String clave, int unidades, Long clienteId, Long formaPagoId) {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", e.articulo());
        renglon.put("cantidad", new BigDecimal(unidades));
        renglon.put("precioUnitario", new BigDecimal("1.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave);
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal(unidades));
        cuerpo.put("formaPagoId", formaPagoId);
        if (clienteId != null) {
            cuerpo.put("clienteId", clienteId);
        }
        return cuerpo;
    }

    private Map<String, Object> aCuenta(Escenario e, String clave, int unidades, Long clienteId) {
        return cuerpo(e, clave, unidades, clienteId, formaDe(e.tienda(), "CUENTA_CORRIENTE"));
    }

    private ResultActions vender(String token, Map<String, Object> cuerpo) throws Exception {
        return postJson(VENTAS, token, objectMapper.writeValueAsString(cuerpo));
    }

    private ResultActions vender(String token, Map<String, Object> cuerpo, boolean autorizaExceso) throws Exception {
        Map<String, Object> conFlag = new LinkedHashMap<>(cuerpo);
        conFlag.put("autorizaExceso", autorizaExceso);
        return vender(token, conFlag);
    }

    /** Deja al cliente debiendo {@code monto}: una venta a cuenta con límite de sobra, y después el límite que pide el escenario. */
    private void debiendo(Escenario e, long clienteId, int monto, String limiteFinal) throws Exception {
        ponerLimite(clienteId, "1000000.00");
        vender(e.dueno(), aCuenta(e, clave(), monto, clienteId)).andExpect(status().isCreated());
        ponerLimite(clienteId, limiteFinal);
    }

    private BigDecimal saldo(long clienteId) {
        return jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
    }

    private long movimientos(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class,
                clienteId);
    }

    private long ultimoNumero(Tienda t) {
        List<Long> numeros = jdbc.queryForList("select ultimo_numero from numerador_comprobante where ferreteria_id = ?",
                Long.class, t.id());
        return numeros.isEmpty() ? 0 : numeros.get(0);
    }

    private void invariante(long clienteId) {
        InvarianteCuenta.assertInvariante(jdbc, clienteId);
    }

    // ------------------------------------------------------------------ camino feliz

    @Test
    @DisplayName("forma única: $ 1.000 a cuenta es 201, un movimiento VENTA con debe 1.000, saldo 1.000 y el pago con medio CUENTA_CORRIENTE")
    void formaUnica() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");

        JsonNode venta = json(vender(e.vendedor(), aCuenta(e, clave(), 1000, c)).andExpect(status().isCreated()));

        assertThat(venta.get("pagos").get(0).get("medio").asText()).isEqualTo("CUENTA_CORRIENTE");
        Map<String, Object> mov = jdbc.queryForMap(
                "select tipo, debe, haber, comprobante_id, pago_id, usuario_id, excedio_limite, autorizado_por, ferreteria_id "
                        + "from movimiento_cuenta_cliente where cliente_id = ?", c);
        assertThat(mov.get("tipo")).isEqualTo("VENTA");
        assertThat((BigDecimal) mov.get("debe")).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) mov.get("haber")).isEqualByComparingTo("0");
        assertThat(((Number) mov.get("comprobante_id")).longValue()).isEqualTo(venta.get("ventaId").asLong());
        assertThat(mov.get("pago_id")).isNotNull();
        assertThat(((Number) mov.get("ferreteria_id")).longValue()).isEqualTo(e.tienda().id());
        assertThat(mov.get("autorizado_por")).isNull();
        assertThat(mov.get("excedio_limite")).isEqualTo(false);
        assertThat(saldo(c)).isEqualByComparingTo("1000.00");
        invariante(c);
    }

    @Test
    @DisplayName("pago mixto $ 3.000 = $ 1.000 efectivo + $ 2.000 a cuenta: solo la parte a cuenta suma deuda, y la caja los separa por medio")
    void mixto() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        Map<String, Object> cuerpo = cuerpo(e, clave(), 3000, c, null);
        cuerpo.put("pagos", List.of(
                Map.of("formaPagoId", formaDe(e.tienda(), "EFECTIVO"), "monto", new BigDecimal("1000.00")),
                Map.of("formaPagoId", formaDe(e.tienda(), "CUENTA_CORRIENTE"), "monto", new BigDecimal("2000.00"))));

        vender(e.vendedor(), cuerpo).andExpect(status().isCreated());

        assertThat(saldo(c)).isEqualByComparingTo("2000.00");
        assertThat(jdbc.queryForObject("select debe from movimiento_cuenta_cliente where cliente_id = ?", BigDecimal.class, c))
                .isEqualByComparingTo("2000.00");
        Map<String, BigDecimal> porMedio = new LinkedHashMap<>();
        jdbc.query("select medio, sum(monto) total from pago where ferreteria_id = ? group by medio",
                rs -> {
                    porMedio.put(rs.getString("medio"), rs.getBigDecimal("total"));
                }, e.tienda().id());
        assertThat(porMedio.get("EFECTIVO")).isEqualByComparingTo("1000.00");
        assertThat(porMedio.get("CUENTA_CORRIENTE")).isEqualByComparingTo("2000.00");
        invariante(c);
    }

    @Test
    @DisplayName("una venta NO facturable a cuenta suma deuda igual")
    void noFacturableSumaDeuda() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        Map<String, Object> cuerpo = aCuenta(e, clave(), 800, c);
        cuerpo.put("facturable", false);

        vender(e.vendedor(), cuerpo).andExpect(status().isCreated());

        assertThat(saldo(c)).isEqualByComparingTo("800.00");
        assertThat(movimientos(c)).isEqualTo(1);
        invariante(c);
    }

    // ------------------------------------------------------------------ cliente

    @Test
    @DisplayName("sin clienteId es 400 en clienteId con el mensaje exacto, y no cambia nada ni consume número")
    void sinCliente() throws Exception {
        Escenario e = escenario();
        long numeroAntes = ultimoNumero(e.tienda());

        JsonNode error = json(vender(e.vendedor(), aCuenta(e, clave(), 100, null)).andExpect(status().isBadRequest()));

        assertThat(error.get("errors").get("clienteId").asText()).isEqualTo(MENSAJE_CLIENTE);
        assertThat(comprobantes(e.tienda())).isZero();
        assertThat(ultimoNumero(e.tienda())).isEqualTo(numeroAntes);
    }

    @Test
    @DisplayName("un cliente de otra ferretería es 404 y no cambia nada; uno desactivado es 422")
    void clienteAjenoYDesactivado() throws Exception {
        Escenario e = escenario();
        Escenario otra = escenario();
        long ajeno = cliente(otra.tienda(), "5000.00");
        long desactivado = cliente(e.tienda(), "5000.00");
        jdbc.update("update cliente set activo = false where id = ?", desactivado);

        vender(e.vendedor(), aCuenta(e, clave(), 100, ajeno)).andExpect(status().isNotFound());
        vender(e.vendedor(), aCuenta(e, clave(), 100, desactivado)).andExpect(status().isUnprocessableEntity());

        assertThat(saldo(ajeno)).isEqualByComparingTo("0");
        assertThat(movimientos(ajeno)).isZero();
        assertThat(comprobantes(e.tienda())).isZero();
        invariante(ajeno);
        invariante(desactivado);
    }

    // ------------------------------------------------------------------ cuenta habilitada y límite

    @Test
    @DisplayName("límite NULL: 422 CUENTA_CORRIENTE_NO_HABILITADA para el Vendedor, para el Dueño y para el Dueño con autorización; sin cifras")
    void noHabilitada() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), null);
        String razon = jdbc.queryForObject("select razon_social from cliente where id = ?", String.class, c);

        JsonNode vendedor = json(vender(e.vendedor(), aCuenta(e, clave(), 100, c)).andExpect(status().isUnprocessableEntity()));
        JsonNode dueno = json(vender(e.dueno(), aCuenta(e, clave(), 100, c)).andExpect(status().isUnprocessableEntity()));
        JsonNode conFlag = json(vender(e.dueno(), aCuenta(e, clave(), 100, c), true)
                .andExpect(status().isUnprocessableEntity()));

        for (JsonNode rechazo : List.of(vendedor, dueno, conFlag)) {
            assertThat(rechazo.get("motivo").asText()).isEqualTo("CUENTA_CORRIENTE_NO_HABILITADA");
            assertThat(rechazo.get("message").asText()).isEqualTo(razon + " no tiene cuenta corriente habilitada.");
            assertThat(rechazo.has("cuentaCorriente")).isFalse();
        }
        assertThat(comprobantes(e.tienda())).isZero();
        assertThat(movimientos(c)).isZero();
        invariante(c);
    }

    @Test
    @DisplayName("límite 0 (habilitada sin crédito): el Vendedor recibe 422 LIMITE y el Dueño que autoriza vende")
    void limiteCero() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "0.00");

        JsonNode rechazo = json(vender(e.vendedor(), aCuenta(e, clave(), 100, c)).andExpect(status().isUnprocessableEntity()));
        vender(e.dueno(), aCuenta(e, clave(), 100, c), true).andExpect(status().isCreated());

        assertThat(rechazo.get("motivo").asText()).isEqualTo("LIMITE_CUENTA_CORRIENTE");
        assertThat(saldo(c)).isEqualByComparingTo("100.00");
        invariante(c);
    }

    @Test
    @DisplayName("el exceso del Vendedor sin autorización: 422 LIMITE, puedeAutorizar=false y el JSON no trae saldo, límite, disponible ni exceso")
    void excesoVendedorSinFlag() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        debiendo(e, c, 4000, "5000.00");

        ResultActions respuesta = vender(e.vendedor(), aCuenta(e, clave(), 2500, c)).andExpect(status().isUnprocessableEntity());
        String crudo = respuesta.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode rechazo = objectMapper.readTree(crudo);

        assertThat(rechazo.get("motivo").asText()).isEqualTo("LIMITE_CUENTA_CORRIENTE");
        assertThat(rechazo.get("message").asText()).isEqualTo(
                "Esta venta supera el límite de cuenta corriente del cliente. Pedile al Dueño que la autorice.");
        assertThat(rechazo.get("cuentaCorriente").get("puedeAutorizar").asBoolean()).isFalse();
        assertThat(rechazo.get("cuentaCorriente").size()).isEqualTo(1);
        assertThat(crudo).doesNotContain("\"saldo\"", "\"limite\"", "\"disponible\"", "\"exceso\"", "\"importe\"");
        assertThat(saldo(c)).isEqualByComparingTo("4000.00");
        assertThat(movimientos(c)).isEqualTo(1);
        invariante(c);
    }

    @Test
    @DisplayName("el Vendedor que manda autorizaExceso con exceso recibe 403 y no queda nada: ni venta, ni movimiento, ni número consumido")
    void vendedorConFlagYExceso() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        debiendo(e, c, 4000, "5000.00");
        long comprobantesAntes = comprobantes(e.tienda());
        long numeroAntes = ultimoNumero(e.tienda());

        vender(e.vendedor(), aCuenta(e, clave(), 2500, c), true).andExpect(status().isForbidden());

        assertThat(comprobantes(e.tienda())).isEqualTo(comprobantesAntes);
        assertThat(ultimoNumero(e.tienda())).isEqualTo(numeroAntes);
        assertThat(movimientos(c)).isEqualTo(1);
        assertThat(saldo(c)).isEqualByComparingTo("4000.00");
        invariante(c);
    }

    @Test
    @DisplayName("el exceso del Dueño sin autorizar: 422 LIMITE con puedeAutorizar y las cifras (límite 5.000, saldo 4.000, venta 2.500: exceso 1.500)")
    void excesoDuenoSinFlag() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        debiendo(e, c, 4000, "5000.00");

        JsonNode rechazo = json(vender(e.dueno(), aCuenta(e, clave(), 2500, c)).andExpect(status().isUnprocessableEntity()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("LIMITE_CUENTA_CORRIENTE");
        assertThat(rechazo.get("message").asText()).isEqualTo(
                "Esta venta supera el límite de cuenta corriente del cliente. Autorizala para seguir.");
        JsonNode cuenta = rechazo.get("cuentaCorriente");
        assertThat(cuenta.get("puedeAutorizar").asBoolean()).isTrue();
        assertThat(cuenta.get("saldo").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(cuenta.get("limite").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(cuenta.get("disponible").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(cuenta.get("importe").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(cuenta.get("exceso").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(saldo(c)).isEqualByComparingTo("4000.00");
        invariante(c);
    }

    @Test
    @DisplayName("el Dueño que autoriza el exceso: 201 y el movimiento queda con excedio_limite y autorizado_por = el Dueño; sin exceso no se marca")
    void duenoAutorizaElExceso() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        debiendo(e, c, 4000, "5000.00");
        long idDueno = jdbc.queryForObject("select id from usuario where email = ?", Long.class, e.tienda().emailDueno());

        vender(e.dueno(), aCuenta(e, clave(), 2500, c), true).andExpect(status().isCreated());

        Map<String, Object> excedido = jdbc.queryForMap(
                "select excedio_limite, autorizado_por from movimiento_cuenta_cliente where cliente_id = ? order by id desc limit 1",
                c);
        assertThat(excedido.get("excedio_limite")).isEqualTo(true);
        assertThat(((Number) excedido.get("autorizado_por")).longValue()).isEqualTo(idDueno);
        assertThat(saldo(c)).isEqualByComparingTo("6500.00");
        invariante(c);

        long otro = cliente(e.tienda(), "5000.00");
        vender(e.dueno(), aCuenta(e, clave(), 100, otro), true).andExpect(status().isCreated());
        Map<String, Object> sinExceso = jdbc.queryForMap(
                "select excedio_limite, autorizado_por from movimiento_cuenta_cliente where cliente_id = ?", otro);
        assertThat(sinExceso.get("excedio_limite")).isEqualTo(false);
        assertThat(sinExceso.get("autorizado_por")).isNull();
        invariante(otro);
    }

    @Test
    @DisplayName("el borde: límite 5.000 y saldo 4.000 aceptan 1.000 justos; 1.001 es 422 LIMITE")
    void borde() throws Exception {
        Escenario e = escenario();
        long justo = cliente(e.tienda(), "5000.00");
        long pasado = cliente(e.tienda(), "5000.00");
        debiendo(e, justo, 4000, "5000.00");
        debiendo(e, pasado, 4000, "5000.00");

        vender(e.vendedor(), aCuenta(e, clave(), 1000, justo)).andExpect(status().isCreated());
        JsonNode rechazo = json(vender(e.vendedor(), aCuenta(e, clave(), 1001, pasado))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("LIMITE_CUENTA_CORRIENTE");
        assertThat(saldo(justo)).isEqualByComparingTo("5000.00");
        assertThat(saldo(pasado)).isEqualByComparingTo("4000.00");
        invariante(justo);
        invariante(pasado);
    }

    // ------------------------------------------------------------------ forma inactiva y precedencia

    @Test
    @DisplayName("con la forma «Cuenta corriente» inactiva (como viene sembrada) es 400 «La forma de pago no es válida.»")
    void formaInactiva() throws Exception {
        Tienda t = tienda();
        long articulo = articulo(t, "Tornillo", "UNIDAD", "1.00", "1000");
        Escenario e = new Escenario(t, articulo, login(t.emailDueno()), login(t.emailVendedor()));
        long c = cliente(t, "5000.00");

        JsonNode error = json(vender(e.dueno(), aCuenta(e, clave(), 100, c)).andExpect(status().isBadRequest()));

        assertThat(error.get("errors").get("formaPagoId").asText()).isEqualTo("La forma de pago no es válida.");
        assertThat(movimientos(c)).isZero();
    }

    @Test
    @DisplayName("los rechazos previos ganan al del límite: artículo inexistente 404, precios 409, pagos 400 y sin caja 422")
    void rechazosPreviosPrimero() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "0.00"); // cualquier venta a cuenta se pasaría del límite

        Map<String, Object> inexistente = aCuenta(e, clave(), 10, c);
        ((List<Map<String, Object>>) inexistente.get("renglones")).get(0).put("articuloId", 987654321L);
        vender(e.vendedor(), inexistente).andExpect(status().isNotFound());

        Map<String, Object> precios = aCuenta(e, clave(), 10, c);
        ((List<Map<String, Object>>) precios.get("renglones")).get(0).put("precioUnitario", new BigDecimal("0.50"));
        assertThat(json(vender(e.vendedor(), precios).andExpect(status().isConflict())).get("motivo").asText())
                .isEqualTo("PRECIOS_CAMBIADOS");

        Map<String, Object> pagos = aCuenta(e, clave(), 10, c);
        pagos.put("pagos", List.of(Map.of("formaPagoId", formaDe(e.tienda(), "CUENTA_CORRIENTE"), "monto", new BigDecimal("3.00"))));
        pagos.remove("formaPagoId");
        assertThat(json(vender(e.vendedor(), pagos).andExpect(status().isBadRequest())).get("errors").get("pagos").asText())
                .isEqualTo("Los pagos tienen que sumar el total de la venta.");

        assertThat(movimientos(c)).isZero();
        invariante(c);

        Tienda sinCaja = tiendaSinCaja(); // sin caja abierta no se vende, ni a cuenta, aunque el cliente se pase del límite
        activarFormaCuentaCorriente(sinCaja);
        long articuloSinCaja = articulo(sinCaja, "Tornillo", "UNIDAD", "1.00", "1000");
        Escenario cerrada = new Escenario(sinCaja, articuloSinCaja, login(sinCaja.emailDueno()), login(sinCaja.emailVendedor()));
        long cc = cliente(sinCaja, "0.00");
        assertThat(json(vender(cerrada.vendedor(), aCuenta(cerrada, clave(), 10, cc))
                .andExpect(status().isUnprocessableEntity())).get("motivo").asText()).isEqualTo("CAJA_CERRADA");
        assertThat(movimientos(cc)).isZero();
        invariante(cc);
    }

    // ------------------------------------------------------------------ idempotencia

    @Test
    @DisplayName("repetir la misma venta es 200 con la original y sin un segundo movimiento; varíe o no el autorizaExceso")
    void claveRepetida() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        String clave = clave();
        Map<String, Object> cuerpo = aCuenta(e, clave, 1000, c);
        JsonNode original = json(vender(e.vendedor(), cuerpo).andExpect(status().isCreated()));

        JsonNode repetida = json(vender(e.vendedor(), cuerpo).andExpect(status().isOk()));
        JsonNode conFlag = json(vender(e.dueno(), cuerpo, true).andExpect(status().isOk()));

        assertThat(repetida.get("ventaId").asLong()).isEqualTo(original.get("ventaId").asLong());
        assertThat(conFlag.get("ventaId").asLong()).isEqualTo(original.get("ventaId").asLong());
        assertThat(movimientos(c)).isEqualTo(1);
        assertThat(saldo(c)).isEqualByComparingTo("1000.00");
        invariante(c);
    }

    @Test
    @DisplayName("la misma clave con otro cliente o con otro reparto de pagos es 422 CLAVE_CON_OTRO_CONTENIDO, y no suma nada")
    void claveConOtroContenido() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        long otro = cliente(e.tienda(), "5000.00");
        String clave = clave();
        vender(e.vendedor(), aCuenta(e, clave, 1000, c)).andExpect(status().isCreated());

        JsonNode otroCliente = json(vender(e.vendedor(), aCuenta(e, clave, 1000, otro)).andExpect(status().isUnprocessableEntity()));
        Map<String, Object> mixto = cuerpo(e, clave, 1000, c, null);
        mixto.put("pagos", List.of(
                Map.of("formaPagoId", formaDe(e.tienda(), "EFECTIVO"), "monto", new BigDecimal("400.00")),
                Map.of("formaPagoId", formaDe(e.tienda(), "CUENTA_CORRIENTE"), "monto", new BigDecimal("600.00"))));
        JsonNode otroReparto = json(vender(e.vendedor(), mixto).andExpect(status().isUnprocessableEntity()));

        assertThat(otroCliente.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(otroReparto.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(movimientos(c)).isEqualTo(1);
        assertThat(movimientos(otro)).isZero();
        assertThat(saldo(c)).isEqualByComparingTo("1000.00");
        invariante(c);
        invariante(otro);
    }

    @Test
    @DisplayName("con el cupo agotado, la misma clave con OTRO contenido que además se pasa del límite es 422 CLAVE_CON_OTRO_CONTENIDO y no LIMITE_CUENTA_CORRIENTE; con el mismo contenido, 200")
    void claveConOtroContenidoYExcesoDeLimite() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        debiendo(e, c, 4000, "5000.00");
        String clave = clave();
        Map<String, Object> original = aCuenta(e, clave, 1000, c); // llega justo al límite: saldo 5.000
        JsonNode creada = json(vender(e.vendedor(), original).andExpect(status().isCreated()));

        // La clave ya está registrada: el contenido distinto gana al chequeo del cupo, también para el Dueño con el flag.
        JsonNode vendedor = json(vender(e.vendedor(), aCuenta(e, clave, 1001, c)).andExpect(status().isUnprocessableEntity()));
        JsonNode dueno = json(vender(e.dueno(), aCuenta(e, clave, 2500, c), true).andExpect(status().isUnprocessableEntity()));
        JsonNode mismoContenido = json(vender(e.vendedor(), original).andExpect(status().isOk()));

        assertThat(vendedor.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(dueno.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(vendedor.has("cuentaCorriente")).isFalse();
        assertThat(dueno.has("cuentaCorriente")).isFalse();
        assertThat(mismoContenido.get("ventaId").asLong()).isEqualTo(creada.get("ventaId").asLong());
        assertThat(movimientos(c)).isEqualTo(2); // la deuda previa y la venta original: los rechazos no asientan nada
        assertThat(saldo(c)).isEqualByComparingTo("5000.00");
        invariante(c);
    }

    // ------------------------------------------------------------------ aislamiento por ferretería

    @Test
    @DisplayName("una venta a cuenta en una ferretería no mueve el saldo ni el libro de un cliente de otra; los movimientos llevan su ferreteria_id")
    void aislamientoPorFerreteria() throws Exception {
        Escenario a = escenario();
        Escenario b = escenario();
        long deA = cliente(a.tienda(), "5000.00");
        long deB = cliente(b.tienda(), "5000.00");

        vender(a.vendedor(), aCuenta(a, clave(), 700, deA)).andExpect(status().isCreated());
        vender(b.vendedor(), aCuenta(b, clave(), 300, deB)).andExpect(status().isCreated());

        assertThat(saldo(deA)).isEqualByComparingTo("700.00");
        assertThat(saldo(deB)).isEqualByComparingTo("300.00");
        List<Long> ferreteriasDeA = jdbc.queryForList(
                "select ferreteria_id from movimiento_cuenta_cliente where cliente_id = ?", Long.class, deA);
        List<Long> ferreteriasDeB = jdbc.queryForList(
                "select ferreteria_id from movimiento_cuenta_cliente where cliente_id = ?", Long.class, deB);
        assertThat(ferreteriasDeA).containsExactly(a.tienda().id());
        assertThat(ferreteriasDeB).containsExactly(b.tienda().id());
        invariante(deA);
        invariante(deB);
    }
}
