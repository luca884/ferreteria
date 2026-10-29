package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.support.RelojControlableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La bandeja de pendientes y rechazadas en Ventas del Dueño (cu-07-contingencias S4, C1; CU-16 punto 7): el filtro
 * {@code fiscal}, el estado fiscal y {@code reimprimir} en la lista, y en el detalle los errores de ARCA tal cual con su
 * explicación, las observaciones de una autorizada, las acciones que valen hoy, la fecha nueva y quién y cuándo la dejó sin
 * factura. Solo del Dueño; otra ferretería no ve nada.
 */
class BandejaDeFacturasIntegrationTest extends FacturaDelDuenoTestBase {

    @Test
    @DisplayName("el filtro «fiscal» trae PENDIENTES (pendiente, en curso, en verificación y en revisión) o RECHAZADAS "
            + "(rechazada y fuera de fecha), más nuevas primero y junto con los otros filtros; otro valor es 400")
    void filtraLaBandeja() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long autorizada = ventaAutorizada(t);
        long revision = ventaAutorizada(t);
        long rechazada = ventaRechazada(t);
        long sinFacturaDelDueno = ventaRechazada(t);
        long fueraDeFecha = ventaPendiente(t, t.emailVendedor());
        fueraDeFecha(fueraDeFecha);
        long pendiente = ventaPendiente(t, t.emailVendedor());
        long noFacturable = json(venderA(t, null, false, clave(), "121.00",
                linea(articulo(t, "Clavo", "UNIDAD", "121.00", "10"), "1", "121.00")).andExpect(status().isCreated()))
                .get("ventaId").asLong();
        enRevision(revision);
        sinFactura(sinFacturaDelDueno, "Se fue sin esperar", usuario(t.emailDueno()), RelojControlableConfig.INICIO);
        String dueno = login(t.emailDueno());

        assertThat(ventaIds(getJson("/api/ventas?fiscal=PENDIENTES", dueno))).containsExactly(pendiente, revision);
        assertThat(ventaIds(getJson("/api/ventas?fiscal=RECHAZADAS", dueno))).containsExactly(fueraDeFecha, rechazada);
        assertThat(ventaIds(getJson("/api/ventas", dueno))).containsExactly(noFacturable, pendiente, fueraDeFecha,
                sinFacturaDelDueno, rechazada, revision, autorizada);
        long numeroRechazada = jdbc.queryForObject("select numero from comprobante where id = ?", Long.class, rechazada);
        assertThat(ventaIds(getJson("/api/ventas?fiscal=RECHAZADAS&numero=" + numeroRechazada, dueno)))
                .containsExactly(rechazada);
        assertThat(ventaIds(getJson("/api/ventas?fiscal=PENDIENTES&numero=" + numeroRechazada, dueno))).isEmpty();
        assertThat(getJson("/api/ventas?fiscal=RECHAZADAS", dueno).get("totalElements").asLong()).isEqualTo(2);
        getResult("/api/ventas?fiscal=TODAS", dueno).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("la lista lleva el estado fiscal y «reimprimir»: true solo si salió con ticket provisorio y ya está AUTORIZADO")
    void laListaMarcaReimprimir() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long provisoria = ventaPendiente(t, t.emailVendedor());
        long siguiente = ventaAutorizada(t);
        long pendiente = ventaPendiente(t, t.emailVendedor());
        assertThat(estadoFiscal(provisoria)).isEqualTo("AUTORIZADO");

        JsonNode items = getJson("/api/ventas", login(t.emailDueno())).get("items");

        assertThat(fiscalDe(items, provisoria).get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(fiscalDe(items, provisoria).get("reimprimir").asBoolean()).isTrue();
        assertThat(fiscalDe(items, siguiente).get("reimprimir").asBoolean()).isFalse();
        assertThat(fiscalDe(items, pendiente).get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(fiscalDe(items, pendiente).get("reimprimir").asBoolean()).isFalse();
        assertThat(detalle(t, provisoria).get("fiscal").get("reimprimir").asBoolean()).isTrue();
        assertThat(detalle(t, pendiente).get("fiscal").get("reimprimir").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("el detalle de una rechazada trae cada error de ARCA con su código y su texto tal cual, la explicación de "
            + "los conocidos (null si no), sin observaciones, con las dos acciones y sin pedir fecha nueva")
    void elDetalleDeUnaRechazada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t, null, new Obs(10015, "El receptor no está identificado."),
                new Obs(19999, "Un código que nadie explicó."));

        JsonNode fiscal = detalle(t, venta).get("fiscal");

        assertThat(fiscal.get("estado").asText()).isEqualTo("RECHAZADO");
        assertThat(fiscal.get("errores")).hasSize(2);
        assertThat(fiscal.get("errores").get(0).get("codigo").isInt()).isTrue();
        assertThat(fiscal.get("errores").get(0).get("codigo").asInt()).isEqualTo(10015);
        assertThat(fiscal.get("errores").get(0).get("mensaje").asText()).isEqualTo("El receptor no está identificado.");
        assertThat(fiscal.get("errores").get(0).get("explicacion").asText()).isNotBlank();
        assertThat(fiscal.get("errores").get(1).get("codigo").asInt()).isEqualTo(19999);
        assertThat(fiscal.get("errores").get(1).get("mensaje").asText()).isEqualTo("Un código que nadie explicó.");
        assertThat(fiscal.get("errores").get(1).has("explicacion")).isTrue();
        assertThat(fiscal.get("errores").get(1).get("explicacion").isNull()).isTrue();
        assertThat(fiscal.get("observaciones")).isEmpty();
        assertThat(textos(fiscal.get("acciones"))).containsExactly("REINTENTAR", "DEJAR_SIN_FACTURA");
        assertThat(fiscal.get("fechaNuevaRequerida").asBoolean()).isFalse();
        assertThat(fiscal.get("reimprimir").asBoolean()).isFalse();
        for (String nulo : List.of("motivoSinFactura", "sinFacturaPor", "sinFacturaEn")) {
            assertThat(fiscal.has(nulo)).as("fiscal.%s presente", nulo).isTrue();
            assertThat(fiscal.get(nulo).isNull()).as("fiscal.%s en null", nulo).isTrue();
        }
        // Lo de antes sigue igual: el estado de la rama 3 y la hoja.
        assertThat(fiscal.get("letra").asText()).isEqualTo("B");
        assertThat(fiscal.get("documento").get("letra").asText()).isEqualTo("X");
    }

    @Test
    @DisplayName("si la fecha de la venta ya no entra en ARCA, el detalle lo dice (fechaNuevaRequerida) y conserva las acciones")
    void elDetallePideFechaNueva() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        reloj.avanzar(Duration.ofDays(5));
        assertThat(detalle(t, venta).get("fiscal").get("fechaNuevaRequerida").asBoolean()).isFalse();

        reloj.avanzar(Duration.ofDays(1));

        JsonNode fiscal = detalle(t, venta).get("fiscal");
        assertThat(fiscal.get("fechaNuevaRequerida").asBoolean()).isTrue();
        assertThat(textos(fiscal.get("acciones"))).containsExactly("REINTENTAR", "DEJAR_SIN_FACTURA");
    }

    @Test
    @DisplayName("una autorizada trae sus observaciones (sin explicación) y ningún error ni acción; en revisión y sin "
            + "factura no tienen acciones, y la sin factura dice el motivo, quién y cuándo (hora de Buenos Aires)")
    void elDetalleDeLasDemas() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long autorizada = ventaAutorizada(t);
        long revision = ventaAutorizada(t);
        long sinFactura = ventaRechazada(t);
        jdbc.update("update comprobante_fiscal set observaciones = '[{\"code\":10063,\"msg\":\"El receptor no es un "
                + "contribuyente activo.\"}]' where comprobante_id = ?", autorizada);
        enRevision(revision);
        sinFactura(sinFactura, "El cliente no volvió", usuario(t.emailDueno()), RelojControlableConfig.INICIO);

        JsonNode a = detalle(t, autorizada).get("fiscal");
        JsonNode r = detalle(t, revision).get("fiscal");
        JsonNode s = detalle(t, sinFactura).get("fiscal");

        assertThat(a.get("errores")).isEmpty();
        assertThat(a.get("observaciones")).singleElement().satisfies(o -> {
            assertThat(o.get("codigo").asInt()).isEqualTo(10063);
            assertThat(o.get("mensaje").asText()).isEqualTo("El receptor no es un contribuyente activo.");
            assertThat(o.has("explicacion")).isFalse();
        });
        assertThat(a.get("acciones")).isEmpty();
        assertThat(r.get("estado").asText()).isEqualTo("CAE_NO_COINCIDE");
        assertThat(r.get("acciones")).isEmpty();
        assertThat(s.get("estado").asText()).isEqualTo("SIN_FACTURA");
        assertThat(s.get("acciones")).isEmpty();
        assertThat(s.get("motivoSinFactura").asText()).isEqualTo("El cliente no volvió");
        assertThat(s.get("sinFacturaPor").asText()).isEqualTo("Dueno Ventas");
        assertThat(s.get("sinFacturaEn").asText()).isEqualTo("2026-10-28T10:15:30");
        assertThat(s.get("errores").get(0).get("codigo").asInt()).isEqualTo(10015);
    }

    @Test
    @DisplayName("solo el Dueño: el Vendedor y el super admin reciben 403 en el filtro y en el detalle")
    void soloElDueno() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);

        for (String token : List.of(login(t.emailVendedor()), superAdmin())) {
            getResult("/api/ventas?fiscal=RECHAZADAS", token).andExpect(status().isForbidden());
            getResult("/api/ventas?fiscal=PENDIENTES", token).andExpect(status().isForbidden());
            getResult("/api/ventas/" + venta, token).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("otra ferretería no ve la bandeja de esta ni su detalle (404)")
    void otraFerreteriaNoVeNada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long rechazada = ventaRechazada(t);
        ventaPendiente(t, t.emailVendedor());
        Tienda otra = tiendaQueFactura(RI);
        String duenoDeLaOtra = login(otra.emailDueno());

        assertThat(ventaIds(getJson("/api/ventas?fiscal=RECHAZADAS", duenoDeLaOtra))).isEmpty();
        assertThat(ventaIds(getJson("/api/ventas?fiscal=PENDIENTES", duenoDeLaOtra))).isEmpty();
        assertThat(json(getResult("/api/ventas/" + rechazada, duenoDeLaOtra).andExpect(status().isNotFound()))
                .get("message").asText()).isEqualTo(VENTA_NO_ENCONTRADA);
    }

    private static JsonNode fiscalDe(JsonNode items, long venta) {
        for (JsonNode item : items) {
            if (item.get("ventaId").asLong() == venta) {
                return item.get("fiscal");
            }
        }
        throw new AssertionError("La venta " + venta + " no está en la lista");
    }

    private static List<String> textos(JsonNode array) {
        List<String> textos = new ArrayList<>();
        array.forEach(n -> textos.add(n.asText()));
        return textos;
    }
}
