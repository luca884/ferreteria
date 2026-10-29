package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Llegó el CAE» (cu-07-contingencias S6, C7; CU-07 «Reimprimir», S14), por HTTP contra MySQL real y el simulador: cada
 * usuario ve las ventas que cobró en la caja abierta, que salieron con ticket provisorio y ya están AUTORIZADO, y pide su
 * hoja con {@code GET /api/ventas/{id}/factura}. El Vendedor no ve nada más: el cierre de caja sigue ciego.
 */
class ProvisoriasAutorizadasIntegrationTest extends FacturaDelDuenoTestBase {

    private static final String URL = "/api/ventas/provisorias-autorizadas";

    /**
     * Las ventas de la caja: en la caja cerrada, una provisoria del Vendedor que ya se autorizó; en la abierta, una
     * provisoria de cada uno (el Vendedor, otro Vendedor y el Dueño), una del Vendedor que salió con su CAE y otra
     * provisoria del Vendedor que sigue pendiente. La venta que no llega a ARCA deja la serie con su pendiente: la
     * siguiente la manda primero (FIFO).
     */
    private record Caja(Tienda t, String otro, long deLaCajaCerrada, long delVendedor, long delOtro, long delDueno,
            long conSuCae, long pendiente) {}

    private Caja armar() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        String otro = otroVendedor(t);
        long deLaCajaCerrada = ventaPendiente(t, t.emailVendedor());
        ventaAutorizada(t);
        assertThat(estadoFiscal(deLaCajaCerrada)).isEqualTo("AUTORIZADO");
        jdbc.update("update caja set abierta = null, fecha_cierre = now(6) where ferreteria_id = ?", t.id());
        cajaAbiertaEl(t.id(), LocalDateTime.now(reloj));

        long delVendedor = ventaPendiente(t, t.emailVendedor());
        long delOtro = cobrarPendiente(t, otro);
        long delDueno = cobrarPendiente(t, t.emailDueno());
        long conSuCae = ventaAutorizada(t);
        long pendiente = ventaPendiente(t, t.emailVendedor());
        for (long venta : List.of(delVendedor, delOtro, delDueno)) {
            assertThat(estadoFiscal(venta)).isEqualTo("AUTORIZADO");
        }
        return new Caja(t, otro, deLaCajaCerrada, delVendedor, delOtro, delDueno, conSuCae, pendiente);
    }

    /** Con la serie trabada por una pendiente que no llega: esta queda PENDIENTE sin enviarse, y sale provisoria. */
    private long cobrarPendiente(Tienda t, String email) throws Exception {
        return ventaPendiente(t, email);
    }

    @Test
    @DisplayName("cada uno ve solo sus ventas de la caja abierta que salieron provisorias y ya tienen CAE, con su número y "
            + "su factura")
    void cadaUnoVeLasSuyas() throws Exception {
        Caja c = armar();
        long numero = jdbc.queryForObject("select numero from comprobante where id = ?", Long.class, c.delVendedor());

        JsonNode delVendedor = getJson(URL, login(c.t().emailVendedor()));

        assertThat(ventaIdsDe(delVendedor)).containsExactly(c.delVendedor());
        assertThat(delVendedor.get(0).get("numero").asLong()).isEqualTo(numero);
        assertThat(delVendedor.get(0).get("factura").asText()).isEqualTo("X · SIMULADO — sin validez fiscal");
        assertThat(delVendedor.get(0).size()).isEqualTo(3);
        assertThat(ventaIdsDe(getJson(URL, login(c.otro())))).containsExactly(c.delOtro());
        assertThat(ventaIdsDe(getJson(URL, login(c.t().emailDueno())))).containsExactly(c.delDueno());
    }

    @Test
    @DisplayName("una factura válida de producción se anuncia con su letra y su número")
    void laFacturaValidaDiceSuLetraYSuNumero() throws Exception {
        Caja c = armar();
        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?", c.delVendedor());
        long numero = jdbc.queryForObject("select numero from comprobante_fiscal where comprobante_id = ?", Long.class,
                c.delVendedor());

        JsonNode lista = getJson(URL, login(c.t().emailVendedor()));

        assertThat(lista.get(0).get("factura").asText()).isEqualTo("Factura B " + String.format("00001-%08d", numero));
    }

    @Test
    @DisplayName("sin caja abierta, la lista está vacía")
    void sinCajaAbiertaEstaVacia() throws Exception {
        Caja c = armar();
        jdbc.update("update caja set abierta = null, fecha_cierre = now(6) where ferreteria_id = ?", c.t().id());

        assertThat(getJson(URL, login(c.t().emailVendedor()))).isEmpty();
        assertThat(getJson(URL, login(c.t().emailDueno()))).isEmpty();
    }

    @Test
    @DisplayName("el Vendedor pide la hoja de una de su lista (la misma respuesta que el detalle) y cualquier otra venta, "
            + "aunque exista, es 404 «Venta no encontrada»: de la caja cerrada, de otro, con su CAE, pendiente o de otra "
            + "ferretería")
    void elVendedorSoloPideLasDeSuLista() throws Exception {
        Caja c = armar();
        String vendedor = login(c.t().emailVendedor());
        Tienda otra = tiendaQueFactura(RI);
        long deOtraFerreteria = ventaAutorizada(otra);

        JsonNode hoja = getJson("/api/ventas/" + c.delVendedor() + "/factura", vendedor);

        assertThat(hoja).isEqualTo(detalle(c.t(), c.delVendedor()));
        assertThat(hoja.get("fiscal").get("documento")).isNotNull();
        assertThat(hoja.get("fiscal").get("reimprimir").asBoolean()).isTrue();
        for (long ajena : List.of(c.deLaCajaCerrada(), c.delOtro(), c.delDueno(), c.conSuCae(), c.pendiente(),
                deOtraFerreteria, 999_999_999L)) {
            assertThat(json(getResult("/api/ventas/" + ajena + "/factura", vendedor).andExpect(status().isNotFound()))
                    .get("message").asText()).as("venta %d", ajena).isEqualTo(VENTA_NO_ENCONTRADA);
        }
        // El detalle sigue siendo solo del Dueño.
        getResult("/api/ventas/" + c.delVendedor(), vendedor).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("el Dueño pide la hoja de cualquier venta de la ferretería; la de otra ferretería es 404")
    void elDuenoPideCualquiera() throws Exception {
        Caja c = armar();
        String dueno = login(c.t().emailDueno());
        Tienda otra = tiendaQueFactura(RI);
        long deOtraFerreteria = ventaAutorizada(otra);

        for (long venta : List.of(c.delVendedor(), c.delOtro(), c.conSuCae(), c.pendiente(), c.deLaCajaCerrada())) {
            assertThat(getJson("/api/ventas/" + venta + "/factura", dueno)).isEqualTo(detalle(c.t(), venta));
        }
        getResult("/api/ventas/" + deOtraFerreteria + "/factura", dueno).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("otra ferretería no ve las de esta, y el super admin recibe 403")
    void aislamientoYSuperAdmin() throws Exception {
        Caja c = armar();
        Tienda otra = tiendaQueFactura(RI);

        assertThat(getJson(URL, login(otra.emailVendedor()))).isEmpty();
        assertThat(getJson(URL, login(otra.emailDueno()))).isEmpty();
        String admin = superAdmin();
        getResult(URL, admin).andExpect(status().isForbidden());
        getResult("/api/ventas/" + c.delVendedor() + "/factura", admin).andExpect(status().isForbidden());
    }

    private static List<Long> ventaIdsDe(JsonNode lista) {
        List<Long> ids = new ArrayList<>();
        lista.forEach(n -> ids.add(n.get("ventaId").asLong()));
        return ids;
    }
}
