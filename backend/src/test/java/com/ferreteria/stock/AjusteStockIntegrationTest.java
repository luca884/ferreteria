package com.ferreteria.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CU-09 ajustar stock por HTTP contra MySQL real: solo el Dueno, con el stock real contado y un motivo. Deja un
 * movimiento AJUSTE y el cache del articulo sigue siendo la suma de sus movimientos. A = El Tornillo, B = La Tuerca.
 */
class AjusteStockIntegrationTest extends IntegrationTestBase {

    private String duenoA;
    private String duenoB;
    private String vendedorA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
        vendedorA = login("vendedor@tornillo.demo");
    }

    private long crearArticulo(String token, String unidad) throws Exception {
        var body = objectMapper.createObjectNode()
                .put("codigoInterno", unico("AJU"))
                .put("descripcion", "Articulo para ajustar")
                .put("costo", 100)
                .put("unidad", unidad);
        String respuesta = postJson("/api/articulos", token, objectMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private String ajuste(String stockReal, String motivo, String detalle) throws Exception {
        var body = objectMapper.createObjectNode();
        if (stockReal != null) body.put("stockReal", new BigDecimal(stockReal));
        if (motivo != null) body.put("motivo", motivo);
        if (detalle != null) body.put("detalle", detalle);
        return objectMapper.writeValueAsString(body);
    }

    private String url(long id) {
        return "/api/articulos/" + id + "/ajustes-stock";
    }

    private BigDecimal stockEnBase(long id) {
        return jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, id);
    }

    private BigDecimal sumaDeMovimientos(long id) {
        return jdbc.queryForObject("select coalesce(sum(cantidad), 0) from movimiento_stock where articulo_id = ?",
                BigDecimal.class, id);
    }

    private long movimientos(long id) {
        return jdbc.queryForObject("select count(*) from movimiento_stock where articulo_id = ?", Long.class, id);
    }

    @Test
    @DisplayName("el Dueno carga 50 contados en un articulo nuevo: 201, +50, el listado muestra 50 y queda el movimiento")
    void ingresoDeMercaderia() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), duenoA, ajuste("50", "INGRESO_MERCADERIA", "remito 123"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.articuloId", is((int) id)))
                .andExpect(jsonPath("$.stockAnterior", is(0.0)))
                .andExpect(jsonPath("$.diferencia", is(50.0)))
                .andExpect(jsonPath("$.stockActual", is(50.0)));

        getResult("/api/articulos/" + id, duenoA).andExpect(jsonPath("$.stockActual", is(50.0)));
        var fila = jdbc.queryForMap("select m.tipo, m.cantidad, m.motivo, m.comprobante_id, u.email "
                + "from movimiento_stock m join usuario u on u.id = m.usuario_id where m.articulo_id = ?", id);
        assertThat(fila.get("tipo")).isEqualTo("AJUSTE");
        assertThat((BigDecimal) fila.get("cantidad")).isEqualByComparingTo("50");
        assertThat(fila.get("motivo")).isEqualTo("Ingreso de mercadería: remito 123");
        assertThat(fila.get("comprobante_id")).isNull();
        assertThat(fila.get("email")).isEqualTo("dueno@tornillo.demo");
        assertThat(stockEnBase(id)).isEqualByComparingTo(sumaDeMovimientos(id));
    }

    @Test
    @DisplayName("dos ajustes seguidos: rotura en metros con decimales y el cache cuadra con los movimientos")
    void roturaConDecimales() throws Exception {
        long id = crearArticulo(duenoA, "METRO");
        postJson(url(id), duenoA, ajuste("150", "CONTEO", null)).andExpect(status().isCreated());

        postJson(url(id), duenoA, ajuste("145.5", "ROTURA", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockAnterior", is(150.0)))
                .andExpect(jsonPath("$.diferencia", is(-4.5)))
                .andExpect(jsonPath("$.stockActual", is(145.5)));

        assertThat(stockEnBase(id)).isEqualByComparingTo("145.5");
        assertThat(sumaDeMovimientos(id)).isEqualByComparingTo("145.5");
        assertThat(jdbc.queryForList("select motivo from movimiento_stock where articulo_id = ? order by id",
                String.class, id)).isEqualTo(List.of("Conteo", "Rotura"));
    }

    @Test
    @DisplayName("UNIDAD con decimales y OTRO sin detalle: 400 con los dos errores por campo y nada cambia")
    void erroresPorCampo() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), duenoA, ajuste("2.5", "OTRO", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stockReal",
                        is("Este artículo se cuenta por unidad: el stock real tiene que ser un número entero.")))
                .andExpect(jsonPath("$.errors.detalle", is("Contá el motivo del ajuste.")));

        assertThat(stockEnBase(id)).isEqualByComparingTo("0");
        assertThat(movimientos(id)).isZero();
    }

    @Test
    @DisplayName("sin stock real ni motivo: 400 con un mensaje por campo")
    void faltanDatos() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), duenoA, ajuste(null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stockReal", is("El stock real es obligatorio.")))
                .andExpect(jsonPath("$.errors.motivo", is("Elegí el motivo del ajuste.")));
    }

    @Test
    @DisplayName("un motivo que no existe da 400 con un mensaje claro")
    void motivoInvalido() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), duenoA, ajuste("3", "ROBO", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.motivo",
                        is("El motivo no es válido. Usá INGRESO_MERCADERIA, ROTURA, CONTEO u OTRO.")));
    }

    @Test
    @DisplayName("el mismo stock que el actual da 400 y no deja movimiento")
    void sinDiferencia() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), duenoA, ajuste("0", "CONTEO", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stockReal", is("El stock real es igual al actual: no hay nada que ajustar.")));

        assertThat(movimientos(id)).isZero();
    }

    @Test
    @DisplayName("ajustar un articulo de otra ferreteria da 404 y no lo toca")
    void articuloDeOtraFerreteria() throws Exception {
        long idDeB = crearArticulo(duenoB, "UNIDAD");

        postJson(url(idDeB), duenoA, ajuste("99", "CONTEO", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));

        assertThat(stockEnBase(idDeB)).isEqualByComparingTo("0");
        assertThat(movimientos(idDeB)).isZero();
    }

    @Test
    @DisplayName("un articulo inexistente da 404")
    void articuloInexistente() throws Exception {
        postJson(url(999_999_999L), duenoA, ajuste("1", "CONTEO", null)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el Vendedor no puede ajustar: 403 y el stock no cambia")
    void vendedorNoPuede() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), vendedorA, ajuste("10", "CONTEO", null)).andExpect(status().isForbidden());

        assertThat(stockEnBase(id)).isEqualByComparingTo("0");
        assertThat(movimientos(id)).isZero();
    }
}
