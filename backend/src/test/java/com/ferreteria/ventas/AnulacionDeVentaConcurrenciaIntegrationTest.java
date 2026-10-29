package com.ferreteria.ventas;

import com.ferreteria.support.BarreraDeLocks.Largada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-08 (rama 8, T7): las sondas del verificador como regresión. La anulación contra «Reintentar» y contra una venta del
 * mismo artículo a la vez, un rechazo que no toca nada y la NC que no se ve desde otros endpoints ni otra ferretería.
 */
class AnulacionDeVentaConcurrenciaIntegrationTest extends FacturaDelDuenoTestBase {

    private Callable<MvcResult> post(String url, String token, String body) {
        return () -> mvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static String cuerpo(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @RepeatedTest(3)
    @DisplayName("anular y Reintentar a la vez sobre una rechazada sin cliente: a lo sumo una nota; la anulacion interna deja SIN_FACTURA y la NC AUTORIZADO; nunca 500 ni 503")
    void anularContraReintentar() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        String dueno = login(t.emailDueno());
        List<MvcResult> r = enParalelo(List.of(
                post("/api/ventas/" + venta + "/anular", dueno, "{\"motivo\":\"devolvio\"}"),
                post("/api/ventas/" + venta + "/factura/reintentar", dueno, "{}")));
        int sa = r.get(0).getResponse().getStatus();
        int sr = r.get(1).getResponse().getStatus();
        // Deja que el autorizar de despues del commit termine.
        dormir(Duration.ofMillis(500));
        List<Map<String, Object>> notas = jdbc.queryForList(
                "select id, motivo_anulacion from comprobante where comprobante_asociado_id = ?", venta);
        String vigente = jdbc.queryForObject("select estado from comprobante_fiscal where comprobante_id = ? and "
                + "reemplazado_en is null", String.class, venta);
        assertThat(sa).isIn(201, 409);
        assertThat(sr).isIn(200, 409);
        assertThat(notas.size()).isLessThanOrEqualTo(1);
        if (!notas.isEmpty() && notas.get(0).get("motivo_anulacion") != null) {
            assertThat(vigente).as("anulada por dentro y facturada a la vez").isIn("SIN_FACTURA");
        }
        if (!notas.isEmpty() && notas.get(0).get("motivo_anulacion") == null) {
            assertThat(vigente).isEqualTo("AUTORIZADO");
        }
    }

    @RepeatedTest(3)
    @DisplayName("anular una venta y vender el mismo articulo a la vez: las dos dan 201 y el stock queda en lo vendido menos lo devuelto")
    void anularContraVentaDelMismoArticulo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        long venta = json(venderA(t, null, null, clave(), "242.00", linea(a, "2", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        assertThat(estadoFiscal(venta)).isEqualTo("AUTORIZADO");
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        String otra = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
        List<MvcResult> r = enParalelo(List.of(
                post("/api/ventas/" + venta + "/anular", dueno, "{}"),
                post("/api/ventas", vendedor, otra)));
        assertThat(r.get(0).getResponse().getStatus()).isEqualTo(201);
        assertThat(r.get(1).getResponse().getStatus()).isEqualTo(201);
        assertThat(stock(a)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("un 409 OTRO_AMBIENTE no cambia el stock, los movimientos, los registros fiscales ni el numerador")
    void unRechazoNoTocaNada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        long venta = json(venderA(t, null, null, clave(), "242.00", linea(a, "2", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        jdbc.update("update config_arca set ambiente = 'HOMOLOGACION', de_prueba = false where ferreteria_id = ?", t.id());
        long mov = movimientos(t);
        long fis = registrosFiscales(t);
        BigDecimal st = stock(a);
        var numAntes = jdbc.queryForList("select * from numerador_comprobante where ferreteria_id = ?", t.id());
        MvcResult res = post("/api/ventas/" + venta + "/anular", login(t.emailDueno()), "{\"motivo\":\"x\"}").call();
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(movimientos(t)).isEqualTo(mov);
        assertThat(registrosFiscales(t)).isEqualTo(fis);
        assertThat(stock(a)).isEqualByComparingTo(st);
        assertThat(jdbc.queryForList("select * from numerador_comprobante where ferreteria_id = ?", t.id())).isEqualTo(numAntes);
    }

    @Test
    @DisplayName("la NC no se ve por /factura para el Vendedor, ni por el detalle, /factura o reintentar desde otra ferreteria: 404")
    void laNcNoSeFiltraPorOtrosEndpoints() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        Tienda otra = tiendaQueFactura(RI);
        long venta = ventaAutorizada(t);
        long nota = objectMapper.readTree(cuerpo(post("/api/ventas/" + venta + "/anular", login(t.emailDueno()), "{}")
                .call())).get("ventaId").asLong();
        String vend = login(t.emailVendedor());
        int factVend = mvc.perform(MockMvcRequestBuilders.get("/api/ventas/" + nota + "/factura")
                .header("Authorization", "Bearer " + vend)).andReturn().getResponse().getStatus();
        int detOtra = mvc.perform(MockMvcRequestBuilders.get("/api/ventas/" + nota)
                .header("Authorization", "Bearer " + login(otra.emailDueno()))).andReturn().getResponse().getStatus();
        int factOtra = mvc.perform(MockMvcRequestBuilders.get("/api/ventas/" + nota + "/factura")
                .header("Authorization", "Bearer " + login(otra.emailDueno()))).andReturn().getResponse().getStatus();
        int reintOtra = post("/api/ventas/" + nota + "/factura/reintentar", login(otra.emailDueno()), "{}").call()
                .getResponse().getStatus();
        String listaOtra = mvc.perform(MockMvcRequestBuilders.get("/api/ventas?size=100")
                .header("Authorization", "Bearer " + login(otra.emailDueno()))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(factVend).isEqualTo(404);
        assertThat(detOtra).isEqualTo(404);
        assertThat(factOtra).isEqualTo(404);
        assertThat(reintOtra).isEqualTo(404);
        assertThat(listaOtra).doesNotContain("\"ventaId\":" + nota + ",");
    }

    private static List<MvcResult> enParalelo(List<Callable<MvcResult>> trabajos) throws Exception {
        Largada largada = new Largada(trabajos.size());
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (Callable<MvcResult> trabajo : trabajos) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return trabajo.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<MvcResult> respuestas = new ArrayList<>();
            for (Future<MvcResult> futuro : futuros) {
                respuestas.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }
}
