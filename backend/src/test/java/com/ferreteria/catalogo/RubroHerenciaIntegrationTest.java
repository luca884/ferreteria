package com.ferreteria.catalogo;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cuantos articulos heredan el margen de un rubro: la pantalla lo avisa antes de cambiar el margen, porque
 * esos son los precios que se recalculan. Cuenta los activos sin margen propio. A = El Tornillo, B = La Tuerca.
 */
class RubroHerenciaIntegrationTest extends IntegrationTestBase {

    private String duenoA;
    private String duenoB;
    private String vendedorA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
        vendedorA = login("vendedor@tornillo.demo");
    }

    private long crearRubro(String token) throws Exception {
        String body = postJson("/api/rubros", token,
                "{\"nombre\":\"" + unico("Herencia") + "\",\"margenPorcentaje\":35}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long crearArticulo(String token, long rubroId, String margenPropio) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", unico("HER"))
                .put("descripcion", "Articulo que hereda")
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("100"))
                .put("rubroId", rubroId);
        if (margenPropio != null) pedido.put("margenPorcentaje", new BigDecimal(margenPropio));
        String body = postJson("/api/articulos", token, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void cuentaSoloLosActivosSinMargenPropio() throws Exception {
        long rubro = crearRubro(duenoA);
        crearArticulo(duenoA, rubro, null);
        crearArticulo(duenoA, rubro, null);
        crearArticulo(duenoA, rubro, "50");
        long deBaja = crearArticulo(duenoA, rubro, null);
        delete("/api/articulos/" + deBaja, duenoA).andExpect(status().isNoContent());

        getResult("/api/rubros/" + rubro + "/articulos-que-heredan", duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad", is(2)));
    }

    @Test
    void unRubroSinArticulosDaCero() throws Exception {
        long rubro = crearRubro(duenoA);

        getResult("/api/rubros/" + rubro + "/articulos-que-heredan", vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad", is(0)));
    }

    @Test
    void unRubroDeOtraFerreteriaDa404() throws Exception {
        long rubroDeB = crearRubro(duenoB);
        crearArticulo(duenoB, rubroDeB, null);

        getResult("/api/rubros/" + rubroDeB + "/articulos-que-heredan", duenoA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")));
    }

    @Test
    void elNombreEnBlancoYElMargenNegativoDanUnMensajePorCampo() throws Exception {
        postJson("/api/rubros", duenoA, "{\"nombre\":\"  \",\"margenPorcentaje\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nombre", is("El nombre es obligatorio.")))
                .andExpect(jsonPath("$.errors.margenPorcentaje", is("El margen no puede ser negativo.")));
    }
}
