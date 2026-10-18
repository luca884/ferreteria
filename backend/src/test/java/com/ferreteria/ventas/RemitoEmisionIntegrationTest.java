package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 (G55, RM-001 a RM-004): emitir un remito en {@code POST /api/remitos}, contra MySQL real. */
class RemitoEmisionIntegrationTest extends VentasTestBase {

    /** El cuerpo del POST: lo que manda el mostrador al emitir un remito. Sin forma de pago. */
    private Map<String, Object> cuerpo(String clave, String total, Linea... lineas) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave);
        List<Map<String, Object>> renglones = new ArrayList<>();
        for (Linea l : lineas) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("articuloId", l.articuloId());
            r.put("cantidad", new BigDecimal(l.cantidad()));
            r.put("precioUnitario", new BigDecimal(l.precio()));
            renglones.add(r);
        }
        cuerpo.put("renglones", renglones);
        cuerpo.put("totalMostrado", total == null ? null : new BigDecimal(total));
        return cuerpo;
    }

    private org.springframework.test.web.servlet.ResultActions emitir(String token, Map<String, Object> cuerpo)
            throws Exception {
        return postJson("/api/remitos", token, objectMapper.writeValueAsString(cuerpo));
    }

    private long remitos(Tienda tienda) {
        return jdbc.queryForObject("select count(*) from remito where ferreteria_id = ?", Long.class, tienda.id());
    }

    @Test
    @DisplayName("el vendedor emite un remito sin caja abierta: queda EMITIDO, descuenta el stock y deja un movimiento REMITO, sin venta")
    void emiteSinCajaYDescuentaElStock() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        Map<String, Object> pedido = cuerpo(clave(), "200.00", linea(a, "2", "100.00"));
        pedido.put("clienteNombre", "Obra Perez");
        pedido.put("observaciones", "Entrega el viernes");

        JsonNode remito = json(emitir(login(t.emailVendedor()), pedido).andExpect(status().isCreated()));

        assertThat(remito.get("numero").asLong()).isEqualTo(1);
        assertThat(remito.get("estado").asText()).isEqualTo("EMITIDO");
        assertThat(remito.get("clienteNombre").asText()).isEqualTo("Obra Perez");
        assertThat(remito.get("observaciones").asText()).isEqualTo("Entrega el viernes");
        assertThat(remito.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(remito.get("subtotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(remito.get("sinStock")).isEmpty();
        JsonNode renglon = remito.get("renglones").get(0);
        assertThat(renglon.get("descripcion").asText()).isEqualTo("Tornillo");
        assertThat(renglon.get("cantidad").decimalValue()).isEqualByComparingTo("2");
        assertThat(renglon.get("precioUnitario").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(renglon.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(remitos(t)).isEqualTo(1);
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("8");
        Map<String, Object> movimiento = jdbc.queryForMap(
                "select tipo, cantidad, remito_id, comprobante_id from movimiento_stock where articulo_id = ?", a);
        assertThat(movimiento.get("tipo")).isEqualTo("REMITO");
        assertThat((BigDecimal) movimiento.get("cantidad")).isEqualByComparingTo("-2");
        assertThat(((Number) movimiento.get("remito_id")).longValue()).isEqualTo(remito.get("remitoId").asLong());
        assertThat(movimiento.get("comprobante_id")).isNull();
        Map<String, Object> fila = jdbc.queryForMap("select r.estado, r.usuario_id, u.rol from remito r "
                + "join usuario u on u.id = r.usuario_id where r.id = ?", remito.get("remitoId").asLong());
        assertThat(fila.get("estado")).isEqualTo("EMITIDO");
        assertThat(fila.get("rol")).isEqualTo("VENDEDOR");
    }

    @Test
    @DisplayName("el Dueno tambien emite")
    void duenoEmite() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        emitir(login(t.emailDueno()), cuerpo(clave(), "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated());

        assertThat(stock(a)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("un renglon con varios articulos descuenta cada uno y deja un movimiento por articulo")
    void variosArticulos() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Cano", "METRO", "50.00", "20");

        emitir(login(t.emailVendedor()), cuerpo(clave(), "325.00", linea(a, "2", "100.00"), linea(b, "2.5", "50.00")))
                .andExpect(status().isCreated());

        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(stock(b)).isEqualByComparingTo("17.5");
        assertThat(movimientos(t)).isEqualTo(2);
    }

    @Test
    @DisplayName("la numeracion es propia de cada ferreteria y no se mezcla con la de las ventas")
    void numeracionPropia() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(otra, "Clavo", "UNIDAD", "50.00", "10");
        String token = login(t.emailVendedor());

        vender(token, clave(), "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());
        JsonNode primero = json(emitir(token, cuerpo(clave(), "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated()));
        JsonNode segundo = json(emitir(token, cuerpo(clave(), "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated()));
        JsonNode deOtra = json(emitir(login(otra.emailVendedor()), cuerpo(clave(), "50.00", linea(b, "1", "50.00")))
                .andExpect(status().isCreated()));

        assertThat(primero.get("numero").asLong()).isEqualTo(1);
        assertThat(segundo.get("numero").asLong()).isEqualTo(2);
        assertThat(deOtra.get("numero").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("con descuento por renglon y global el total sale como en la venta: 2 x 100 con 10 % y 10 % da 162")
    void conDescuentos() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        Map<String, Object> pedido = cuerpo(clave(), "162.00", linea(a, "2", "100.00"));
        pedido.put("descuentoPorcentaje", new BigDecimal("10.00"));
        ((Map<String, Object>) ((List<?>) pedido.get("renglones")).get(0))
                .put("descuentoPorcentaje", new BigDecimal("10.00"));

        JsonNode remito = json(emitir(login(t.emailVendedor()), pedido).andExpect(status().isCreated()));

        assertThat(remito.get("total").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(remito.get("subtotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(remito.get("descuento").decimalValue()).isEqualByComparingTo("38.00");
        assertThat(remito.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("10.00");
        Map<String, Object> fila = jdbc.queryForMap("select neto, iva, total from remito where id = ?",
                remito.get("remitoId").asLong());
        assertThat(((BigDecimal) fila.get("neto")).add((BigDecimal) fila.get("iva"))).isEqualByComparingTo("162.00");
    }

    @Test
    @DisplayName("un remito de un articulo sin stock suficiente se emite igual: el stock queda negativo y lo avisa")
    void sinStockSePermiteYAvisa() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "1");
        long b = articulo(t, "Clavo", "UNIDAD", "10.00", "10");

        JsonNode remito = json(emitir(login(t.emailVendedor()),
                cuerpo(clave(), "510.00", linea(a, "5", "100.00"), linea(b, "1", "10.00")))
                .andExpect(status().isCreated()));

        assertThat(stock(a)).isEqualByComparingTo("-4");
        assertThat(stock(b)).isEqualByComparingTo("9");
        assertThat(remito.get("sinStock")).hasSize(1);
        assertThat(remito.get("sinStock").get(0).get("articuloId").asLong()).isEqualTo(a);
        assertThat(remito.get("sinStock").get(0).get("stockActual").decimalValue()).isEqualByComparingTo("-4");
    }

    @Test
    @DisplayName("si cambio el precio, 409 PRECIOS_CAMBIADOS con el total actual y no guarda ni descuenta nada ni gasta el numero")
    void preciosCambiadosDa409() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        JsonNode rechazo = json(emitir(token, cuerpo(clave(), "90.00", linea(a, "1", "90.00")))
                .andExpect(status().isConflict()));
        assertThat(stock(a)).isEqualByComparingTo("10");
        JsonNode siguiente = json(emitir(token, cuerpo(clave(), "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("total").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(rechazo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(siguiente.get("numero").asLong()).isEqualTo(1);
        assertThat(remitos(t)).isEqualTo(1);
        assertThat(movimientos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("un articulo inexistente o de otra ferreteria da 404; uno dado de baja o sin precio, 422; no se mueve el stock")
    void rechazosDeArticulos() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long ajeno = articulo(otra, "Ajeno", "UNIDAD", "100.00", "10");
        long baja = articulo(t, "Viejo", "UNIDAD", "100.00", "10", "IVA_21", false);
        long sinPrecio = articulo(t, "Gratis", "UNIDAD", "0.00", "10");
        String token = login(t.emailVendedor());

        JsonNode inexistente = json(emitir(token, cuerpo(clave(), "100.00", linea(ajeno, "1", "100.00")))
                .andExpect(status().isNotFound()));
        JsonNode dadoDeBaja = json(emitir(token, cuerpo(clave(), "100.00", linea(baja, "1", "100.00")))
                .andExpect(status().isUnprocessableEntity()));
        JsonNode gratis = json(emitir(token, cuerpo(clave(), "0.00", linea(sinPrecio, "1", "0.00")))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(inexistente.get("motivo").asText()).isEqualTo("ARTICULO_INEXISTENTE");
        assertThat(dadoDeBaja.get("motivo").asText()).isEqualTo("ARTICULO_DADO_DE_BAJA");
        assertThat(gratis.get("motivo").asText()).isEqualTo("ARTICULO_SIN_PRECIO");
        assertThat(remitos(t)).isZero();
        assertThat(stock(ajeno)).isEqualByComparingTo("10");
        assertThat(stock(baja)).isEqualByComparingTo("10");
        assertThat(stock(sinPrecio)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("cantidad invalida, descuento fuera de rango y articulo repetido dan 400 en su campo")
    void validacionesDelTicket() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        JsonNode cantidad = json(emitir(token, cuerpo(clave(), "150.00", linea(a, "1.5", "100.00")))
                .andExpect(status().isBadRequest()));
        Map<String, Object> conDescuento = cuerpo(clave(), "0.00", linea(a, "1", "100.00"));
        conDescuento.put("descuentoPorcentaje", new BigDecimal("100.00"));
        JsonNode descuento = json(emitir(token, conDescuento).andExpect(status().isBadRequest()));
        JsonNode repetido = json(emitir(token, cuerpo(clave(), "200.00", linea(a, "1", "100.00"),
                linea(a, "1", "100.00"))).andExpect(status().isBadRequest()));

        assertThat(cantidad.get("errors").has("renglones[0].cantidad")).isTrue();
        assertThat(descuento.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("El descuento tiene que estar entre 0 y 99,99 %.");
        assertThat(repetido.get("errors").has("renglones[1].articuloId")).isTrue();
        assertThat(remitos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("sin renglones, con la clave rota o con textos demasiado largos da 400")
    void validacionesDelPedido() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        JsonNode sinRenglones = json(emitir(token, cuerpo(clave(), "0.00")).andExpect(status().isBadRequest()));
        JsonNode claveRota = json(emitir(token, cuerpo("no-es-un-uuid", "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isBadRequest()));
        Map<String, Object> largo = cuerpo(clave(), "100.00", linea(a, "1", "100.00"));
        largo.put("clienteNombre", "x".repeat(81));
        largo.put("observaciones", "y".repeat(201));
        JsonNode textos = json(emitir(token, largo).andExpect(status().isBadRequest()));

        assertThat(sinRenglones.get("errors").has("renglones")).isTrue();
        assertThat(claveRota.get("errors").has("clave")).isTrue();
        assertThat(textos.get("errors").has("clienteNombre")).isTrue();
        assertThat(textos.get("errors").has("observaciones")).isTrue();
        assertThat(remitos(t)).isZero();
    }

    @Test
    @DisplayName("repetir el POST devuelve el mismo remito (200) sin descontar de nuevo; con otro contenido, 422")
    void idempotencia() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();

        JsonNode primera = json(emitir(token, cuerpo(clave, "200.00", linea(a, "2", "100.00")))
                .andExpect(status().isCreated()));
        JsonNode repetida = json(emitir(token, cuerpo(clave, "200.00", linea(a, "2", "100.00")))
                .andExpect(status().isOk()));
        JsonNode otroContenido = json(emitir(token, cuerpo(clave, "300.00", linea(a, "3", "100.00")))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(repetida.get("remitoId").asLong()).isEqualTo(primera.get("remitoId").asLong());
        assertThat(repetida.get("numero").asLong()).isEqualTo(1);
        assertThat(repetida.get("sinStock")).isEmpty();
        assertThat(otroContenido.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(otroContenido.get("message").asText()).contains("remito");
        assertThat(remitos(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(movimientos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma clave en otra ferreteria es otro remito")
    void claveDeOtraFerreteria() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(otra, "Clavo", "UNIDAD", "100.00", "10");
        String clave = clave();

        emitir(login(t.emailVendedor()), cuerpo(clave, "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated());
        emitir(login(otra.emailVendedor()), cuerpo(clave, "100.00", linea(b, "1", "100.00")))
                .andExpect(status().isCreated());

        assertThat(remitos(t)).isEqualTo(1);
        assertThat(remitos(otra)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin sesion da 401")
    void sinSesion() throws Exception {
        mvc.perform(post("/api/remitos").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
