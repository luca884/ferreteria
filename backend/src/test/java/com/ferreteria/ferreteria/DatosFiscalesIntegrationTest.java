package com.ferreteria.ferreteria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.support.IntegrationTestBase;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Datos fiscales de la ferreteria del token (CU-01 paso 1, D1 del plan de facturacion): el Dueño lee y edita razon social,
 * condicion de IVA, domicilio comercial, inicio de actividades e Ingresos Brutos; el CUIT se ve y no cambia nunca. El
 * Vendedor y el super admin reciben 403, y otra ferreteria no ve ni toca nada.
 */
class DatosFiscalesIntegrationTest extends IntegrationTestBase {

    private static final String URL = "/api/datos-fiscales";

    /** Hash de demo1234, el mismo de db/dev-data. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";

    private record FerreteriaDePrueba(long id, String cuit, String token) {}

    /** Ferreteria nueva, sin datos fiscales, y su Dueño logueado. */
    private FerreteriaDePrueba ferreteriaNueva() throws Exception {
        String cuit = "30-" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)) + "-7";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Fiscal " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "datos-fiscales-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno fiscal', 'DUENO')", id, email, HASH_DEMO);
        return new FerreteriaDePrueba(id, cuit, login(email));
    }

    private static String datos(String razonSocial, String condicionIva, String domicilio, String inicio, String iibb) {
        return "{\"razonSocial\": " + texto(razonSocial) + ", \"condicionIva\": " + texto(condicionIva)
                + ", \"domicilioComercial\": " + texto(domicilio) + ", \"inicioActividades\": " + texto(inicio)
                + ", \"ingresosBrutos\": " + texto(iibb) + "}";
    }

    private static String completos() {
        return datos("Tuerca SRL", "MONOTRIBUTISTA", "Av. Luro 3200, Mar del Plata", "2015-03-01", "901-123456-7");
    }

    private static String texto(String valor) {
        return valor == null ? "null" : "\"" + valor + "\"";
    }

    @Test
    void unaFerreteriaNuevaTraeLoDelAltaYNadaMas() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();

        getResult(URL, f.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial", is("Fiscal " + f.cuit())))
                .andExpect(jsonPath("$.cuit", is(f.cuit())))
                .andExpect(jsonPath("$.condicionIva", is("RESPONSABLE_INSCRIPTO")))
                .andExpect(jsonPath("$.domicilioComercial", nullValue()))
                .andExpect(jsonPath("$.inicioActividades", nullValue()))
                .andExpect(jsonPath("$.ingresosBrutos", nullValue()));
    }

    @Test
    void elDuenoGuardaLosCincoDatosYLosVuelveALeer() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();

        putJson(URL, f.token(), completos())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial", is("Tuerca SRL")))
                .andExpect(jsonPath("$.cuit", is(f.cuit())))
                .andExpect(jsonPath("$.condicionIva", is("MONOTRIBUTISTA")))
                .andExpect(jsonPath("$.domicilioComercial", is("Av. Luro 3200, Mar del Plata")))
                .andExpect(jsonPath("$.inicioActividades", is("2015-03-01")))
                .andExpect(jsonPath("$.ingresosBrutos", is("901-123456-7")));

        getResult(URL, f.token())
                .andExpect(jsonPath("$.razonSocial", is("Tuerca SRL")))
                .andExpect(jsonPath("$.condicionIva", is("MONOTRIBUTISTA")))
                .andExpect(jsonPath("$.domicilioComercial", is("Av. Luro 3200, Mar del Plata")))
                .andExpect(jsonPath("$.inicioActividades", is("2015-03-01")))
                .andExpect(jsonPath("$.ingresosBrutos", is("901-123456-7")));
    }

    @Test
    void elCuitNoCambiaAunqueElCuerpoTraigaOtro() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();
        String conOtroCuit = completos().replaceFirst("\\{", "{\"cuit\": \"30-71234567-1\", ");

        putJson(URL, f.token(), conOtroCuit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuit", is(f.cuit())));

        assertThat(jdbc.queryForObject("select cuit from ferreteria where id = ?", String.class, f.id()))
                .isEqualTo(f.cuit());
    }

    @Test
    void recortaLosTextosYGuardaLosVaciosComoVacios() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();
        putJson(URL, f.token(), completos()).andExpect(status().isOk());

        putJson(URL, f.token(), datos("  Tuerca SRL  ", "RESPONSABLE_INSCRIPTO", "   ", null, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial", is("Tuerca SRL")))
                .andExpect(jsonPath("$.domicilioComercial", nullValue()))
                .andExpect(jsonPath("$.inicioActividades", nullValue()))
                .andExpect(jsonPath("$.ingresosBrutos", nullValue()));

        Map<String, Object> fila = jdbc.queryForMap(
                "select domicilio_comercial, inicio_actividades, ingresos_brutos from ferreteria where id = ?", f.id());
        assertThat(fila.values()).containsOnlyNulls();
    }

    @Test
    void otraFerreteriaNoVeNiTocaLosDatos() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();
        FerreteriaDePrueba otra = ferreteriaNueva();

        putJson(URL, f.token(), completos()).andExpect(status().isOk());

        getResult(URL, otra.token())
                .andExpect(jsonPath("$.razonSocial", is("Fiscal " + otra.cuit())))
                .andExpect(jsonPath("$.cuit", is(otra.cuit())))
                .andExpect(jsonPath("$.condicionIva", is("RESPONSABLE_INSCRIPTO")))
                .andExpect(jsonPath("$.domicilioComercial", nullValue()));
        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, otra.id()))
                .isEqualTo("Fiscal " + otra.cuit());
    }

    @Test
    void soloElDueno() throws Exception {
        String vendedor = login("vendedor@tornillo.demo");
        String admin = login("admin@demo.local");

        getResult(URL, vendedor).andExpect(status().isForbidden());
        putJson(URL, vendedor, completos()).andExpect(status().isForbidden());
        // El 403 sale antes de leer el cuerpo: un Vendedor con datos invalidos no recibe 400.
        putJson(URL, vendedor, "{\"razonSocial\": \"\"}").andExpect(status().isForbidden());
        getResult(URL, admin).andExpect(status().isForbidden());
        putJson(URL, admin, completos()).andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(completos()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validaLosDatosYNoGuardaNada() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();
        String largo201 = "x".repeat(201);

        putJson(URL, f.token(), datos(" ", "MONOTRIBUTISTA", null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.razonSocial", is("La razón social es obligatoria.")));
        putJson(URL, f.token(), datos("x".repeat(151), "MONOTRIBUTISTA", null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.razonSocial", is("La razón social admite hasta 150 caracteres.")));
        putJson(URL, f.token(), datos("Tuerca SRL", null, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.condicionIva", is("Elegí la condición de IVA.")));
        putJson(URL, f.token(), datos("Tuerca SRL", "INVENTADA", null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.condicionIva", notNullValue()));
        putJson(URL, f.token(), datos("Tuerca SRL", "MONOTRIBUTISTA", largo201, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.domicilioComercial", is("El domicilio admite hasta 200 caracteres.")));
        putJson(URL, f.token(), datos("Tuerca SRL", "MONOTRIBUTISTA", null, null, "x".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ingresosBrutos", is("Ingresos Brutos admite hasta 30 caracteres.")));
        String manana = HoraArgentina.hoy().plusDays(1).toString();
        putJson(URL, f.token(), datos("Tuerca SRL", "MONOTRIBUTISTA", null, manana, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.inicioActividades",
                        is("El inicio de actividades no puede ser una fecha futura.")));

        getResult(URL, f.token())
                .andExpect(jsonPath("$.razonSocial", is("Fiscal " + f.cuit())))
                .andExpect(jsonPath("$.condicionIva", is("RESPONSABLE_INSCRIPTO")))
                .andExpect(jsonPath("$.inicioActividades", nullValue()));
    }

    @Test
    void elInicioDeActividadesPuedeSerHoy() throws Exception {
        FerreteriaDePrueba f = ferreteriaNueva();
        String hoy = HoraArgentina.hoy().toString();

        putJson(URL, f.token(), datos("Tuerca SRL", "EXENTO", null, hoy, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inicioActividades", is(hoy)));
    }
}
