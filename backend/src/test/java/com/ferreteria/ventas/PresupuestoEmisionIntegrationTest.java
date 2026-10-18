package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 (GDS-13, PP-001 a PP-004): emitir un presupuesto en {@code POST /api/presupuestos}, contra MySQL real. */
class PresupuestoEmisionIntegrationTest extends VentasTestBase {

    /** El cuerpo del POST: lo que manda el mostrador al guardar un presupuesto. Sin forma de pago. */
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
        return postJson("/api/presupuestos", token, objectMapper.writeValueAsString(cuerpo));
    }

    private long presupuestos(Tienda tienda) {
        return jdbc.queryForObject("select count(*) from presupuesto where ferreteria_id = ?", Long.class, tienda.id());
    }

    @Test
    @DisplayName("el vendedor emite un presupuesto sin caja abierta: queda pendiente, vence en 15 dias y no toca stock ni ventas")
    void emiteSinCajaYSinTocarNada() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        Map<String, Object> pedido = cuerpo(clave(), "200.00", linea(a, "2", "100.00"));
        pedido.put("clienteNombre", "Obra Perez");
        pedido.put("observaciones", "Entrega el viernes");

        JsonNode presupuesto = json(emitir(login(t.emailVendedor()), pedido).andExpect(status().isCreated()));

        assertThat(presupuesto.get("numero").asLong()).isEqualTo(1);
        assertThat(presupuesto.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(presupuesto.get("vencido").asBoolean()).isFalse();
        assertThat(presupuesto.get("vence").asText()).isEqualTo(HoraArgentina.ahora().toLocalDate().plusDays(15).toString());
        assertThat(presupuesto.get("clienteNombre").asText()).isEqualTo("Obra Perez");
        assertThat(presupuesto.get("observaciones").asText()).isEqualTo("Entrega el viernes");
        assertThat(presupuesto.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(presupuesto.get("subtotal").decimalValue()).isEqualByComparingTo("200.00");
        JsonNode renglon = presupuesto.get("renglones").get(0);
        assertThat(renglon.get("descripcion").asText()).isEqualTo("Tornillo");
        assertThat(renglon.get("cantidad").decimalValue()).isEqualByComparingTo("2");
        assertThat(renglon.get("precioUnitario").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(renglon.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(presupuestos(t)).isEqualTo(1);
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        Map<String, Object> fila = jdbc.queryForMap("select p.estado, p.usuario_id, u.rol from presupuesto p "
                + "join usuario u on u.id = p.usuario_id where p.id = ?", presupuesto.get("presupuestoId").asLong());
        assertThat(fila.get("estado")).isEqualTo("PENDIENTE");
        assertThat(fila.get("rol")).isEqualTo("VENDEDOR");
    }

    @Test
    @DisplayName("el Dueno tambien emite; los dias de validez se pueden elegir")
    void duenoEmiteConValidezPropia() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        Map<String, Object> pedido = cuerpo(clave(), "100.00", linea(a, "1", "100.00"));
        pedido.put("diasValidez", 30);

        JsonNode presupuesto = json(emitir(login(t.emailDueno()), pedido).andExpect(status().isCreated()));

        assertThat(LocalDate.parse(presupuesto.get("vence").asText()))
                .isEqualTo(HoraArgentina.ahora().toLocalDate().plusDays(30));
    }

    @Test
    @DisplayName("los dias de validez van de 1 a 90: 0 y 91 dan 400 en su campo y no guardan nada")
    void validezFueraDeRango() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        for (int dias : new int[] {0, 91}) {
            Map<String, Object> pedido = cuerpo(clave(), "100.00", linea(a, "1", "100.00"));
            pedido.put("diasValidez", dias);
            JsonNode error = json(emitir(token, pedido).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("diasValidez").asText())
                    .isEqualTo("La validez va de 1 a 90 días.");
        }
        assertThat(presupuestos(t)).isZero();
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

        JsonNode presupuesto = json(emitir(login(t.emailVendedor()), pedido).andExpect(status().isCreated()));

        assertThat(presupuesto.get("total").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(presupuesto.get("subtotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(presupuesto.get("descuento").decimalValue()).isEqualByComparingTo("38.00");
        assertThat(presupuesto.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("10.00");
        Map<String, Object> fila = jdbc.queryForMap("select neto, iva, total from presupuesto where id = ?",
                presupuesto.get("presupuestoId").asLong());
        assertThat(((BigDecimal) fila.get("neto")).add((BigDecimal) fila.get("iva"))).isEqualByComparingTo("162.00");
    }

    @Test
    @DisplayName("un presupuesto de un articulo sin stock suficiente se emite igual")
    void sinStockSePermite() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "1");

        emitir(login(t.emailVendedor()), cuerpo(clave(), "500.00", linea(a, "5", "100.00")))
                .andExpect(status().isCreated());

        assertThat(stock(a)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("si cambio el precio, 409 PRECIOS_CAMBIADOS con el total actual y no guarda nada ni gasta el numero")
    void preciosCambiadosDa409() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        JsonNode rechazo = json(emitir(token, cuerpo(clave(), "90.00", linea(a, "1", "90.00")))
                .andExpect(status().isConflict()));
        JsonNode siguiente = json(emitir(token, cuerpo(clave(), "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("total").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(rechazo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(siguiente.get("numero").asLong()).isEqualTo(1);
        assertThat(presupuestos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("un articulo inexistente o de otra ferreteria da 404; uno dado de baja o sin precio, 422")
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
        assertThat(presupuestos(t)).isZero();
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
        assertThat(presupuestos(t)).isZero();
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
        assertThat(presupuestos(t)).isZero();
    }

    @Test
    @DisplayName("repetir el POST devuelve el mismo presupuesto (200); con otro contenido, 422")
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

        assertThat(repetida.get("presupuestoId").asLong()).isEqualTo(primera.get("presupuestoId").asLong());
        assertThat(repetida.get("numero").asLong()).isEqualTo(1);
        assertThat(otroContenido.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(otroContenido.get("message").asText()).contains("presupuesto");
        assertThat(presupuestos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma clave en otra ferreteria es otro presupuesto")
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

        assertThat(presupuestos(t)).isEqualTo(1);
        assertThat(presupuestos(otra)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin sesion da 401")
    void sinSesion() throws Exception {
        mvc.perform(post("/api/presupuestos").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
