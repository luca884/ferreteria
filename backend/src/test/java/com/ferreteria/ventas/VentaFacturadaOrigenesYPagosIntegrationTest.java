package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La venta facturada por los otros caminos del mostrador (cu-07-venta-facturada S3, S4 y S5; sondas p2, p3 y p4 del
 * verificador) contra MySQL real y el simulador: desde un presupuesto, desde un remito con diferencia y a cuenta corriente
 * sale autorizada y con la hora del reloj en todo lo que mueve; con cuatro alícuotas, descuento global y pago mixto con
 * recargo, lo que va a ARCA suma lo cobrado; y el 422 del cliente sin datos fiscales a cuenta corriente no toca la cuenta
 * ni el numerador.
 */
class VentaFacturadaOrigenesYPagosIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";

    @Test
    @DisplayName("desde un presupuesto, desde un remito con diferencia y a cuenta corriente sale AUTORIZADO, y la venta y todos sus movimientos llevan la hora del reloj")
    void desdePresupuestoDesdeRemitoYACuentaCorriente() throws Exception {
        Instant instante = Instant.parse("2026-10-28T13:15:30.123456Z");
        reloj.fijar(instante);
        LocalDateTime enBuenosAires = LocalDateTime.ofInstant(instante, HoraArgentina.ZONA);
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "100");
        String vendedor = login(t.emailVendedor());

        long presupuestoId = presupuesto(vendedor, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        Map<String, Object> desdePresupuesto = mapa(clave(), null, "121.00", linea(a, "1", "121.00"));
        desdePresupuesto.put("formaPagoId", formaDe(t, "EFECTIVO"));
        desdePresupuesto.put("presupuestoId", presupuestoId);
        JsonNode delPresupuesto = json(postJson("/api/ventas", vendedor,
                objectMapper.writeValueAsString(desdePresupuesto)).andExpect(status().isCreated()));

        // El remito sacó 2 del stock; la venta lleva 3: la diferencia sale con la venta.
        long remitoId = remito(vendedor, a, "2", "121.00", "242.00").get("remitoId").asLong();
        Map<String, Object> desdeRemito = mapa(clave(), null, "363.00", linea(a, "3", "121.00"));
        desdeRemito.put("formaPagoId", formaDe(t, "EFECTIVO"));
        desdeRemito.put("remitoId", remitoId);
        JsonNode delRemito = json(postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(desdeRemito))
                .andExpect(status().isCreated()));

        activarFormaCuentaCorriente(t);
        long ri = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), "Av. Colón 2000");
        jdbc.update("update cliente set limite_cuenta_corriente = 1000 where id = ?", ri);
        Map<String, Object> aCuenta = mapa(clave(), null, "242.00", linea(a, "2", "121.00"));
        aCuenta.put("formaPagoId", formaDe(t, "CUENTA_CORRIENTE"));
        aCuenta.put("clienteId", ri);
        JsonNode aCuentaCorriente = json(postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(aCuenta))
                .andExpect(status().isCreated()));

        for (JsonNode venta : List.of(delPresupuesto, delRemito, aCuentaCorriente)) {
            assertThat(venta.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
            assertThat((BigDecimal) registroDe(venta.get("ventaId").asLong()).get("imp_total"))
                    .isEqualByComparingTo(venta.get("total").decimalValue());
            assertThat(LocalDateTime.parse(venta.get("fecha").asText())).isEqualTo(enBuenosAires);
        }
        assertThat(delPresupuesto.get("fiscal").get("letra").asText()).isEqualTo("B");
        assertThat(delPresupuesto.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(delRemito.get("fiscal").get("letra").asText()).isEqualTo("B");
        assertThat(delRemito.get("fiscal").get("numero").asLong()).isEqualTo(2);
        assertThat(aCuentaCorriente.get("fiscal").get("letra").asText()).isEqualTo("A");
        assertThat(aCuentaCorriente.get("fiscal").get("numero").asLong()).isEqualTo(1);
        List<LocalDateTime> fechas = new ArrayList<>();
        getJson("/api/articulos/" + a + "/movimientos-stock", login(t.emailDueno())).get("items")
                .forEach(m -> fechas.add(LocalDateTime.parse(m.get("fecha").asText())));
        // La venta del presupuesto, el remito, la diferencia de su venta y la venta a cuenta corriente.
        assertThat(fechas).hasSize(4).containsOnly(enBuenosAires);
        assertThat(stock(a)).isEqualByComparingTo("94");
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, ri))
                .isEqualByComparingTo("242.00");
        assertThat(wsfe.solicitudes()).isEqualTo(3);
    }

    @Test
    @DisplayName("cuatro alícuotas, descuento global de 7,33 % y pago mixto con recargo: AUTORIZADO y el ImpTotal es el total cobrado")
    void cuatroAlicuotasDescuentoYPagoMixtoConRecargo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long martillo = articulo(t, "Martillo", "UNIDAD", "121.00", "100");
        long panDulce = articulo(t, "Pan dulce", "UNIDAD", "110.50", "100", "IVA_10_5", true);
        long taladro = articulo(t, "Taladro", "UNIDAD", "999.99", "100", "IVA_27", true);
        long libro = articulo(t, "Libro", "UNIDAD", "333.33", "100", "IVA_0", true);
        jdbc.update("update forma_pago set recargo_porcentaje = 13.57 where id = ?", formaDe(t, "CREDITO"));
        // Con el 7,33 % de descuento: 336,39 + 102,40 + 926,69 + 617,79 = 1.983,27 sin recargo. Se pagan 1.000,00 en
        // efectivo y 983,27 con crédito, que suma el 13,57 % de recargo (133,43): 2.116,70.
        Map<String, Object> cuerpo = mapa(clave(), null, "2116.70", linea(martillo, "3", "121.00"),
                linea(panDulce, "1", "110.50"), linea(taladro, "1", "999.99"), linea(libro, "2", "333.33"));
        cuerpo.remove("formaPagoId");
        cuerpo.put("descuentoPorcentaje", new BigDecimal("7.33"));
        cuerpo.put("pagos", List.of(
                Map.of("formaPagoId", formaDe(t, "EFECTIVO"), "monto", new BigDecimal("1000.00")),
                Map.of("formaPagoId", formaDe(t, "CREDITO"), "monto", new BigDecimal("983.27"))));

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));

        long id = venta.get("ventaId").asLong();
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("2116.70");
        assertThat(venta.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(venta.get("fiscal").get("letra").asText()).isEqualTo("B");
        assertThat((BigDecimal) registroDe(id).get("imp_total")).isEqualByComparingTo("2116.70");
        assertThat(jdbc.queryForList("select a.iva_id from comprobante_fiscal_alicuota a join comprobante_fiscal r "
                + "on r.id = a.comprobante_fiscal_id where r.comprobante_id = ? order by a.iva_id", Integer.class, id))
                .containsExactly(3, 4, 5, 6);
    }

    @Test
    @DisplayName("un RI sin domicilio a cuenta corriente da 422 CLIENTE_SIN_DATOS_FISCALES sin tocar el saldo, el libro de la cuenta ni el numerador")
    void elRechazoACuentaCorrienteNoTocaLaCuenta() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        activarFormaCuentaCorriente(t);
        long ri = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), null);
        jdbc.update("update cliente set limite_cuenta_corriente = 1000 where id = ?", ri);
        Map<String, Object> aCuenta = mapa(clave(), null, "121.00", linea(a, "1", "121.00"));
        aCuenta.put("formaPagoId", formaDe(t, "CUENTA_CORRIENTE"));
        aCuenta.put("clienteId", ri);

        JsonNode rechazo = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(aCuenta))
                .andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CLIENTE_SIN_DATOS_FISCALES");
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, ri)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where ferreteria_id = ?",
                Long.class, t.id())).isZero();
        assertThat(jdbc.queryForList("select ultimo_numero from numerador_comprobante where ferreteria_id = ?", Long.class,
                t.id())).isEmpty();
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(registrosFiscales(t)).isZero();
        assertThat(wsfe.solicitudes()).isZero();
    }
}
