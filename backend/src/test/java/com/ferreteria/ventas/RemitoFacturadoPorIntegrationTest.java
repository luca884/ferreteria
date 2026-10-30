package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * cu-07-remito-presupuesto-imprimible (S2): el detalle de un remito FACTURADO trae la venta que lo facturó y, si esa venta
 * tiene factura válida, su letra y número fiscal. Un remito emitido o anulado, y el listado, no lo traen.
 */
class RemitoFacturadoPorIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";

    /** Cobra el remito como el Vendedor; {@code facturable} false deja la venta sin factura. */
    private JsonNode venderRemito(Tienda t, long remitoId, boolean facturable, String total, Linea... lineas)
            throws Exception {
        Map<String, Object> cuerpo = mapa(clave(), null, total, lineas);
        cuerpo.put("formaPagoId", formaDe(t, "EFECTIVO"));
        cuerpo.put("remitoId", remitoId);
        cuerpo.put("facturable", facturable);
        return json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));
    }

    @Test
    @DisplayName("el remito facturado trae la venta (id y numero) y la factura B con su numero fiscal si la venta la tiene")
    void facturadoConFacturaValida() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "2", "121.00", "242.00").get("remitoId").asLong();
        JsonNode venta = venderRemito(t, r, true, "242.00", linea(a, "2", "121.00"));
        // El simulador sale X: solo PRODUCCION AUTORIZADO con CAE es valido como factura.
        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?",
                venta.get("ventaId").asLong());

        JsonNode detalle = getJson("/api/remitos/" + r, token);

        assertThat(detalle.get("estado").asText()).isEqualTo("FACTURADO");
        JsonNode facturadoPor = detalle.get("facturadoPor");
        assertThat(facturadoPor.get("ventaId").asLong()).isEqualTo(venta.get("ventaId").asLong());
        assertThat(facturadoPor.get("numero").asLong()).isEqualTo(venta.get("numero").asLong());
        assertThat(facturadoPor.get("factura").asText()).isEqualTo("Factura B 00001-00000001");
    }

    @Test
    @DisplayName("si la venta no tiene factura valida (un X del simulador o sin registro fiscal), facturadoPor trae la venta y no trae «factura»")
    void facturadoSinFacturaValida() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "1", "121.00", "121.00").get("remitoId").asLong();
        JsonNode ventaX = venderRemito(t, r, true, "121.00", linea(a, "1", "121.00"));
        long r2 = remito(token, a, "1", "121.00", "121.00").get("remitoId").asLong();
        JsonNode ventaSinRegistro = venderRemito(t, r2, false, "121.00", linea(a, "1", "121.00"));

        JsonNode deLaX = getJson("/api/remitos/" + r, token).get("facturadoPor");
        JsonNode sinRegistro = getJson("/api/remitos/" + r2, token).get("facturadoPor");

        assertThat(deLaX.get("ventaId").asLong()).isEqualTo(ventaX.get("ventaId").asLong());
        assertThat(deLaX.has("factura")).isFalse();
        assertThat(sinRegistro.get("ventaId").asLong()).isEqualTo(ventaSinRegistro.get("ventaId").asLong());
        assertThat(sinRegistro.has("factura")).isFalse();
    }

    @Test
    @DisplayName("un remito emitido o anulado no trae facturadoPor, ni en el detalle ni en el listado ni al anular")
    void emitidoYAnuladoSinFacturadoPor() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String dueno = login(t.emailDueno());
        long emitido = remito(dueno, a, "1", "121.00", "121.00").get("remitoId").asLong();
        long anulado = remito(dueno, a, "1", "121.00", "121.00").get("remitoId").asLong();
        long facturado = remito(dueno, a, "1", "121.00", "121.00").get("remitoId").asLong();
        venderRemito(t, facturado, true, "121.00", linea(a, "1", "121.00"));

        ResultActions anulacion = mvc.perform(patch("/api/remitos/" + anulado + "/anular")
                .header("Authorization", "Bearer " + dueno)).andExpect(status().isOk());

        assertThat(json(anulacion).has("facturadoPor")).isFalse();
        assertThat(getJson("/api/remitos/" + emitido, dueno).has("facturadoPor")).isFalse();
        assertThat(getJson("/api/remitos/" + anulado, dueno).has("facturadoPor")).isFalse();
        getJson("/api/remitos", dueno).get("items").forEach(i -> assertThat(i.has("facturadoPor")).isFalse());
    }
}
