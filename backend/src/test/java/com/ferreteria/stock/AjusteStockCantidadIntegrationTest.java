package com.ferreteria.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU-09 ajuste en modo CANTIDAD por HTTP contra MySQL real: suma o resta N al stock vigente al confirmar. */
class AjusteStockCantidadIntegrationTest extends IntegrationTestBase {

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
                .put("codigoInterno", unico("CAN"))
                .put("descripcion", "Articulo para ajustar por cantidad")
                .put("costo", 100)
                .put("unidad", unidad);
        String respuesta = postJson("/api/articulos", token, objectMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private String cantidad(String cantidad, String motivo, String detalle) throws Exception {
        var body = objectMapper.createObjectNode().put("modo", "CANTIDAD");
        if (cantidad != null) body.put("cantidad", new BigDecimal(cantidad));
        if (motivo != null) body.put("motivo", motivo);
        if (detalle != null) body.put("detalle", detalle);
        return objectMapper.writeValueAsString(body);
    }

    private String stockReal(String valor) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("modo", "STOCK_REAL").put("stockReal", new BigDecimal(valor)).put("motivo", "CONTEO"));
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
    @DisplayName("sumar 25 con 10: 201, anterior 10, diferencia +25, actual 35 y el cache cuadra")
    void suma() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");
        postJson(url(id), duenoA, stockReal("10")).andExpect(status().isCreated());

        postJson(url(id), duenoA, cantidad("25", "INGRESO_MERCADERIA", "remito 9"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.articuloId", is((int) id)))
                .andExpect(jsonPath("$.stockAnterior", is(10.0)))
                .andExpect(jsonPath("$.diferencia", is(25.0)))
                .andExpect(jsonPath("$.stockActual", is(35.0)));

        assertThat(stockEnBase(id)).isEqualByComparingTo("35");
        assertThat(sumaDeMovimientos(id)).isEqualByComparingTo("35");
    }

    @Test
    @DisplayName("restar 4,5 metros de 150: -4,5 y el movimiento guarda el motivo")
    void resta() throws Exception {
        long id = crearArticulo(duenoA, "METRO");
        postJson(url(id), duenoA, stockReal("150")).andExpect(status().isCreated());

        postJson(url(id), duenoA, cantidad("-4.5", "ROTURA", "se cortó mal"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.diferencia", is(-4.5)))
                .andExpect(jsonPath("$.stockActual", is(145.5)));

        assertThat(jdbc.queryForObject("select motivo from movimiento_stock where articulo_id = ? order by id desc limit 1",
                String.class, id)).isEqualTo("Rotura: se cortó mal");
        assertThat(stockEnBase(id)).isEqualByComparingTo(sumaDeMovimientos(id));
    }

    @Test
    @DisplayName("una venta hecha entre que se abrio el dialogo y se confirmo NO se pisa: se suma sobre el stock vigente")
    void ventaEntreLecturaYConfirmacion() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");
        postJson(url(id), duenoA, stockReal("10")).andExpect(status().isCreated());
        // El dialogo mostro "stock actual 10"; antes de confirmar el mostrador vendio 4.
        jdbc.update("update articulo set stock_actual = stock_actual - 4 where id = ?", id);
        jdbc.update("insert into movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, motivo, usuario_id) "
                + "select ferreteria_id, id, 'VENTA', -4, null, (select id from usuario where email = 'dueno@tornillo.demo') "
                + "from articulo where id = ?", id);

        postJson(url(id), duenoA, cantidad("25", "INGRESO_MERCADERIA", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockAnterior", is(6.0)))
                .andExpect(jsonPath("$.stockActual", is(31.0)));

        assertThat(stockEnBase(id)).isEqualByComparingTo("31");
        assertThat(sumaDeMovimientos(id)).isEqualByComparingTo("31");
    }

    @Test
    @DisplayName("con stock negativo por ventas, un ingreso parcial se registra y el cache cuadra")
    void ingresoConStockNegativo() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");
        // Se vendieron 4 sin stock: la venta lo permite y deja -4.
        jdbc.update("update articulo set stock_actual = stock_actual - 4 where id = ?", id);
        jdbc.update("insert into movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, motivo, usuario_id) "
                + "select ferreteria_id, id, 'VENTA', -4, null, (select id from usuario where email = 'dueno@tornillo.demo') "
                + "from articulo where id = ?", id);

        postJson(url(id), duenoA, cantidad("2", "INGRESO_MERCADERIA", "pedido parcial"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockAnterior", is(-4.0)))
                .andExpect(jsonPath("$.diferencia", is(2.0)))
                .andExpect(jsonPath("$.stockActual", is(-2.0)));

        assertThat(stockEnBase(id)).isEqualByComparingTo("-2");
        assertThat(sumaDeMovimientos(id)).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("sacar mas de lo que hay: 400 claro en cantidad y nada cambia")
    void noQuedaNegativo() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");
        postJson(url(id), duenoA, stockReal("3")).andExpect(status().isCreated());

        postJson(url(id), duenoA, cantidad("-5", "ROTURA", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cantidad", is("No podés sacar 5: hay 3 unidades.")));

        assertThat(stockEnBase(id)).isEqualByComparingTo("3");
        assertThat(movimientos(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("cantidad cero, decimales en UNIDAD y falta de cantidad dan errores por campo")
    void erroresPorCampo() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), duenoA, cantidad("0", "CONTEO", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cantidad", is("La cantidad no puede ser cero.")));
        postJson(url(id), duenoA, cantidad("1.5", "OTRO", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cantidad",
                        is("Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.")))
                .andExpect(jsonPath("$.errors.detalle", is("Contá el motivo del ajuste.")));
        postJson(url(id), duenoA, cantidad(null, "CONTEO", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cantidad", is("La cantidad es obligatoria.")));

        assertThat(movimientos(id)).isZero();
    }

    @Test
    @DisplayName("un modo que no existe da 400 con un mensaje claro")
    void modoInvalido() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("modo", "RESTAR").put("cantidad", 1).put("motivo", "CONTEO"));

        postJson(url(id), duenoA, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.modo", is("El modo no es válido. Usá STOCK_REAL o CANTIDAD.")));
    }

    @Test
    @DisplayName("un articulo de otra ferreteria da 404 en modo cantidad y no se toca")
    void otraFerreteria() throws Exception {
        long idDeB = crearArticulo(duenoB, "UNIDAD");

        postJson(url(idDeB), duenoA, cantidad("5", "INGRESO_MERCADERIA", null)).andExpect(status().isNotFound());

        assertThat(stockEnBase(idDeB)).isEqualByComparingTo("0");
        assertThat(movimientos(idDeB)).isZero();
    }

    @Test
    @DisplayName("un articulo dado de baja no se ajusta en ningun modo: 422 con el mensaje y nada cambia")
    void articuloDeBaja() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");
        postJson(url(id), duenoA, stockReal("10")).andExpect(status().isCreated());
        delete("/api/articulos/" + id, duenoA).andExpect(status().isNoContent());
        String mensaje = "\"Articulo para ajustar por cantidad\" está dado de baja. Reactivalo para ajustar el stock.";

        postJson(url(id), duenoA, cantidad("5", "INGRESO_MERCADERIA", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", is(mensaje)));
        postJson(url(id), duenoA, stockReal("20"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", is(mensaje)));

        assertThat(stockEnBase(id)).isEqualByComparingTo("10");
        assertThat(movimientos(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("el Vendedor no puede ajustar por cantidad: 403")
    void vendedorNoPuede() throws Exception {
        long id = crearArticulo(duenoA, "UNIDAD");

        postJson(url(id), vendedorA, cantidad("5", "INGRESO_MERCADERIA", null)).andExpect(status().isForbidden());

        assertThat(movimientos(id)).isZero();
    }
}
