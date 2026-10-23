package com.ferreteria.ferreteria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * La configuracion de precios (margen por defecto y multiplo de redondeo) de la ferreteria del token: la usa
 * el formulario de articulo para mostrar el precio final en vivo con la misma cuenta que el backend.
 */
class ConfiguracionPreciosIntegrationTest extends IntegrationTestBase {

    /** Hash de demo1234, el mismo de db/dev-data. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";

    /** Ferreteria nueva con margen 45 y multiplo 50, y su dueno. Devuelve el token. */
    private String ferreteriaConConfiguracionPropia() throws Exception {
        String cuit = "30-" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)) + "-7";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 45.00, 50.00)", "Config " + cuit, cuit);
        Long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "config-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno config', 'DUENO')", id, email, HASH_DEMO);
        return login(email);
    }

    @Test
    void cadaFerreteriaVeSuPropiaConfiguracion() throws Exception {
        String otra = ferreteriaConConfiguracionPropia();

        getResult("/api/configuracion-precios", otra)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(45.0)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(50.0)));
        getResult("/api/configuracion-precios", login("dueno@tornillo.demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(30.0)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(10.0)));
    }

    @Test
    void elVendedorNoLaVe() throws Exception {
        // H07: el margen por defecto es del Dueno.
        getResult("/api/configuracion-precios", login("vendedor@tornillo.demo")).andExpect(status().isForbidden());
    }

    @Test
    void sinFerreteriaDa403YSinTokenDa401() throws Exception {
        getResult("/api/configuracion-precios", login("admin@demo.local")).andExpect(status().isForbidden());
        mvc.perform(get("/api/configuracion-precios")).andExpect(status().isUnauthorized());
    }

    // ---- el Dueño la cambia y se recalculan los precios (cu-03-configuracion-precios) ----

    private static final String URL_CONTEO = "/api/configuracion-precios/articulos-que-cambian";

    private static String configuracion(String margen, String multiplo) {
        return "{\"margenDefectoPorcentaje\": " + margen + ", \"redondeoMultiplo\": " + multiplo + "}";
    }

    /** Articulo de costo 1000, IVA 21, sin rubro. Devuelve su id. */
    private long articulo(String token, String margen, boolean activo, String precioManual) throws Exception {
        var body = objectMapper.createObjectNode()
                .put("codigoInterno", unico("CFG"))
                .put("descripcion", "Articulo de configuracion")
                .put("costo", 1000)
                .put("unidad", "UNIDAD")
                .put("activo", activo);
        if (margen != null) {
            body.put("margenPorcentaje", Integer.parseInt(margen));
        }
        if (precioManual != null) {
            body.put("precioManual", true).put("precioFinal", Integer.parseInt(precioManual));
        }
        String respuesta = postJson("/api/articulos", token, objectMapper.writeValueAsString(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private BigDecimal precio(long articuloId) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class,
                articuloId);
    }

    /**
     * Margen 45 y multiplo 50: el heredero (1000 x 1,45 x 1,21 = 1754,50) vale 1800 y el de margen propio 30
     * (1573) vale 1600. Un inactivo y uno con precio manual quedan afuera del recalculo.
     */
    private record Catalogo(long heredero, long margenPropio, long inactivo, long manual) {
    }

    private Catalogo catalogo(String token) throws Exception {
        Catalogo catalogo = new Catalogo(articulo(token, null, true, null), articulo(token, "30", true, null),
                articulo(token, null, false, null), articulo(token, null, true, "999"));
        assertThat(precio(catalogo.heredero())).isEqualByComparingTo("1800");
        assertThat(precio(catalogo.margenPropio())).isEqualByComparingTo("1600");
        return catalogo;
    }

    /** Articulo de costo 1000 en un rubro nuevo de margen 40, sin margen propio. Devuelve su id. */
    private long articuloConRubro(String token) throws Exception {
        String rubro = postJson("/api/rubros", token,
                        "{\"nombre\": \"" + unico("Rubro") + "\", \"margenPorcentaje\": 40}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var body = objectMapper.createObjectNode()
                .put("codigoInterno", unico("CFG"))
                .put("descripcion", "Articulo con rubro")
                .put("costo", 1000)
                .put("unidad", "UNIDAD")
                .put("rubroId", objectMapper.readTree(rubro).get("id").asLong());
        String respuesta = postJson("/api/articulos", token, objectMapper.writeValueAsString(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    @Test
    void siCambiaSoloElMargenPorDefectoNoTocaALosArticulosConRubro() throws Exception {
        String dueno = ferreteriaConConfiguracionPropia();
        long conRubro = articuloConRubro(dueno);
        // 1000 x 1,40 x 1,21 = 1694 -> 1700
        assertThat(precio(conRubro)).isEqualByComparingTo("1700");

        getResult(URL_CONTEO + "?margenDefectoPorcentaje=60&redondeoMultiplo=50", dueno)
                .andExpect(jsonPath("$.cantidad", is(0)));
        putJson("/api/configuracion-precios", dueno, configuracion("60", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(0)));

        assertThat(precio(conRubro)).isEqualByComparingTo("1700");
    }

    @Test
    void elConteoSinUnParametroDa400() throws Exception {
        getResult(URL_CONTEO + "?margenDefectoPorcentaje=10", ferreteriaConConfiguracionPropia())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Falta el parámetro: redondeoMultiplo")));
    }

    @Test
    void siCambiaSoloElMargenPorDefectoSeRecalculanLosQueLoHeredan() throws Exception {
        String dueno = ferreteriaConConfiguracionPropia();
        Catalogo catalogo = catalogo(dueno);

        getResult(URL_CONTEO + "?margenDefectoPorcentaje=60&redondeoMultiplo=50", dueno)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad", is(1)));

        putJson("/api/configuracion-precios", dueno, configuracion("60", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(60)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(50)))
                .andExpect(jsonPath("$.articulosRecalculados", is(1)))
                .andExpect(jsonPath("$.avisos.length()", is(0)));

        // 1000 x 1,60 x 1,21 = 1936 -> 1950
        assertThat(precio(catalogo.heredero())).isEqualByComparingTo("1950");
        assertThat(precio(catalogo.margenPropio())).isEqualByComparingTo("1600");
        assertThat(precio(catalogo.manual())).isEqualByComparingTo("999");
        assertThat(jdbc.queryForObject(
                "select origen from historial_precio where articulo_id = ? order by id desc limit 1", String.class,
                catalogo.heredero())).isEqualTo("MASIVO");
        getResult("/api/configuracion-precios", dueno)
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(60.0)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(50.0)));
    }

    @Test
    void siCambiaElMultiploSeRecalculanTodosLosActivosSinPrecioManual() throws Exception {
        String dueno = ferreteriaConConfiguracionPropia();
        Catalogo catalogo = catalogo(dueno);
        BigDecimal precioDelInactivo = precio(catalogo.inactivo());

        getResult(URL_CONTEO + "?margenDefectoPorcentaje=45&redondeoMultiplo=0", dueno)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad", is(2)));

        putJson("/api/configuracion-precios", dueno, configuracion("45", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(2)));

        assertThat(precio(catalogo.heredero())).isEqualByComparingTo("1754.50");
        assertThat(precio(catalogo.margenPropio())).isEqualByComparingTo("1573.00");
        assertThat(precio(catalogo.inactivo())).isEqualByComparingTo(precioDelInactivo);
        assertThat(precio(catalogo.manual())).isEqualByComparingTo("999");
    }

    @Test
    void sinCambiosNoSeRecalculaNada() throws Exception {
        String dueno = ferreteriaConConfiguracionPropia();
        catalogo(dueno);

        getResult(URL_CONTEO + "?margenDefectoPorcentaje=45.00&redondeoMultiplo=50", dueno)
                .andExpect(jsonPath("$.cantidad", is(0)));
        putJson("/api/configuracion-precios", dueno, configuracion("45.00", "50.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(0)));
    }

    @Test
    void cambiarLaConfiguracionNoTocaAOtraFerreteria() throws Exception {
        String tornillo = login("dueno@tornillo.demo");
        long deTornillo = articulo(tornillo, null, true, null);
        BigDecimal precioDeTornillo = precio(deTornillo);
        String otra = ferreteriaConConfiguracionPropia();

        putJson("/api/configuracion-precios", otra, configuracion("80", "0")).andExpect(status().isOk());

        assertThat(precio(deTornillo)).isEqualByComparingTo(precioDeTornillo);
        getResult("/api/configuracion-precios", tornillo)
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(30.0)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(10.0)));
        // El conteo de la otra ferreteria no ve los articulos de El Tornillo.
        getResult(URL_CONTEO + "?margenDefectoPorcentaje=80&redondeoMultiplo=1", otra)
                .andExpect(jsonPath("$.cantidad", is(0)));
    }

    @Test
    void soloElDuenoLaCambia() throws Exception {
        String vendedor = login("vendedor@tornillo.demo");
        putJson("/api/configuracion-precios", vendedor, configuracion("80", "0")).andExpect(status().isForbidden());
        getResult(URL_CONTEO + "?margenDefectoPorcentaje=80&redondeoMultiplo=0", vendedor)
                .andExpect(status().isForbidden());
        putJson("/api/configuracion-precios", login("admin@demo.local"), configuracion("80", "0"))
                .andExpect(status().isForbidden());
        getResult("/api/configuracion-precios", login("dueno@tornillo.demo"))
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(30.0)));
    }

    @Test
    void conUnArticuloEnDolaresSinCotizacionDa409YNoGuardaNada() throws Exception {
        String dueno = ferreteriaConConfiguracionPropia();
        long usd = articulo(dueno, null, true, null);
        // La ferreteria nueva no tiene ninguna cotizacion guardada: el articulo pasa a dolares por la base.
        jdbc.update("update articulo set moneda = 'USD' where id = ?", usd);
        BigDecimal precioAntes = precio(usd);

        putJson("/api/configuracion-precios", dueno, configuracion("60", "50"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));

        assertThat(precio(usd)).isEqualByComparingTo(precioAntes);
        getResult("/api/configuracion-precios", dueno)
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(45.0)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(50.0)));
    }

    @Test
    void validaLosValores() throws Exception {
        String dueno = ferreteriaConConfiguracionPropia();

        putJson("/api/configuracion-precios", dueno, configuracion("-1", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.margenDefectoPorcentaje", is("El margen no puede ser negativo.")));
        putJson("/api/configuracion-precios", dueno, configuracion("1000.01", "0")).andExpect(status().isBadRequest());
        putJson("/api/configuracion-precios", dueno, configuracion("30", "-0.5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.redondeoMultiplo", is("El redondeo no puede ser negativo.")));
        putJson("/api/configuracion-precios", dueno, configuracion("30", "0.505")).andExpect(status().isBadRequest());
        putJson("/api/configuracion-precios", dueno, configuracion("30", "null")).andExpect(status().isBadRequest());
        getResult("/api/configuracion-precios", dueno)
                .andExpect(jsonPath("$.margenDefectoPorcentaje", is(45.0)))
                .andExpect(jsonPath("$.redondeoMultiplo", is(50.0)));
    }
}
