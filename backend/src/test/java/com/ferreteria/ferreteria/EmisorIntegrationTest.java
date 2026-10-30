package com.ferreteria.ferreteria;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * Los datos del emisor para imprimir remitos y presupuestos (cu-07-remito-presupuesto-imprimible, S3): el Dueño y el
 * Vendedor leen los de SU ferreteria; el super admin recibe 403 y cada ferreteria ve solo los suyos. {@code /api/datos-fiscales}
 * sigue siendo solo del Dueño.
 */
class EmisorIntegrationTest extends IntegrationTestBase {

    private static final String URL = "/api/emisor";

    /** Hash de demo1234, el mismo de db/dev-data. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";

    private record Emisor(String cuit, String razonSocial, String tokenDueno, String tokenVendedor) {}

    /** Ferreteria nueva con domicilio, inicio de actividades e IIBB, y un Dueño y un Vendedor logueados. */
    private Emisor emisorNuevo(String marca) throws Exception {
        String cuit = "30-" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)) + "-7";
        String razonSocial = marca + " " + cuit;
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'MONOTRIBUTISTA', ?, '2015-03-01', '901-123456-7')",
                razonSocial, cuit, "Calle " + marca + " 100");
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String dueno = "emisor-dueno-" + cuit + "@test.local";
        String vendedor = "emisor-vendedor-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno emisor', 'DUENO')", id, dueno, HASH_DEMO);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor emisor', 'VENDEDOR')", id, vendedor, HASH_DEMO);
        return new Emisor(cuit, razonSocial, login(dueno), login(vendedor));
    }

    @Test
    void elDuenoYElVendedorLeenLosDatosDeSuFerreteria() throws Exception {
        Emisor e = emisorNuevo("Uno");

        for (String token : new String[] {e.tokenDueno(), e.tokenVendedor()}) {
            getResult(URL, token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.razonSocial", is(e.razonSocial())))
                    .andExpect(jsonPath("$.cuit", is(e.cuit())))
                    .andExpect(jsonPath("$.condicionIva", is("MONOTRIBUTISTA")))
                    .andExpect(jsonPath("$.domicilioComercial", is("Calle Uno 100")))
                    .andExpect(jsonPath("$.inicioActividades", is("2015-03-01")))
                    .andExpect(jsonPath("$.ingresosBrutos", is("901-123456-7")));
        }
    }

    @Test
    void cadaFerreteriaVeSoloLosSuyosAunqueMandeOtraEnLaQuery() throws Exception {
        Emisor uno = emisorNuevo("Uno");
        Emisor otra = emisorNuevo("Otra");
        long idDeUno = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, uno.cuit());

        getResult(URL + "?ferreteriaId=" + idDeUno, otra.tokenVendedor())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial", is(otra.razonSocial())))
                .andExpect(jsonPath("$.cuit", is(otra.cuit())))
                .andExpect(jsonPath("$.domicilioComercial", is("Calle Otra 100")));
    }

    @Test
    void elSuperAdminRecibe403YSinSesion401() throws Exception {
        getResult(URL, login("admin@demo.local")).andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void datosFiscalesSigueSiendoSoloDelDueno() throws Exception {
        Emisor e = emisorNuevo("Uno");

        getResult("/api/datos-fiscales", e.tokenVendedor()).andExpect(status().isForbidden());
        getResult("/api/datos-fiscales", e.tokenDueno()).andExpect(status().isOk());
    }
}
