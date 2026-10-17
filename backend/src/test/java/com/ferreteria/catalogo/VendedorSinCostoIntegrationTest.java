package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H07: el Vendedor esta para vender. No ve costo, margen ni nada que permita deducirlos (precio de lista,
 * bonificaciones, ajustes, si la lista trae IVA, la moneda del costo) y no edita nada de eso. Conserva las pantallas
 * de Articulos, Rubros y Proveedores en modo lectura. El Dueno sigue viendo todo.
 */
class VendedorSinCostoIntegrationTest extends IntegrationTestBase {

    /** Claves de costo y margen que el Vendedor no tiene que recibir en un articulo. */
    private static final List<String> COSTO_DEL_ARTICULO = List.of(
            "costo", "moneda", "margenPorcentaje", "precioListaProveedor", "ajuste1", "ajuste2", "ajuste3",
            "bonificacion1", "bonificacion2", "bonificacion3", "listaIncluyeIva", "version");

    /** Claves de la lista del proveedor (de donde sale el costo). */
    private static final List<String> LISTA_DEL_PROVEEDOR = List.of(
            "mapeoLista", "listaIncluyeIva", "bonificacion1", "bonificacion2", "bonificacion3");

    private String duenoA;
    private String vendedorA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        vendedorA = login("vendedor@tornillo.demo");
    }

    private static void sinClaves(JsonNode nodo, List<String> claves) {
        assertThat(claves).allSatisfy(clave -> assertThat(nodo.has(clave)).as("trae %s", clave).isFalse());
    }

    private static void conClaves(JsonNode nodo, List<String> claves) {
        assertThat(claves).allSatisfy(clave -> assertThat(nodo.has(clave)).as("falta %s", clave).isTrue());
    }

    @Test
    @DisplayName("listado de articulos: el Vendedor ve precio y stock, sin costo ni margen; el Dueno ve todo")
    void listadoDeArticulos() throws Exception {
        JsonNode vendedor = getJson("/api/articulos?q=TOR-001", vendedorA).get("items").get(0);
        JsonNode dueno = getJson("/api/articulos?q=TOR-001", duenoA).get("items").get(0);

        sinClaves(vendedor, COSTO_DEL_ARTICULO);
        conClaves(vendedor, List.of("id", "codigoInterno", "descripcion", "precioFinal", "stockActual", "activo"));
        conClaves(dueno, COSTO_DEL_ARTICULO);
    }

    @Test
    @DisplayName("detalle de articulo: el Vendedor no recibe costo ni margen")
    void detalleDeArticulo() throws Exception {
        sinClaves(getJson("/api/articulos/1", vendedorA), COSTO_DEL_ARTICULO);
        conClaves(getJson("/api/articulos/1", duenoA), COSTO_DEL_ARTICULO);
    }

    @Test
    @DisplayName("el desglose del precio es solo del Dueno: el Vendedor recibe 403")
    void desglose() throws Exception {
        getResult("/api/articulos/1/desglose", vendedorA).andExpect(status().isForbidden());
        getResult("/api/articulos/1/desglose", duenoA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("rubros: el Vendedor ve el nombre sin el margen; cuantos heredan el margen es solo del Dueno")
    void rubros() throws Exception {
        JsonNode rubrosVendedor = getJson("/api/rubros", vendedorA);
        long rubroId = rubrosVendedor.get(0).get("id").asLong();

        sinClaves(rubrosVendedor.get(0), List.of("margenPorcentaje"));
        sinClaves(getJson("/api/rubros/" + rubroId, vendedorA), List.of("margenPorcentaje"));
        conClaves(getJson("/api/rubros/" + rubroId, duenoA), List.of("id", "nombre", "margenPorcentaje"));
        getResult("/api/rubros/" + rubroId + "/articulos-que-heredan", vendedorA).andExpect(status().isForbidden());
        getResult("/api/rubros/" + rubroId + "/articulos-que-heredan", duenoA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el margen por defecto y el redondeo de la ferreteria son solo del Dueno")
    void configuracionDePrecios() throws Exception {
        getResult("/api/configuracion-precios", vendedorA).andExpect(status().isForbidden());
        getResult("/api/configuracion-precios", duenoA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("proveedores: el Vendedor ve nombre, CUIT y estado, sin el mapeo ni las bonificaciones de la lista")
    void proveedores() throws Exception {
        JsonNode lista = getJson("/api/proveedores", vendedorA);
        long proveedorId = lista.get(0).get("id").asLong();

        sinClaves(lista.get(0), LISTA_DEL_PROVEEDOR);
        conClaves(lista.get(0), List.of("id", "nombre", "cuit", "activo"));
        sinClaves(getJson("/api/proveedores/" + proveedorId, vendedorA), LISTA_DEL_PROVEEDOR);
        conClaves(getJson("/api/proveedores/" + proveedorId, duenoA), LISTA_DEL_PROVEEDOR);
    }

    @Test
    @DisplayName("el Vendedor no edita ajustes ni rubros (403) y nada cambia")
    void noEdita() throws Exception {
        long rubroId = getJson("/api/rubros", duenoA).get(0).get("id").asLong();
        String margenAntes = getJson("/api/rubros/" + rubroId, duenoA).get("margenPorcentaje").asText();

        putJson("/api/articulos/1/ajustes", vendedorA, "{\"ajuste1\":-10,\"version\":0}")
                .andExpect(status().isForbidden());
        postJson("/api/rubros", vendedorA, "{\"nombre\":\"" + unico("RV") + "\",\"margenPorcentaje\":10}")
                .andExpect(status().isForbidden());
        putJson("/api/rubros/" + rubroId, vendedorA, "{\"nombre\":\"Cambiado\",\"margenPorcentaje\":1}")
                .andExpect(status().isForbidden());

        assertThat(getJson("/api/rubros/" + rubroId, duenoA).get("margenPorcentaje").asText()).isEqualTo(margenAntes);
    }

    @Test
    @DisplayName("aislamiento: un articulo de otra ferreteria sigue dando 404 al Vendedor")
    void otraFerreteria() throws Exception {
        long deB = jdbc.queryForObject("select min(id) from articulo where ferreteria_id = 2", Long.class);

        getResult("/api/articulos/" + deB, vendedorA).andExpect(status().isNotFound());
    }
}
