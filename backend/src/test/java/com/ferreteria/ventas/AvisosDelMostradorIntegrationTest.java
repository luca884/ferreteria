package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.RelojControlableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los avisos de facturación del mostrador del Dueño (cu-07-contingencias S6, C4; CU-16 punto 8), por HTTP contra MySQL
 * real y el simulador: cuántas rechazadas, pendientes de CAE desde hace más de 30 minutos (S11) y en revisión, y el texto
 * del bloqueo de ARCA de la rama 6 (C4), el mismo que muestra «Facturación». Solo el Dueño; de su ferretería.
 */
class AvisosDelMostradorIntegrationTest extends FacturaDelDuenoTestBase {

    private static final String URL = "/api/facturacion/avisos-mostrador";

    @Test
    @DisplayName("cuenta las rechazadas (y fuera de fecha), las pendientes de más de 30 minutos y las en revisión; sin factura, "
            + "autorizadas y reemplazadas no cuentan")
    void cuentaLoQueHayQueRevisar() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        ventaAutorizada(t);
        long revision = ventaAutorizada(t);
        ventaRechazada(t);
        long sinFactura = ventaRechazada(t);
        String dueno = login(t.emailDueno());
        // La rechazada que se reintentó queda como historia, reemplazada por una autorizada.
        reintentar(dueno, ventaRechazada(t), "{\"fechaDeHoy\": false}").andExpect(status().isOk());
        long fueraDeFecha = ventaPendiente(t, t.emailVendedor());
        fueraDeFecha(fueraDeFecha);
        ventaPendiente(t, t.emailVendedor());
        enRevision(revision);
        sinFactura(sinFactura, "Se fue", usuario(t.emailDueno()), RelojControlableConfig.INICIO);

        JsonNode recien = getJson(URL, dueno);
        reloj.avanzar(Duration.ofMinutes(30));
        JsonNode justo = getJson(URL, dueno);
        reloj.avanzar(Duration.ofSeconds(1));
        JsonNode vieja = getJson(URL, dueno);

        assertThat(recien.get("rechazadas").asInt()).isEqualTo(2);
        assertThat(recien.get("pendientesViejas").asInt()).isZero();
        assertThat(recien.get("enRevision").asInt()).isEqualTo(1);
        assertThat(recien.has("bloqueo")).isTrue();
        assertThat(recien.get("bloqueo").isNull()).isTrue();
        assertThat(recien.size()).isEqualTo(4);
        assertThat(justo.get("pendientesViejas").asInt()).isZero();
        assertThat(vieja.get("pendientesViejas").asInt()).isEqualTo(1);
        assertThat(vieja.get("rechazadas").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("A11: un reintento con la fecha de la venta que queda pendiente cuenta los 30 minutos desde el reintento, "
            + "no desde la venta")
    void unReintentoCuentaDesdeElReintento() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        String dueno = login(t.emailDueno());
        reloj.avanzar(Duration.ofHours(2));
        wsfe.fallas().programar(Falla.NO_LLEGA);
        reintentar(dueno, venta, "{\"fechaDeHoy\": false}").andExpect(status().isOk());
        assertThat(registrosDe(venta)).hasSize(2);
        assertThat(registrosDe(venta).get(1)).containsEntry("estado", "PENDIENTE")
                .containsEntry("registrado_en", registrosDe(venta).get(0).get("registrado_en"));

        JsonNode recien = getJson(URL, dueno);
        reloj.avanzar(Duration.ofMinutes(30));
        JsonNode justo = getJson(URL, dueno);
        reloj.avanzar(Duration.ofSeconds(1));
        JsonNode vieja = getJson(URL, dueno);

        assertThat(recien.get("pendientesViejas").asInt()).isZero();
        assertThat(justo.get("pendientesViejas").asInt()).isZero();
        assertThat(vieja.get("pendientesViejas").asInt()).isOne();
    }

    @Test
    @DisplayName("con la facturación bloqueada por ARCA trae el texto del bloqueo de «Facturación», tal cual")
    void traeElBloqueo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        jdbc.update("update config_arca set bloqueo_codigo = 'cms.cert.expired', bloqueo_mensaje = 'Certificado expirado', "
                + "bloqueado_en = now(6) where ferreteria_id = ?", t.id());
        String dueno = login(t.emailDueno());

        JsonNode avisos = getJson(URL, dueno);

        assertThat(avisos.get("bloqueo").asText()).isEqualTo("ARCA rechazó el certificado: Certificado expirado "
                + "(cms.cert.expired). La facturación está frenada hasta que cambies el certificado o pruebes la conexión.");
        assertThat(avisos.get("bloqueo").asText())
                .isEqualTo(getJson("/api/facturacion/configuracion", dueno).get("avisos").get(0).asText());
    }

    @Test
    @DisplayName("una ferretería sin configuración de ARCA no tiene avisos; otra ferretería no ve los de esta")
    void sinConfiguracionYAislamiento() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        ventaRechazada(t);
        Tienda sinArca = tiendaConDatosFiscales(RI);
        Tienda otra = tiendaQueFactura(RI);

        for (Tienda sinAvisos : List.of(sinArca, otra)) {
            JsonNode avisos = getJson(URL, login(sinAvisos.emailDueno()));
            assertThat(avisos.get("rechazadas").asInt()).isZero();
            assertThat(avisos.get("pendientesViejas").asInt()).isZero();
            assertThat(avisos.get("enRevision").asInt()).isZero();
            assertThat(avisos.get("bloqueo").isNull()).isTrue();
        }
        assertThat(getJson(URL, login(t.emailDueno())).get("rechazadas").asInt()).isOne();
    }

    @Test
    @DisplayName("solo el Dueño: el Vendedor y el super admin reciben 403")
    void soloElDueno() throws Exception {
        Tienda t = tiendaQueFactura(RI);

        getResult(URL, login(t.emailVendedor())).andExpect(status().isForbidden());
        getResult(URL, superAdmin()).andExpect(status().isForbidden());
    }
}
