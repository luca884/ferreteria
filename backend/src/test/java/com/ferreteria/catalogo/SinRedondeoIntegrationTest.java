package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.support.IntegrationTestBase;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H11: por ahora el precio final NO se redondea a un multiplo (decision de Luca, 2026-10-17). Con el multiplo de $10
 * una arandela de $3,15 quedaba en $10. La migracion V9 deja el multiplo en 0 (sin redondeo). En la base de tests
 * V1100 (db/test-data) vuelve a $10 porque los tests de precios se escribieron con ese multiplo: aca se usa una
 * ferreteria propia en 0, como queda en produccion despues de V9.
 */
class SinRedondeoIntegrationTest extends IntegrationTestBase {

    /** Hash BCrypt de demo1234, el mismo de los datos demo. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";
    private static final AtomicInteger CUITS = new AtomicInteger();

    /** Ferreteria sin multiplo de redondeo, y su dueno. Devuelve el email del dueno. */
    private String ferreteriaSinMultiplo() {
        String cuit = String.format("30-%08d-7", 75000000 + CUITS.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, redondeo_multiplo) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO', 0.00)", unico("Sin redondeo"), cuit);
        Long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = unico("dueno") + "@sinredondeo.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, 'Dueno', 'DUENO')",
                id, email, HASH_DEMO);
        return email;
    }

    @Test
    @DisplayName("la V9 esta aplicada y una ferreteria nueva nace sin multiplo de redondeo")
    void multiploEnCero() {
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version = '9'", Boolean.class))
                .isTrue();
        assertThat(new Ferreteria("Nueva", "30-71234567-1", CondicionIva.RESPONSABLE_INSCRIPTO).getRedondeoMultiplo())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("una arandela de costo $2, margen 30 % e IVA 21 % queda en $3,15 y el desglose no tiene paso de redondeo")
    void arandelaConCentavos() throws Exception {
        String dueno = login(ferreteriaSinMultiplo());
        var body = objectMapper.createObjectNode()
                .put("codigoInterno", unico("ARA"))
                .put("descripcion", "Arandela plana 1/4")
                .put("costo", 2)
                .put("margenPorcentaje", 30)
                .put("unidad", "UNIDAD");
        String respuesta = postJson("/api/articulos", dueno, objectMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.precioFinal", is(3.15)))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(respuesta).get("id").asLong();

        var pasos = getJson("/api/articulos/" + id + "/desglose", dueno).get("pasos");
        assertThat(pasos.findValuesAsText("tipo")).doesNotContain("REDONDEO");
    }
}
