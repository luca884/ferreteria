package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * informacion-y-links (S4): los datos que faltaban en los detalles. El remito dice quién lo emitió y la venta que salió de
 * un presupuesto dice su número. Otra ferretería no ve ninguno de los dos (404, regla 3 de CLAUDE.md).
 */
class InformacionDeDocumentosIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";

    private String nombreDe(String email) {
        return jdbc.queryForObject("select nombre from usuario where email = ?", String.class, email);
    }

    private JsonNode vender(Tienda t, String campo, long id, String total, Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave(), null, total, lineas);
        cuerpo.put("formaPagoId", formaDe(t, "EFECTIVO"));
        cuerpo.put(campo, id);
        cuerpo.put("facturable", false);
        return json(postJson("/api/ventas", login(t.emailDueno()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));
    }

    @Test
    @DisplayName("el detalle del remito dice quién lo emitió; el listado no lo trae")
    void remitoConQuienLoEmitio() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String vendedor = login(t.emailVendedor());
        long r = remito(vendedor, a, "1", "121.00", "121.00").get("remitoId").asLong();

        JsonNode detalle = getJson("/api/remitos/" + r, login(t.emailDueno()));

        assertThat(detalle.get("usuario").asText()).isEqualTo(nombreDe(t.emailVendedor()));
        getJson("/api/remitos", vendedor).get("items").forEach(i -> assertThat(i.has("usuario")).isFalse());
    }

    @Test
    @DisplayName("la venta que salió de un presupuesto trae su número; la que no, no")
    void ventaConElNumeroDelPresupuesto() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String dueno = login(t.emailDueno());
        presupuesto(dueno, a, "1", "121.00", "121.00");
        JsonNode p = presupuesto(dueno, a, "1", "121.00", "121.00");
        long ventaId = vender(t, "presupuestoId", p.get("presupuestoId").asLong(), "121.00", linea(a, "1", "121.00"))
                .get("ventaId").asLong();
        long remitoId = remito(dueno, a, "1", "121.00", "121.00").get("remitoId").asLong();
        long deRemito = vender(t, "remitoId", remitoId, "121.00", linea(a, "1", "121.00")).get("ventaId").asLong();

        JsonNode venta = getJson("/api/ventas/" + ventaId, dueno);
        JsonNode ventaDeRemito = getJson("/api/ventas/" + deRemito, dueno);

        assertThat(venta.get("presupuestoId").asLong()).isEqualTo(p.get("presupuestoId").asLong());
        assertThat(venta.get("presupuestoNumero").asLong()).isEqualTo(p.get("numero").asLong());
        assertThat(ventaDeRemito.has("presupuestoNumero")).isFalse();
    }

    @Test
    @DisplayName("otra ferretería no ve el remito ni la venta con los datos nuevos: 404")
    void otraFerreteriaNoLosVe() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        Tienda otra = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String dueno = login(t.emailDueno());
        JsonNode p = presupuesto(dueno, a, "1", "121.00", "121.00");
        long ventaId = vender(t, "presupuestoId", p.get("presupuestoId").asLong(), "121.00", linea(a, "1", "121.00"))
                .get("ventaId").asLong();
        long remitoId = remito(dueno, a, "1", "121.00", "121.00").get("remitoId").asLong();
        String ajeno = login(otra.emailDueno());

        mvc.perform(get("/api/remitos/" + remitoId).header("Authorization", "Bearer " + ajeno))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ventas/" + ventaId).header("Authorization", "Bearer " + ajeno))
                .andExpect(status().isNotFound());
    }
}
