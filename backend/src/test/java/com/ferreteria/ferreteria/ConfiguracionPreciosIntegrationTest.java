package com.ferreteria.ferreteria;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
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
    void elVendedorTambienLaLee() throws Exception {
        getResult("/api/configuracion-precios", login("vendedor@tornillo.demo")).andExpect(status().isOk());
    }

    @Test
    void sinFerreteriaDa403YSinTokenDa401() throws Exception {
        getResult("/api/configuracion-precios", login("admin@demo.local")).andExpect(status().isForbidden());
        mvc.perform(get("/api/configuracion-precios")).andExpect(status().isUnauthorized());
    }
}
