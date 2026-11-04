package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La fecha de la primera respuesta es la misma que la de la repetida y la del GET. En Linux el reloj de Java da
 * nanosegundos y MySQL guarda microsegundos: sin el recorte de {@code HoraArgentina.enMicrosegundos} la primera respuesta
 * traia nanosegundos que la base no guarda (en la Mac este test pasa igual).
 */
class FechaDeLaRespuestaIntegrationTest extends VentasTestBase {

    @Test
    @DisplayName("venta: la fecha de la primera respuesta es la de la repetida y la del GET")
    void venta() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long art = articulo(t, "Reloj", "UNIDAD", "100.00", "10");
        String clave = clave();

        JsonNode primera = json(vender(dueno, clave, "100.00", linea(art, "1", "100.00")));
        JsonNode repetida = json(vender(dueno, clave, "100.00", linea(art, "1", "100.00")));
        JsonNode get = getJson("/api/ventas/" + primera.get("ventaId").asLong(), dueno);

        assertThat(repetida.get("fecha").asText()).isEqualTo(primera.get("fecha").asText());
        assertThat(get.get("fecha").asText()).isEqualTo(primera.get("fecha").asText());
    }

    @Test
    @DisplayName("remito y presupuesto: la fecha de la respuesta es la del GET")
    void remitoYPresupuesto() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long art = articulo(t, "Reloj", "UNIDAD", "100.00", "10");

        JsonNode remito = remito(dueno, art, "1", "100.00", "100.00");
        JsonNode remitoGet = getJson("/api/remitos/" + remito.get("remitoId").asLong(), dueno);
        JsonNode presu = presupuesto(dueno, art, "1", "100.00", "100.00");
        JsonNode presuGet = getJson("/api/presupuestos/" + presu.get("presupuestoId").asLong(), dueno);

        assertThat(remitoGet.get("fecha").asText()).isEqualTo(remito.get("fecha").asText());
        assertThat(presuGet.get("fecha").asText()).isEqualTo(presu.get("fecha").asText());
    }
}
