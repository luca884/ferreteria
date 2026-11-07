package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Regresión de mejoras-rubros (S1 y S2): los casos que probó el verificador y no cubrían los tests de la rama. Ferreterias
 * propias (CUIT
 * 30-79990011-1 y 30-79990012-2), margen por defecto 30, multiplo 10, dolar vigente 1000.
 */
class MejorasRubrosRegresionIntegrationTest extends CotizacionTestBase {

    private static final String VP = "/api/actualizacion-masiva/vista-previa";
    private static final String AP = "/api/actualizacion-masiva";
    private static final String DERIVA = "Los artículos cambiaron desde la vista previa. "
            + "Volvé a generar la vista previa y confirmá de nuevo.";
    private static final String SIN_COTIZACION = "Todavía no hay cotización del dólar. Probá en unos minutos";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    private Long a;
    private Long b;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79990011-1", "Probe mejoras A");
        b = ferreteria("30-79990012-2", "Probe mejoras B");
        jdbc.update("update ferreteria set margen_defecto_porcentaje = 30.00, redondeo_multiplo = 10.00 where id in (?, ?)",
                a, b);
        borrarCotizaciones("probemr-");
        cotizacion("USD", "probemr-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> {
            c.setCasaPrecios("probemr-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "probemr-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        dueno = login(usuario(a, "dueno-probemr@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("probemr-");
        borrarDatosDe(a, b);
    }

    private Long rubro(Long ferreteriaId, String nombre, String margen) {
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, ?)", ferreteriaId, nombre,
                new BigDecimal(margen));
        return jdbc.queryForObject("select id from rubro where ferreteria_id = ? and nombre = ?", Long.class,
                ferreteriaId, nombre);
    }

    private Long art(String codigo, String moneda, String costo, String precio, Long rubro, String margen,
                     boolean manual) {
        Long id = articulo(a, codigo, moneda, costo, precio, true);
        jdbc.update("update articulo set rubro_id = ?, margen_porcentaje = ?, precio_manual = ? where id = ?", rubro,
                margen == null ? null : new BigDecimal(margen), manual, id);
        return id;
    }

    private Long rubroDe(Long articuloId) {
        return jdbc.queryForObject("select rubro_id from articulo where id = ?", Long.class, articuloId);
    }

    private long versionDe(Long articuloId) {
        return jdbc.queryForObject("select version from articulo where id = ?", Long.class, articuloId);
    }

    private List<Map<String, Object>> filas() {
        return jdbc.queryForList("select id, rubro_id, costo, precio_final, margen_porcentaje, precio_manual, version "
                + "from articulo where ferreteria_id = ? order by id", a);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ObjectNode cambiarRubro(Long destino) {
        return objectMapper.createObjectNode().put("modo", "CAMBIAR_RUBRO").put("rubroDestinoId", destino);
    }

    // ---------- S2: CAMBIAR_RUBRO ----------

    @Test
    void cambiarRubroUnoYaEnElDestinoNoSeEscribeUnoSinRubroSeMueveYSoloCambiaElRubroSeEscribeSinHistorial()
            throws Exception {
        Long origen = rubro(a, "MR-Origen", "40");
        Long destino = rubro(a, "MR-Destino", "60");
        // 100 x 1,6 x 1,21 = 193,6 -> 200 con el 60 % del destino.
        Long yaEsta = art("MR-YA", "ARS", "100.00", "200.00", destino, null, false);
        Long sinRubro = art("MR-SIN", "ARS", "100.00", "160.00", null, null, false);
        Long propio = art("MR-PROPIO", "ARS", "100.00", "190.00", origen, "50.00", false);
        long versionYaEsta = versionDe(yaEsta);
        long versionPropio = versionDe(propio);
        ObjectNode pedido = cambiarRubro(destino).put("q", "MR-");

        JsonNode previa = json(postJson(VP, dueno, pedido.toString()).andExpect(status().isOk()).andReturn());
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);
        assertThat(previa.at("/conteos/modificados").asInt()).isEqualTo(2);
        List<Long> enCambios = new ArrayList<>();
        previa.get("cambios").forEach(c -> enCambios.add(c.get("articuloId").asLong()));
        assertThat(enCambios).containsExactly(sinRubro, propio);
        assertThat(previa.get("cambios").get(0).get("rubroAnteriorId").isNull()).isTrue();
        assertThat(previa.get("cambios").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("200");

        JsonNode resultado = json(postJson(AP, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText())
                .toString()).andExpect(status().isCreated()).andReturn());
        assertThat(resultado.get("entradasDeHistorial").asInt()).isEqualTo(1);
        assertThat(versionDe(yaEsta)).isEqualTo(versionYaEsta);
        assertThat(versionDe(propio)).isEqualTo(versionPropio + 1);
        assertThat(rubroDe(sinRubro)).isEqualTo(destino);
        assertThat(rubroDe(propio)).isEqualTo(destino);
        assertThat(precioDe(sinRubro)).isEqualByComparingTo("200");
        assertThat(precioDe(propio)).isEqualByComparingTo("190");
        assertThat(historialDe(sinRubro)).hasSize(1);
        assertThat(historialDe(propio)).isEmpty();
        assertThat(historialDe(yaEsta)).isEmpty();
    }

    @Test
    void cambiarRubroConUnUsdEnElAlcanceYSinCotizacionDa409EnLosDosYNoCambiaNada() throws Exception {
        configuracion(a, c -> c.setCasaPrecios("probemr-no-existe"));
        Long origen = rubro(a, "MR-Origen", "40");
        Long destino = rubro(a, "MR-Destino", "60");
        art("MR-USD", "USD", "10.00", "16940.00", origen, null, false);
        art("MR-ARS", "ARS", "100.00", "170.00", origen, null, false);
        List<Map<String, Object>> antes = filas();

        postJson(VP, dueno, cambiarRubro(destino).toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(SIN_COTIZACION)));
        postJson(AP, dueno, cambiarRubro(destino).put("huella", "x").toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(SIN_COTIZACION)));

        assertThat(filas()).isEqualTo(antes);
        assertThat(historialDeLaFerreteria(a)).isZero();
    }

    @Test
    void cambiarRubroSiCambiaElMargenDelDestinoEntreLaVistaPreviaYAplicarDa409DerivaSinEscribir() throws Exception {
        Long origen = rubro(a, "MR-Origen", "40");
        Long destino = rubro(a, "MR-Destino", "60");
        art("MR-HER", "ARS", "100.00", "170.00", origen, null, false);
        ObjectNode pedido = cambiarRubro(destino);
        JsonNode previa = json(postJson(VP, dueno, pedido.toString()).andExpect(status().isOk()).andReturn());

        jdbc.update("update rubro set margen_porcentaje = 80.00 where id = ?", destino);
        List<Map<String, Object>> antes = filas();

        postJson(AP, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(DERIVA)));
        assertThat(filas()).isEqualTo(antes);
        assertThat(historialDeLaFerreteria(a)).isZero();
    }

    @Test
    void cambiarRubroConValorNullExplicitoAndaYUnModoViejoConRubroDestinoNullExplicitoTambien() throws Exception {
        Long origen = rubro(a, "MR-Origen", "40");
        Long destino = rubro(a, "MR-Destino", "60");
        art("MR-HER", "ARS", "100.00", "170.00", origen, null, false);

        postJson(VP, dueno, cambiarRubro(destino).putNull("valor").toString()).andExpect(status().isOk());
        postJson(VP, dueno, "{\"modo\":\"MARGEN\",\"valor\":45,\"rubroDestinoId\":null}").andExpect(status().isOk());
    }

    @Test
    void unModoViejoConUnRubroDestinoQueNoExisteDa404YConUnoPropio400() throws Exception {
        Long destino = rubro(a, "MR-Destino", "60");
        art("MR-HER", "ARS", "100.00", "170.00", null, null, false);

        MvcResult inexistente = postJson(VP, dueno, "{\"modo\":\"MARGEN\",\"valor\":45,\"rubroDestinoId\":999999}")
                .andReturn();
        MvcResult propio = postJson(VP, dueno, "{\"modo\":\"MARGEN\",\"valor\":45,\"rubroDestinoId\":" + destino + "}")
                .andReturn();
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);
        assertThat(propio.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void cambiarRubroConAjustesQueDejanElPrecioEnCeroQuedaComoErrorYNoSeMueve() throws Exception {
        Long origen = rubro(a, "MR-Origen", "100");
        Long destino = rubro(a, "MR-Destino", "0");
        // 100 x 2 x 1,21 = 242 con el origen; con el destino 121 y el ajuste de -200 deja el precio en 0 o menos.
        Long aj = art("MR-AJ", "ARS", "100.00", "50.00", origen, null, false);
        jdbc.update("update articulo set ajustes_precio = '[{\"nombre\":null,\"tipo\":\"MONTO\",\"valor\":-200}]' "
                + "where id = ?", aj);
        Long otro = art("MR-OK", "ARS", "100.00", "250.00", origen, null, false);
        ObjectNode pedido = cambiarRubro(destino);

        JsonNode previa = json(postJson(VP, dueno, pedido.toString()).andExpect(status().isOk()).andReturn());
        assertThat(previa.at("/conteos/conError").asInt()).isEqualTo(1);
        postJson(AP, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isCreated());
        assertThat(rubroDe(aj)).isEqualTo(origen);
        assertThat(rubroDe(otro)).isEqualTo(destino);
    }

    @Test
    void cambiarRubroConUsdYCotizacionUsaElVigenteYElPrecioDelAplicarEsElDeLaVistaPrevia() throws Exception {
        Long origen = rubro(a, "MR-Origen", "40");
        Long destino = rubro(a, "MR-Destino", "60");
        // 10 x 1000 x 1,6 x 1,21 = 19360.
        Long usd = art("MR-USD", "USD", "10.00", "16940.00", origen, null, false);
        ObjectNode pedido = cambiarRubro(destino);
        JsonNode previa = json(postJson(VP, dueno, pedido.toString()).andExpect(status().isOk()).andReturn());
        BigDecimal enLaPrevia = previa.get("cambios").get(0).get("precioNuevo").decimalValue();

        postJson(AP, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isCreated());
        assertThat(enLaPrevia).isEqualByComparingTo("19360");
        assertThat(precioDe(usd)).isEqualByComparingTo(enLaPrevia);
        assertThat(rubroDe(usd)).isEqualTo(destino);
    }

    // ---------- S1: vista previa del margen ----------

    private String vistaPrevia(Long rubroId, String margen) {
        return "/api/rubros/" + rubroId + "/vista-previa-margen?margenPorcentaje=" + margen;
    }

    @Test
    void vistaPreviaConMilUnoTraeMilYRecortadoYConMilNoRecorta() throws Exception {
        Long r = rubro(a, "MR-Tope", "35");
        List<Object[]> filas = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            String codigo = String.format("T-%04d", 1000 - i);
            filas.add(new Object[] {a, codigo, "Articulo " + codigo, new BigDecimal("1000.00"), "ARS",
                new BigDecimal("1640.00"), true, r});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, moneda, precio_final, "
                + "activo, rubro_id) values (?, ?, ?, ?, ?, ?, ?, ?)", filas);

        JsonNode vista = getJson(vistaPrevia(r, "36"), dueno);
        assertThat(vista.get("cantidad").asLong()).isEqualTo(1001);
        assertThat(vista.get("articulos")).hasSize(1000);
        assertThat(vista.get("recortado").asBoolean()).isTrue();
        assertThat(vista.get("articulos").get(0).get("codigo").asText()).isEqualTo("T-0000");
        assertThat(vista.get("articulos").get(999).get("codigo").asText()).isEqualTo("T-0999");

        jdbc.update("update articulo set activo = false where ferreteria_id = ? and codigo_interno = 'T-1000'", a);
        JsonNode mil = getJson(vistaPrevia(r, "36"), dueno);
        assertThat(mil.get("cantidad").asLong()).isEqualTo(1000);
        assertThat(mil.get("articulos")).hasSize(1000);
        assertThat(mil.get("recortado").asBoolean()).isFalse();
    }

    @Test
    void elPrecioNuevoDeLaVistaPreviaEsElQueDejaElPutYLaCantidadSonLosHistoriales() throws Exception {
        Long r = rubro(a, "MR-Igual", "35");
        Long her = art("MR-HER", "ARS", "1000.00", "1640.00", r, null, false);
        Long usd = art("MR-USD", "USD", "10.00", "16340.00", r, null, false);
        Long aj = art("MR-AJ", "ARS", "100.00", "160.00", r, null, false);
        jdbc.update("update articulo set ajustes_precio = '[{\"nombre\":\"D\",\"tipo\":\"PORCENTAJE\",\"valor\":-10}]' "
                + "where id = ?", aj);
        Long cero = art("MR-AJ0", "ARS", "100.00", "10.00", r, null, false);
        jdbc.update("update articulo set ajustes_precio = '[{\"nombre\":null,\"tipo\":\"MONTO\",\"valor\":-180}]' "
                + "where id = ?", cero);
        art("MR-MAN", "ARS", "1000.00", "1640.00", r, null, true);

        JsonNode vista = getJson(vistaPrevia(r, "50"), dueno);
        putJson("/api/rubros/" + r, dueno, "{\"nombre\":\"MR-Igual\",\"margenPorcentaje\":50}")
                .andExpect(status().isOk());

        for (JsonNode fila : vista.get("articulos")) {
            assertThat(precioDe(fila.get("id").asLong())).isEqualByComparingTo(fila.get("precioNuevo").decimalValue());
        }
        assertThat(historialDeLaFerreteria(a)).isEqualTo(vista.get("cantidad").asLong());
        assertThat(precioDe(her)).isNotNull();
        assertThat(precioDe(usd)).isNotNull();
    }

    @Test
    void el409DeLaVistaPreviaCoincideConElDelPut() throws Exception {
        configuracion(a, c -> c.setCasaPrecios("probemr-no-existe"));
        // USD heredero con precio manual: la cascada igual pide el dolar (409); la vista previa tambien.
        Long r1 = rubro(a, "MR-409-manual", "35");
        art("MR-USD-MAN", "USD", "10.00", "16340.00", r1, null, true);
        int vp1 = getResult(vistaPrevia(r1, "50"), dueno).andReturn().getResponse().getStatus();
        int put1 = putJson("/api/rubros/" + r1, dueno, "{\"nombre\":\"MR-409-manual\",\"margenPorcentaje\":50}")
                .andReturn().getResponse().getStatus();
        // USD dado de baja: ni la cascada ni la vista previa lo miran.
        Long r2 = rubro(a, "MR-409-baja", "35");
        Long baja = art("MR-USD-BAJA", "USD", "10.00", "16340.00", r2, null, false);
        jdbc.update("update articulo set activo = false where id = ?", baja);
        int vp2 = getResult(vistaPrevia(r2, "50"), dueno).andReturn().getResponse().getStatus();
        int put2 = putJson("/api/rubros/" + r2, dueno, "{\"nombre\":\"MR-409-baja\",\"margenPorcentaje\":50}")
                .andReturn().getResponse().getStatus();
        // USD con margen propio: no hereda.
        Long r3 = rubro(a, "MR-409-propio", "35");
        art("MR-USD-PROPIO", "USD", "10.00", "18150.00", r3, "50.00", false);
        int vp3 = getResult(vistaPrevia(r3, "50"), dueno).andReturn().getResponse().getStatus();
        int put3 = putJson("/api/rubros/" + r3, dueno, "{\"nombre\":\"MR-409-propio\",\"margenPorcentaje\":50}")
                .andReturn().getResponse().getStatus();
        assertThat(vp1).isEqualTo(put1).isEqualTo(409);
        assertThat(vp2).isEqualTo(put2).isEqualTo(200);
        assertThat(vp3).isEqualTo(put3).isEqualTo(200);
    }

    @Test
    void parametrosRarosYPermisosDeLaVistaPrevia() throws Exception {
        Long r = rubro(a, "MR-Param", "35");
        Long ajeno = rubro(b, "MR-Ajeno", "35");
        String vendedor = login(usuario(a, "vendedor-probemr@test.local", "VENDEDOR"));
        // {url, estado esperado}: sin el parámetro, uno que no es número, vacío, 3 decimales y fuera de rango dan 400; los
        // bordes 0 y 1000 andan; un rubro ajeno da 404 aunque el margen sea inválido (el 404 va antes que el 400).
        Object[][] casos = {{"/api/rubros/" + r + "/vista-previa-margen", 400},
            {vistaPrevia(r, "abc"), 400}, {vistaPrevia(r, ""), 400}, {vistaPrevia(r, "35.123"), 400},
            {vistaPrevia(r, "0"), 200}, {vistaPrevia(r, "1000"), 200}, {vistaPrevia(r, "1000.01"), 400},
            {vistaPrevia(ajeno, "-1"), 404}, {vistaPrevia(999999L, "36"), 404}};
        for (Object[] caso : casos) {
            MvcResult res = getResult((String) caso[0], dueno).andReturn();
            assertThat(res.getResponse().getStatus()).as((String) caso[0]).isEqualTo(caso[1]);
        }
        getResult(vistaPrevia(r, "36"), vendedor).andExpect(status().isForbidden());
        getResult(vistaPrevia(ajeno, "-1"), dueno).andExpect(status().isNotFound());
        getResult(vistaPrevia(r, "35.123"), dueno).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.margenPorcentaje", is("El margen admite hasta 2 decimales.")));
        getResult("/api/rubros/" + r + "/articulos-que-heredan", dueno).andExpect(status().isNotFound());
    }
}
