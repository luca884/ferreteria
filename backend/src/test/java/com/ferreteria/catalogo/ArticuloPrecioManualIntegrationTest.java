package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.model.AjustePrecio;
import com.ferreteria.catalogo.model.AjustesDePrecio;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.PrecioCalculator;
import com.ferreteria.catalogo.model.TipoAjustePrecio;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Precio manual y ajustes sobre el precio final por la API del articulo (CU-14, T3): alta, edicion, respuesta,
 * validaciones, permiso del Dueño y aislamiento entre ferreterias. Ferreterias demo Tornillo (A) y Tuerca (B).
 */
class ArticuloPrecioManualIntegrationTest extends IntegrationTestBase {

    private static final String URL = "/api/articulos";

    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        vendedorA = login("vendedor@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
    }

    // ---- helpers ----

    /** Costo 100, margen propio 30, IVA 21, sin rubro. */
    private ObjectNode pedido() {
        return objectMapper.createObjectNode()
                .put("codigoInterno", unico("PM"))
                .put("descripcion", "Articulo precio manual")
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("100"))
                .put("margenPorcentaje", new BigDecimal("30"));
    }

    private static ObjectNode ajuste(ObjectNode pedido, String nombre, String tipo, String valor) {
        ArrayNode lista = pedido.has("ajustesPrecio") ? (ArrayNode) pedido.get("ajustesPrecio")
                : pedido.putArray("ajustesPrecio");
        ObjectNode ajuste = lista.addObject();
        if (nombre != null) {
            ajuste.put("nombre", nombre);
        }
        if (tipo != null) {
            ajuste.put("tipo", tipo);
        }
        if (valor != null) {
            ajuste.put("valor", new BigDecimal(valor));
        }
        return pedido;
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode crear(ObjectNode pedido) throws Exception {
        return cuerpo(postJson(URL, duenoA, pedido.toString()).andExpect(status().isCreated()));
    }

    private ResultActions editar(String token, long id, ObjectNode pedido) throws Exception {
        return putJson(URL + "/" + id, token, conLaVersionVigente(id, pedido.toString()));
    }

    /** El precio que da la regla con costo 100, margen 30, IVA 21 y el multiplo de la ferreteria A. */
    private BigDecimal calculado(AjustePrecio... ajustes) {
        BigDecimal multiplo = jdbc.queryForObject(
                "select f.redondeo_multiplo from ferreteria f join usuario u on u.ferreteria_id = f.id "
                        + "where u.email = 'dueno@tornillo.demo'", BigDecimal.class);
        return PrecioCalculator.precioFinal(new BigDecimal("100"), new BigDecimal("30"), AlicuotaIva.IVA_21,
                multiplo, BigDecimal.ONE, new AjustesDePrecio(List.of(ajustes)));
    }

    private BigDecimal precioEnBase(long id) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, id);
    }

    // ---- alta ----

    @Test
    @DisplayName("alta con precio manual: queda marcado, con el precio escrito y una entrada de historial MANUAL")
    void altaConPrecioManual() throws Exception {
        JsonNode creado = crear(pedido().put("precioManual", true).put("precioFinal", new BigDecimal("999.99")));

        assertThat(creado.get("precioManual").asBoolean()).isTrue();
        assertThat(creado.get("precioFinal").decimalValue()).isEqualByComparingTo("999.99");
        assertThat(creado.get("ajustesPrecio")).isEmpty();
        assertThat(jdbc.queryForObject("select precio_nuevo from historial_precio where articulo_id = ? "
                + "and origen = 'MANUAL'", BigDecimal.class, creado.get("id").asLong())).isEqualByComparingTo("999.99");
    }

    @Test
    @DisplayName("alta con ajustes: los devuelve en orden y el precio es el calculado mas los ajustes")
    void altaConAjustes() throws Exception {
        JsonNode creado = crear(ajuste(ajuste(pedido(), "Flete", "PORCENTAJE", "10"), null, "MONTO", "-5"));

        assertThat(creado.get("precioManual").asBoolean()).isFalse();
        assertThat(creado.get("ajustesPrecio")).hasSize(2);
        assertThat(creado.at("/ajustesPrecio/0/nombre").asText()).isEqualTo("Flete");
        assertThat(creado.at("/ajustesPrecio/0/tipo").asText()).isEqualTo("PORCENTAJE");
        assertThat(creado.at("/ajustesPrecio/1/nombre").isNull()).isTrue();
        assertThat(creado.at("/ajustesPrecio/1/valor").decimalValue()).isEqualByComparingTo("-5");
        assertThat(creado.get("precioFinal").decimalValue()).isEqualByComparingTo(calculado(
                new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, new BigDecimal("10")),
                new AjustePrecio(null, TipoAjustePrecio.MONTO, new BigDecimal("-5"))));
    }

    // ---- edicion ----

    @Test
    @DisplayName("un PUT sin los campos nuevos conserva la marca de precio manual y los ajustes")
    void putSinCamposConserva() throws Exception {
        long id = crear(ajuste(pedido(), "Flete", "PORCENTAJE", "10")
                .put("precioManual", true).put("precioFinal", new BigDecimal("500"))).get("id").asLong();

        JsonNode editado = cuerpo(editar(duenoA, id, pedido().put("costo", new BigDecimal("150")))
                .andExpect(status().isOk()));

        assertThat(editado.get("costo").decimalValue()).isEqualByComparingTo("150");
        assertThat(editado.get("precioManual").asBoolean()).isTrue();
        assertThat(editado.get("precioFinal").decimalValue()).isEqualByComparingTo("500");
        assertThat(editado.get("ajustesPrecio")).hasSize(1);
    }

    @Test
    @DisplayName("destildar el precio manual lo vuelve a calcular con los ajustes")
    void destildarRecalcula() throws Exception {
        long id = crear(ajuste(pedido(), "Flete", "PORCENTAJE", "10")
                .put("precioManual", true).put("precioFinal", new BigDecimal("500"))).get("id").asLong();

        JsonNode editado = cuerpo(editar(duenoA, id, pedido().put("precioManual", false)).andExpect(status().isOk()));

        assertThat(editado.get("precioManual").asBoolean()).isFalse();
        assertThat(editado.get("precioFinal").decimalValue()).isEqualByComparingTo(
                calculado(new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, new BigDecimal("10"))));
    }

    @Test
    @DisplayName("una lista vacia borra los ajustes")
    void listaVaciaBorra() throws Exception {
        long id = crear(ajuste(pedido(), "Flete", "PORCENTAJE", "10")).get("id").asLong();
        ObjectNode sinAjustes = pedido();
        sinAjustes.putArray("ajustesPrecio");

        JsonNode editado = cuerpo(editar(duenoA, id, sinAjustes).andExpect(status().isOk()));

        assertThat(editado.get("ajustesPrecio")).isEmpty();
        assertThat(editado.get("precioFinal").decimalValue()).isEqualByComparingTo(calculado());
    }

    @Test
    @DisplayName("el desglose trae la marca de precio manual y los ajustes sobre el precio final")
    void desglose() throws Exception {
        long id = crear(ajuste(pedido(), "Flete", "PORCENTAJE", "10")
                .put("precioManual", true).put("precioFinal", new BigDecimal("500"))).get("id").asLong();

        JsonNode desglose = getJson(URL + "/" + id + "/desglose", duenoA);

        assertThat(desglose.get("precioManual").asBoolean()).isTrue();
        assertThat(desglose.get("ajustesPrecio")).hasSize(1);
        assertThat(desglose.at("/ajustesPrecio/0/nombre").asText()).isEqualTo("Flete");
        assertThat(desglose.get("precioFinal").decimalValue()).isEqualByComparingTo("500");
    }

    // ---- validaciones ----

    @Test
    @DisplayName("ajustes invalidos dan 400 con un mensaje por campo y no crean nada")
    void ajustesInvalidos() throws Exception {
        ObjectNode pedido = pedido();
        ajuste(pedido, "x".repeat(41), "MONTO", "1");
        ajuste(pedido, null, null, "1");
        ajuste(pedido, null, "PORCENTAJE", null);
        ajuste(pedido, null, "MONTO", "1.555");

        postJson(URL, duenoA, pedido.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['ajustesPrecio[0].nombre']").value("El nombre del ajuste tiene hasta 40 caracteres."))
                .andExpect(jsonPath("$.errors['ajustesPrecio[1].tipo']").value("Elegí si el ajuste es en % o en $."))
                .andExpect(jsonPath("$.errors['ajustesPrecio[2].valor']").value("El valor del ajuste es obligatorio."))
                .andExpect(jsonPath("$.errors['ajustesPrecio[3].valor']").value("El valor del ajuste admite hasta 2 decimales."));
    }

    @Test
    @DisplayName("un porcentaje fuera de -99,99 a 1000 da 400 en su valor")
    void porcentajeFueraDeRango() throws Exception {
        postJson(URL, duenoA, ajuste(ajuste(pedido(), null, "PORCENTAJE", "10"), null, "PORCENTAJE", "1000.01").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['ajustesPrecio[1].valor']")
                        .value("El porcentaje del ajuste tiene que estar entre -99,99 y 1.000."));
    }

    @Test
    @DisplayName("ajustes que dejan el precio en 0 o menos dan 400")
    void ajustesEnCero() throws Exception {
        postJson(URL, duenoA, ajuste(pedido(), null, "MONTO", "-100000").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ajustesPrecio")
                        .value("Con los ajustes del precio final, el precio queda en 0 o menos."));
    }

    @Test
    @DisplayName("precio manual sin precio o en 0 da 400 en precioFinal")
    void precioManualSinPrecio() throws Exception {
        postJson(URL, duenoA, pedido().put("precioManual", true).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.precioFinal").value("El precio tiene que ser mayor a 0."));
        postJson(URL, duenoA, pedido().put("precioManual", true).put("precioFinal", BigDecimal.ZERO).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.precioFinal").value("El precio tiene que ser mayor a 0."));
    }

    // ---- permiso y aislamiento ----

    @Test
    @DisplayName("el Vendedor no edita (403) y su lectura no trae precio manual ni ajustes")
    void vendedor() throws Exception {
        long id = crear(ajuste(pedido(), "Flete", "PORCENTAJE", "10")).get("id").asLong();

        editar(vendedorA, id, pedido().put("precioManual", true).put("precioFinal", new BigDecimal("1")))
                .andExpect(status().isForbidden());
        JsonNode leido = getJson(URL + "/" + id, vendedorA);

        assertThat(leido.has("ajustesPrecio")).isFalse();
        assertThat(leido.has("precioManual")).isFalse();
        assertThat(precioEnBase(id)).isEqualByComparingTo(calculado(
                new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, new BigDecimal("10"))));
    }

    @Test
    @DisplayName("otra ferreteria no ve ni edita el precio manual ni los ajustes: 404 y no cambia nada")
    void otraFerreteria() throws Exception {
        long id = crear(ajuste(pedido(), "Flete", "PORCENTAJE", "10")).get("id").asLong();
        BigDecimal antes = precioEnBase(id);

        getResult(URL + "/" + id, duenoB).andExpect(status().isNotFound());
        editar(duenoB, id, ajuste(pedido(), null, "MONTO", "50").put("precioManual", true)
                .put("precioFinal", new BigDecimal("1"))).andExpect(status().isNotFound());

        assertThat(precioEnBase(id)).isEqualByComparingTo(antes);
        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, id)).isFalse();
    }
}
