package com.ferreteria.catalogo;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validacion del alta de un rubro: un mensaje por campo. Los articulos que heredan el margen del rubro y cambian de
 * precio con uno nuevo se prueban en {@link RubroVistaPreviaMargenIntegrationTest}. A = El Tornillo.
 */
class RubroHerenciaIntegrationTest extends IntegrationTestBase {

    private String duenoA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
    }

    @Test
    void elNombreEnBlancoYElMargenNegativoDanUnMensajePorCampo() throws Exception {
        postJson("/api/rubros", duenoA, "{\"nombre\":\"  \",\"margenPorcentaje\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nombre", is("El nombre es obligatorio.")))
                .andExpect(jsonPath("$.errors.margenPorcentaje", is("El margen no puede ser negativo.")));
    }
}
