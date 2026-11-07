package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * arreglos-confusos (S5, #64): el detalle de un presupuesto pasado a venta (FACTURADO por dentro) trae la venta que lo
 * cobró y, si esa venta tiene factura válida, su letra y número fiscal, igual que el remito. Uno pendiente, aceptado,
 * rechazado o vencido, y el listado, no lo traen. Otra ferretería recibe 404.
 */
class PresupuestoVendidoEnIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";

    /** Cobra el presupuesto como el Vendedor; {@code facturable} false deja la venta sin factura. */
    private JsonNode venderPresupuesto(Tienda t, long presupuestoId, boolean facturable, String total, Linea... lineas)
            throws Exception {
        Map<String, Object> cuerpo = mapa(clave(), null, total, lineas);
        cuerpo.put("formaPagoId", formaDe(t, "EFECTIVO"));
        cuerpo.put("presupuestoId", presupuestoId);
        cuerpo.put("facturable", facturable);
        return json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));
    }

    private ResultActions cambiarEstado(String token, long id, String estado) throws Exception {
        return mvc.perform(patch("/api/presupuestos/" + id + "/estado").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"" + estado + "\"}"));
    }

    @Test
    @DisplayName("el presupuesto pasado a venta trae la venta (id y numero) y la factura B con su numero fiscal si la venta la tiene")
    void vendidoConFacturaValida() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "2", "121.00", "242.00").get("presupuestoId").asLong();
        JsonNode venta = venderPresupuesto(t, p, true, "242.00", linea(a, "2", "121.00"));
        // El simulador sale X: solo PRODUCCION AUTORIZADO con CAE es valido como factura.
        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?",
                venta.get("ventaId").asLong());

        JsonNode detalle = getJson("/api/presupuestos/" + p, token);

        assertThat(detalle.get("estado").asText()).isEqualTo("FACTURADO");
        JsonNode facturadoPor = detalle.get("facturadoPor");
        assertThat(facturadoPor.get("ventaId").asLong()).isEqualTo(venta.get("ventaId").asLong());
        assertThat(facturadoPor.get("numero").asLong()).isEqualTo(venta.get("numero").asLong());
        assertThat(facturadoPor.get("factura").asText()).isEqualTo("Factura B 00001-00000001");
    }

    @Test
    @DisplayName("si la venta no tiene factura valida (no facturable, o un X del simulador), facturadoPor trae la venta y no trae «factura»")
    void vendidoSinFacturaValida() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        JsonNode ventaNoFacturable = venderPresupuesto(t, p, false, "121.00", linea(a, "1", "121.00"));
        long p2 = presupuesto(token, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        JsonNode ventaX = venderPresupuesto(t, p2, true, "121.00", linea(a, "1", "121.00"));

        JsonNode sinFactura = getJson("/api/presupuestos/" + p, token).get("facturadoPor");
        JsonNode deLaX = getJson("/api/presupuestos/" + p2, token).get("facturadoPor");

        assertThat(sinFactura.get("ventaId").asLong()).isEqualTo(ventaNoFacturable.get("ventaId").asLong());
        assertThat(sinFactura.get("numero").asLong()).isEqualTo(ventaNoFacturable.get("numero").asLong());
        assertThat(sinFactura.has("factura")).isFalse();
        assertThat(deLaX.get("ventaId").asLong()).isEqualTo(ventaX.get("ventaId").asLong());
        assertThat(deLaX.has("factura")).isFalse();
    }

    @Test
    @DisplayName("un presupuesto pendiente, aceptado, rechazado o vencido no trae facturadoPor, ni en el detalle ni en el listado ni al cambiar el estado")
    void sinVenderNoTraeFacturadoPor() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String dueno = login(t.emailDueno());
        long pendiente = presupuesto(dueno, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        long aceptado = presupuesto(dueno, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        long rechazado = presupuesto(dueno, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        long vencido = presupuesto(dueno, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        long vendido = presupuesto(dueno, a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        venderPresupuesto(t, vendido, true, "121.00", linea(a, "1", "121.00"));
        jdbc.update("update presupuesto set vence = date_sub(curdate(), interval 30 day) where id = ?", vencido);

        JsonNode alAceptar = json(cambiarEstado(dueno, aceptado, "ACEPTADO").andExpect(status().isOk()));
        JsonNode alRechazar = json(cambiarEstado(dueno, rechazado, "RECHAZADO").andExpect(status().isOk()));

        assertThat(alAceptar.has("facturadoPor")).isFalse();
        assertThat(alRechazar.has("facturadoPor")).isFalse();
        for (long id : new long[] {pendiente, aceptado, rechazado, vencido}) {
            assertThat(getJson("/api/presupuestos/" + id, dueno).has("facturadoPor")).isFalse();
        }
        assertThat(getJson("/api/presupuestos/" + vendido, dueno).has("facturadoPor")).isTrue();
        getJson("/api/presupuestos", dueno).get("items").forEach(i -> assertThat(i.has("facturadoPor")).isFalse());
    }

    @Test
    @DisplayName("otra ferreteria recibe 404 en el presupuesto vendido y no ve la venta que lo cobro")
    void otraFerreteriaRecibe404() throws Exception {
        Tienda propia = tiendaQueFactura(RI);
        Tienda ajena = tiendaQueFactura(RI);
        long a = articulo(propia, "Martillo", "UNIDAD", "121.00", "10");
        long p = presupuesto(login(propia.emailVendedor()), a, "1", "121.00", "121.00").get("presupuestoId").asLong();
        venderPresupuesto(propia, p, false, "121.00", linea(a, "1", "121.00"));

        getResult("/api/presupuestos/" + p, login(ajena.emailDueno())).andExpect(status().isNotFound());
        assertThat(getJson("/api/presupuestos/" + p, login(propia.emailDueno())).get("facturadoPor").get("ventaId")
                .asLong()).isPositive();
    }
}
