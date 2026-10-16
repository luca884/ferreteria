package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.support.ListasDePrueba;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * R-130 (T-156): un articulo que entro por la IMPORTACION en dolares se vende al precio en pesos guardado, aunque el
 * dolar cambie sin recalculo, y el mostrador y la venta no conocen la moneda. Ferreteria propia (CUIT 30-78300001-1).
 *
 * <p>Complementa a {@link VentaConArticuloUsdIntegrationTest}, que siembra el articulo por JDBC: aca el articulo nace
 * de una fila "USD" de una lista, con el dolar de precios en 1.000, margen de 30 % y IVA 21 % (10 x 1.000 x 1,30 x
 * 1,21 = 15.730).
 */
class VentaConArticuloImportadoEnUsdIntegrationTest extends CotizacionTestBase {

    private static final String CASA = "t83v-oficial";
    private final Instant haceUnRato = Instant.now().minusSeconds(600);

    private Long ferreteria;
    private String dueno;
    private String vendedor;
    private Long usd;

    @BeforeEach
    void sembrar() throws Exception {
        ferreteria = ferreteria("30-78300001-1", "Ventas con importado en dolares");
        borrarCotizaciones("t83v-");
        limpiarVentas();
        borrarDatosDe(ferreteria);
        cotizacion("USD", CASA, "1090", "1100", haceUnRato);
        configuracion(ferreteria, c -> {
            c.setCasaPrecios(CASA);
            c.fijarVigente(new BigDecimal("1000.0000"), CASA, LadoCotizacion.VENTA, haceUnRato);
        });
        dueno = login(usuario(ferreteria, "dueno-t83v@test.local", "DUENO"));
        vendedor = login(usuario(ferreteria, "vendedor-t83v@test.local", "VENDEDOR"));
        usd = importarArticuloEnDolares();
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t83v-");
        limpiarVentas();
        borrarDatosDe(ferreteria);
    }

    private void limpiarVentas() {
        jdbc.update("delete from pago where ferreteria_id = ?", ferreteria);
        jdbc.update("delete from item_comprobante where ferreteria_id = ?", ferreteria);
        jdbc.update("delete from movimiento_stock where ferreteria_id = ?", ferreteria);
        jdbc.update("delete from comprobante where ferreteria_id = ?", ferreteria);
        jdbc.update("delete from numerador_comprobante where ferreteria_id = ?", ferreteria);
    }

    /** Importa una lista con una fila en USD (costo 10) y devuelve el articulo creado, con stock para vender. */
    private Long importarArticuloEnDolares() throws Exception {
        String body = postJson("/api/proveedores", dueno, "{\"nombre\":\"" + unico("Prov") + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long proveedor = objectMapper.readTree(body).get("id").asLong();
        List<List<Object>> filas = new ArrayList<>();
        filas.add(java.util.Arrays.asList("Codigo", "Descripcion", "Costo", "Mon."));
        filas.add(java.util.Arrays.asList("T83V-1", "Importado en dolares", new BigDecimal("10"), "USD"));
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(filas));

        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedor).put("listaIncluyeIva", false);
        datos.set("mapeo", objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 2).put("columnaMoneda", 3));
        JsonNode previa = objectMapper.readTree(postMultipart("/api/importaciones/vista-previa", dueno, lista, datos)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        ObjectNode aplicar = datos.deepCopy();
        aplicar.put("crearNuevos", true);
        aplicar.set("conteosEsperados", previa.get("conteos"));
        postMultipart("/api/importaciones", dueno, lista, aplicar).andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(1)));

        Long id = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_proveedor = 'T83V-1'",
                Long.class, ferreteria);
        jdbc.update("update articulo set stock_actual = 10 where id = ?", id);
        return id;
    }

    private ResultActions vender(Long articuloId, String precioMostrado) throws Exception {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articuloId);
        renglon.put("cantidad", new BigDecimal("1"));
        renglon.put("precioUnitario", new BigDecimal(precioMostrado));
        Map<String, Object> venta = new LinkedHashMap<>();
        venta.put("clave", UUID.randomUUID().toString());
        venta.put("renglones", List.of(renglon));
        venta.put("totalMostrado", new BigDecimal(precioMostrado));
        venta.put("medioPago", "EFECTIVO");
        return postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(venta));
    }

    private JsonNode cuerpoDe(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> campos(JsonNode objeto) {
        List<String> nombres = new ArrayList<>();
        objeto.fieldNames().forEachRemaining(nombres::add);
        return nombres;
    }

    @Test
    @DisplayName("R-130: lo importado en USD queda en pesos (15.730) y se vende a ese precio aunque el dolar suba sin recalculo")
    void seVendeAlPrecioGuardadoPorLaImportacionAunqueElDolarSuba() throws Exception {
        assertThat(jdbc.queryForObject("select moneda from articulo where id = ?", String.class, usd)).isEqualTo("USD");
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");

        // El dolar del mercado sube a 1.200 y NADIE recalcula: el precio guardado sigue en 15.730.
        cotizacion("USD", CASA, "1190", "1200", Instant.now().minusSeconds(60));
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");

        JsonNode venta = cuerpoDe(vender(usd, "15730.00").andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("15730.00");
        assertThat(jdbc.queryForObject("select total from comprobante where ferreteria_id = ?", BigDecimal.class,
                ferreteria)).isEqualByComparingTo("15730.00");
        Map<String, Object> item = jdbc.queryForMap("select * from item_comprobante where ferreteria_id = ?", ferreteria);
        assertThat((BigDecimal) item.get("precio_unitario")).isEqualByComparingTo("15730.00");
        assertThat((BigDecimal) item.get("total")).isEqualByComparingTo("15730.00");
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("R-130: un precio distinto del guardado (el de un recalculo que no ocurrio) no cobra: PRECIOS_CAMBIADOS")
    void unPrecioQueNoEsElGuardadoNoSeCobra() throws Exception {
        cotizacion("USD", CASA, "1190", "1200", Instant.now().minusSeconds(60));

        JsonNode rechazo = cuerpoDe(vender(usd, "17310.00").andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("15730");
        assertThat(jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class,
                ferreteria)).isZero();
    }

    @Test
    @DisplayName("R-130: ni el mostrador ni la venta del articulo importado exponen moneda, costo ni cotizacion")
    void elMostradorYLaVentaNoExponenLaMoneda() throws Exception {
        String texto = mvc.perform(get("/api/articulos/mostrador").param("q", "T83V-1")
                        .header("Authorization", "Bearer " + vendedor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].precioFinal", is(15730.0)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode articulo = objectMapper.readTree(texto).get("items").get(0);
        assertThat(campos(articulo)).doesNotContain("moneda", "cotizacion", "costo", "margenPorcentaje");
        assertThat(texto).doesNotContain("moneda").doesNotContain("cotizacion").doesNotContain("USD");

        JsonNode venta = cuerpoDe(vender(usd, "15730.00").andExpect(status().isCreated()));
        assertThat(campos(venta)).doesNotContain("moneda", "cotizacion");
        assertThat(campos(venta.get("renglones").get(0))).doesNotContain("moneda", "cotizacion", "costo");
    }
}
