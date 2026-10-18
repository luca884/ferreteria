package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 corte 1: contrato HTTP de {@code POST /api/ventas} y sus reglas de negocio contra MySQL real. */
class VentaIntegrationTest extends VentasTestBase {

    // ---------------------------------------------------------------- T-15: contrato y validacion

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(post("/api/ventas").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renglonesVaciosDan400ConMensajeEnEspanolYNoGuardanNada() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());

        JsonNode cuerpo = json(postJson("/api/ventas", token, cuerpo(clave(), "EFECTIVO", "0.00"))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones").asText()).isEqualTo("El ticket no tiene artículos.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void claveNulaDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                cuerpo(null, "EFECTIVO", "100.00", linea(a, "1", "100.00"))).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("clave").asText()).isEqualTo("Falta la clave de la venta.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void claveQueNoEsUuidDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                cuerpo("no-soy-un-uuid", "EFECTIVO", "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("clave").asText()).isEqualTo("La clave de la venta no es válida.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void formaDePagoInexistenteDa400ConMensajeEnEspanol() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                cuerpo(clave(), "CHEQUE", "100.00", linea(a, "1", "100.00"))).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("formaPagoId").asText()).isEqualTo("La forma de pago no es válida.");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void faltaLaFormaDePagoDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                cuerpo(clave(), null, "100.00", linea(a, "1", "100.00"))).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("formaPagoId").asText()).isEqualTo("Elegí una forma de pago.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void articuloRepetidoDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "200.00",
                linea(a, "1", "100.00"), linea(a, "1", "100.00")).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").has("renglones[1].articuloId")).isTrue();
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void precioMostradoNegativoDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "100.00", linea(a, "1", "-100.00"))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].precioUnitario").asText())
                .isEqualTo("El precio no puede ser negativo.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void faltaElTotalMostradoDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), null, linea(a, "1", "100.00"))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("totalMostrado").asText()).isEqualTo("Falta el total.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void masDeQuinientosRenglonesDa400() throws Exception {
        Tienda t = tienda();
        Linea[] lineas = new Linea[501];
        for (int i = 0; i < lineas.length; i++) {
            lineas[i] = linea(i + 1L, "1", "1.00");
        }

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "501.00", lineas)
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones").asText()).isEqualTo("El ticket admite hasta 500 renglones.");
    }

    @Test
    void laFerreteriaYElUsuarioDelCuerpoSeIgnoranYSalenDelToken() throws Exception {
        Tienda propia = tienda();
        Tienda ajena = tienda();
        long a = articulo(propia, "Tornillo", "UNIDAD", "100.00", "10");
        ultimaTienda = propia;
        Map<String, Object> cuerpo = mapa(clave(), "EFECTIVO", "100.00", linea(a, "1", "100.00"));
        cuerpo.put("ferreteriaId", ajena.id());
        cuerpo.put("usuarioId", 1);

        postJson("/api/ventas", login(propia.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated());

        assertThat(comprobantes(propia)).isEqualTo(1);
        assertThat(comprobantes(ajena)).isZero();
        long usuario = jdbc.queryForObject("select usuario_id from comprobante where ferreteria_id = ?", Long.class,
                propia.id());
        long esperado = jdbc.queryForObject("select id from usuario where email = ?", Long.class,
                propia.emailVendedor());
        assertThat(usuario).isEqualTo(esperado);
    }

    // ---------------------------------------------------------------- T-16: camino feliz y reglas de negocio

    @Test
    void elVendedorCobraYRecibe201ConNumeroTotalYRenglonesSinCostoNiMargen() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo 1/4", "UNIDAD", "1210.00", "10");

        var resultado = vender(login(t.emailVendedor()), clave(), "3630.00", linea(a, "3", "1210.00"))
                .andExpect(status().isCreated());
        String texto = resultado.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode venta = objectMapper.readTree(texto);

        assertThat(venta.get("ventaId").asLong()).isPositive();
        assertThat(venta.get("numero").asLong()).isEqualTo(1);
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("3630.00");
        assertThat(venta.get("medioPago").asText()).isEqualTo("EFECTIVO");
        assertThat(venta.get("fecha").asText()).isNotBlank();
        assertThat(venta.get("renglones")).hasSize(1);
        assertThat(venta.get("renglones").get(0).get("descripcion").asText()).isEqualTo("Tornillo 1/4");
        assertThat(venta.get("sinStock")).isEmpty();
        assertThat(texto).doesNotContain("costo").doesNotContain("margen");
        assertThat(stock(a)).isEqualByComparingTo("7");
    }

    @Test
    void elDuenoTambienCobra() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(vender(login(t.emailDueno()), clave(), "200.00", linea(a, "2", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("200.00");
    }

    @Test
    void guardaComprobanteItemPagoYMovimientoConLosDatosDelMomento() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo 1/4", "UNIDAD", "1210.00", "10");

        vender(login(t.emailVendedor()), clave(), "3630.00", linea(a, "3", "1210.00")).andExpect(status().isCreated());

        Map<String, Object> comprobante = jdbc.queryForMap(
                "select tipo, estado, numero, total, neto, iva, cliente_id from comprobante where ferreteria_id = ?", t.id());
        assertThat(comprobante.get("tipo")).isEqualTo("VENTA");
        assertThat(comprobante.get("estado")).isEqualTo("SIN_FACTURAR");
        assertThat(comprobante.get("cliente_id")).isNull();
        assertThat((java.math.BigDecimal) comprobante.get("total")).isEqualByComparingTo("3630.00");
        assertThat((java.math.BigDecimal) comprobante.get("neto")).isEqualByComparingTo("3000.00");
        assertThat((java.math.BigDecimal) comprobante.get("iva")).isEqualByComparingTo("630.00");

        Map<String, Object> item = jdbc.queryForMap("select * from item_comprobante where ferreteria_id = ?", t.id());
        assertThat(item.get("descripcion")).isEqualTo("Tornillo 1/4");
        assertThat(item.get("unidad")).isEqualTo("UNIDAD");
        assertThat(item.get("alicuota_iva")).isEqualTo("IVA_21");
        assertThat((java.math.BigDecimal) item.get("cantidad")).isEqualByComparingTo("3");
        assertThat((java.math.BigDecimal) item.get("precio_unitario")).isEqualByComparingTo("1210.00");
        assertThat((java.math.BigDecimal) item.get("total")).isEqualByComparingTo("3630.00");

        Map<String, Object> pago = jdbc.queryForMap("select * from pago where ferreteria_id = ?", t.id());
        assertThat(pago.get("medio")).isEqualTo("EFECTIVO");
        assertThat((java.math.BigDecimal) pago.get("monto")).isEqualByComparingTo("3630.00");

        Map<String, Object> mov = jdbc.queryForMap("select * from movimiento_stock where ferreteria_id = ?", t.id());
        assertThat(mov.get("tipo")).isEqualTo("VENTA");
        assertThat((java.math.BigDecimal) mov.get("cantidad")).isEqualByComparingTo("-3");
    }

    @Test
    void elCambioPosteriorDelArticuloNoAlteraElItemGuardado() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo 1/4", "UNIDAD", "1210.00", "10");
        vender(login(t.emailVendedor()), clave(), "1210.00", linea(a, "1", "1210.00")).andExpect(status().isCreated());

        jdbc.update("update articulo set precio_final = 1300.00, descripcion = 'Otro nombre' where id = ?", a);

        Map<String, Object> item = jdbc.queryForMap("select * from item_comprobante where ferreteria_id = ?", t.id());
        assertThat(item.get("descripcion")).isEqualTo("Tornillo 1/4");
        assertThat((java.math.BigDecimal) item.get("precio_unitario")).isEqualByComparingTo("1210.00");
    }

    @Test
    void laAlicuotaLaLeeElServidorYNoElCliente() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Cable", "UNIDAD", "80.00", "10", "IVA_10_5", true);
        Map<String, Object> cuerpo = mapa(clave(), "EFECTIVO", "80.00", linea(a, "1", "80.00"));
        @SuppressWarnings("unchecked")
        Map<String, Object> renglon = ((java.util.List<Map<String, Object>>) cuerpo.get("renglones")).get(0);
        renglon.put("alicuotaIva", "IVA_0");

        postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated());

        Map<String, Object> item = jdbc.queryForMap("select * from item_comprobante where ferreteria_id = ?", t.id());
        assertThat(item.get("alicuota_iva")).isEqualTo("IVA_10_5");
        assertThat((java.math.BigDecimal) item.get("neto")).isEqualByComparingTo("72.40");
        assertThat((java.math.BigDecimal) item.get("iva")).isEqualByComparingTo("7.60");
    }

    @Test
    void sinStockSuficienteVendeIgualYAvisaConElStockNegativo() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro", "UNIDAD", "100.00", "2");

        JsonNode venta = json(vender(login(t.emailVendedor()), clave(), "500.00", linea(a, "5", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(stock(a)).isEqualByComparingTo("-3");
        assertThat(venta.get("sinStock")).hasSize(1);
        assertThat(venta.get("sinStock").get(0).get("articuloId").asLong()).isEqualTo(a);
        assertThat(venta.get("sinStock").get(0).get("descripcion").asText()).isEqualTo("Taladro");
        assertThat(venta.get("sinStock").get(0).get("stockActual").decimalValue()).isEqualByComparingTo("-3");
    }

    @Test
    void quedarJustoEnCeroNoAvisa() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro", "UNIDAD", "100.00", "5");

        JsonNode venta = json(vender(login(t.emailVendedor()), clave(), "500.00", linea(a, "5", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(stock(a)).isEqualByComparingTo("0");
        assertThat(venta.get("sinStock")).isEmpty();
    }

    @Test
    void dosMetrosYMedioDejanUnMovimientoDeMenos2500() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Cable unipolar", "METRO", "333.33", "100");

        vender(login(t.emailVendedor()), clave(), "833.33", linea(a, "2.5", "333.33")).andExpect(status().isCreated());

        java.math.BigDecimal cantidad = jdbc.queryForObject(
                "select cantidad from movimiento_stock where ferreteria_id = ?", java.math.BigDecimal.class, t.id());
        assertThat(cantidad).isEqualByComparingTo("-2.500");
        assertThat(stock(a)).isEqualByComparingTo("97.5");
    }

    @Test
    void cantidadDecimalEnUnArticuloPorUnidadDa400SinGuardarNada() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo 1/4", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "250.00", linea(a, "2.5", "100.00"))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].cantidad").asText())
                .startsWith("\"Tornillo 1/4\" se vende por unidad");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void precioCambiadoDa409SinGuardarNadaYElReintentoConLaMismaClaveYDatosNuevosCobra() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Martillo", "UNIDAD", "1500.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        jdbc.update("update articulo set precio_final = 1650.00 where id = ?", a);

        JsonNode rechazo = json(vender(token, clave, "1500.00", linea(a, "1", "1500.00"))
                .andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("total").decimalValue()).isEqualByComparingTo("1650.00");
        JsonNode articulo = rechazo.get("articulos").get(0);
        assertThat(articulo.get("articuloId").asLong()).isEqualTo(a);
        assertThat(articulo.get("descripcion").asText()).isEqualTo("Martillo");
        assertThat(articulo.get("precioMostrado").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(articulo.get("precioActual").decimalValue()).isEqualByComparingTo("1650.00");
        assertThat(articulo.get("totalRenglon").decimalValue()).isEqualByComparingTo("1650.00");
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");

        JsonNode venta = json(vender(token, clave, "1650.00", linea(a, "1", "1650.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("1650.00");
        assertThat(comprobantes(t)).isEqualTo(1);
    }

    @Test
    void articuloConPrecioCeroDa422ConSuNombreYNoVende() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long cero = articulo(t, "Arandela suelta", "UNIDAD", "0.00", "10");

        JsonNode rechazo = json(vender(login(t.emailVendedor()), clave(), "100.00",
                linea(a, "1", "100.00"), linea(cero, "1", "0.00")).andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("ARTICULO_SIN_PRECIO");
        assertThat(rechazo.get("message").asText()).contains("Arandela suelta");
        assertThat(rechazo.get("articulos").get(0).get("articuloId").asLong()).isEqualTo(cero);
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(stock(cero)).isEqualByComparingTo("10");
    }

    @Test
    void articuloDadoDeBajaDa422ConSuNombreYDejaIntactoElOtro() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long baja = articulo(t, "Martillo viejo", "UNIDAD", "200.00", "10", "IVA_21", false);

        JsonNode rechazo = json(vender(login(t.emailVendedor()), clave(), "300.00",
                linea(a, "1", "100.00"), linea(baja, "1", "200.00")).andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("ARTICULO_DADO_DE_BAJA");
        assertThat(rechazo.get("message").asText()).contains("Martillo viejo");
        assertThat(rechazo.get("articulos").get(0).get("articuloId").asLong()).isEqualTo(baja);
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(stock(baja)).isEqualByComparingTo("10");
    }

    @Test
    void articuloInexistenteYAjenoDanElMismo404SinDatosDelArticulo() throws Exception {
        Tienda propia = tienda();
        Tienda ajena = tienda();
        long delOtro = articulo(ajena, "Secreto de la otra", "UNIDAD", "100.00", "10");
        long inexistente = 987_654_321L;
        String token = login(propia.emailVendedor());

        JsonNode porAjeno = json(vender(token, clave(), "100.00", linea(delOtro, "1", "100.00"))
                .andExpect(status().isNotFound()));
        JsonNode porInexistente = json(vender(token, clave(), "100.00", linea(inexistente, "1", "100.00"))
                .andExpect(status().isNotFound()));

        assertThat(porAjeno.get("motivo").asText()).isEqualTo("ARTICULO_INEXISTENTE");
        assertThat(porAjeno.get("message").asText()).isEqualTo(porInexistente.get("message").asText());
        assertThat(porAjeno.get("articulos").get(0).get("articuloId").asLong()).isEqualTo(delOtro);
        assertThat(porAjeno.get("articulos").get(0).has("descripcion")).isFalse();
        assertThat(porAjeno.toString()).doesNotContain("Secreto");
        assertThat(stock(delOtro)).isEqualByComparingTo("10");
    }

    @Test
    void elSuperAdminNoPuedeVender() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        postJson("/api/ventas", login("admin@demo.local"), cuerpo(clave(), "EFECTIVO", "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isForbidden());

        assertThat(comprobantes(t)).isZero();
    }

    @Test
    void cacheDeStockIgualASumaDeMovimientosTrasVariasVentas() throws Exception {
        Tienda t = tienda();
        long unidad = articulo(t, "Tornillo", "UNIDAD", "100.00", "50");
        long metro = articulo(t, "Cable", "METRO", "333.33", "20");
        String token = login(t.emailVendedor());
        String repetida = clave();

        vender(token, clave(), "300.00", linea(unidad, "3", "100.00")).andExpect(status().isCreated());
        vender(token, clave(), "833.33", linea(metro, "2.5", "333.33")).andExpect(status().isCreated());
        vender(token, repetida, "100.00", linea(unidad, "1", "100.00")).andExpect(status().isCreated());
        vender(token, repetida, "100.00", linea(unidad, "1", "100.00")).andExpect(status().isOk());
        vender(token, clave(), "100.00", linea(unidad, "1", "999.00")).andExpect(status().isConflict());
        vender(token, clave(), "100.00", linea(unidad, "1.5", "100.00")).andExpect(status().isBadRequest());
        vender(token, clave(), "1000.00", linea(unidad, "200", "5.00")).andExpect(status().isConflict());

        assertThat(stock(unidad)).isEqualByComparingTo(
                new java.math.BigDecimal("50").add(sumaMovimientos(unidad)));
        assertThat(stock(metro)).isEqualByComparingTo(
                new java.math.BigDecimal("20").add(sumaMovimientos(metro)));
        assertThat(sumaMovimientos(unidad)).isEqualByComparingTo("-4");
        assertThat(sumaMovimientos(metro)).isEqualByComparingTo("-2.5");
    }

    @Test
    void elTotalGuardadoEsLaSumaDeLosItems() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo 1/4", "UNIDAD", "1210.00", "10");
        long b = articulo(t, "Cable", "METRO", "333.33", "100");

        vender(login(t.emailVendedor()), clave(), "4463.33", linea(a, "3", "1210.00"), linea(b, "2.5", "333.33"))
                .andExpect(status().isCreated());

        Map<String, Object> fila = jdbc.queryForMap(
                "select c.total, c.neto, c.iva, sum(i.total) st, sum(i.neto) sn, sum(i.iva) si "
                        + "from comprobante c join item_comprobante i on i.comprobante_id = c.id "
                        + "where c.ferreteria_id = ? group by c.id, c.total, c.neto, c.iva", t.id());
        assertThat((java.math.BigDecimal) fila.get("total")).isEqualByComparingTo((java.math.BigDecimal) fila.get("st"));
        assertThat((java.math.BigDecimal) fila.get("neto")).isEqualByComparingTo((java.math.BigDecimal) fila.get("sn"));
        assertThat((java.math.BigDecimal) fila.get("iva")).isEqualByComparingTo((java.math.BigDecimal) fila.get("si"));
        assertThat((java.math.BigDecimal) fila.get("total")).isEqualByComparingTo("4463.33");
    }

    private java.math.BigDecimal sumaMovimientos(long articuloId) {
        return jdbc.queryForObject("select coalesce(sum(cantidad), 0) from movimiento_stock where articulo_id = ?",
                java.math.BigDecimal.class, articuloId);
    }

    // ---------------------------------------------------------------- T-17: aislamiento por tenant

    @Test
    void elDuenoDeBConUnArticuloDeARecibe404IdenticoAlInexistenteYElStockDeANoCambia() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = articulo(a, "Solo de A", "UNIDAD", "100.00", "10");
        long propioB = articulo(b, "Propio de B", "UNIDAD", "50.00", "10");
        String tokenB = login(b.emailDueno());

        var ajeno = vender(tokenB, clave(), "150.00", linea(propioB, "1", "50.00"), linea(deA, "1", "100.00"))
                .andExpect(status().isNotFound());
        var inexistente = vender(tokenB, clave(), "150.00", linea(propioB, "1", "50.00"), linea(deA + 900_000_000L, "1", "100.00"))
                .andExpect(status().isNotFound());

        JsonNode cuerpoAjeno = json(ajeno);
        JsonNode cuerpoInexistente = json(inexistente);
        assertThat(cuerpoAjeno.get("message")).isEqualTo(cuerpoInexistente.get("message"));
        assertThat(cuerpoAjeno.get("motivo")).isEqualTo(cuerpoInexistente.get("motivo"));
        assertThat(cuerpoAjeno.get("articulos")).hasSize(1);
        assertThat(cuerpoAjeno.get("articulos").get(0).get("articuloId").asLong()).isEqualTo(deA);
        assertThat(cuerpoAjeno.toString()).doesNotContain("Solo de A");
        assertThat(stock(deA)).isEqualByComparingTo("10");
        assertThat(stock(propioB)).isEqualByComparingTo("10");
        assertThat(comprobantes(a)).isZero();
        assertThat(comprobantes(b)).isZero();
        assertThat(movimientos(a)).isZero();
    }

    @Test
    void laMismaClaveEnDosFerreteriasDaDosComprobantesIndependientes() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = articulo(a, "Tornillo", "UNIDAD", "100.00", "10");
        long deB = articulo(b, "Tuerca", "UNIDAD", "70.00", "10");
        String clave = clave();

        JsonNode ventaA = json(vender(login(a.emailVendedor()), clave, "100.00", linea(deA, "1", "100.00"))
                .andExpect(status().isCreated()));
        // Contenido distinto en B: si B viera la clave de A daria 422 u 200; tiene que ser una venta nueva.
        JsonNode ventaB = json(vender(login(b.emailVendedor()), clave, "210.00", linea(deB, "3", "70.00"))
                .andExpect(status().isCreated()));

        assertThat(ventaA.get("ventaId")).isNotEqualTo(ventaB.get("ventaId"));
        assertThat(comprobantes(a)).isEqualTo(1);
        assertThat(comprobantes(b)).isEqualTo(1);
        assertThat(stock(deA)).isEqualByComparingTo("9");
        assertThat(stock(deB)).isEqualByComparingTo("7");
    }

    @Test
    void laNumeracionDeANoAvanzaPorLasVentasDeB() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = articulo(a, "Tornillo", "UNIDAD", "100.00", "50");
        long deB = articulo(b, "Tuerca", "UNIDAD", "70.00", "50");
        String tokenA = login(a.emailVendedor());
        String tokenB = login(b.emailVendedor());

        JsonNode a1 = json(vender(tokenA, clave(), "100.00", linea(deA, "1", "100.00")).andExpect(status().isCreated()));
        vender(tokenB, clave(), "70.00", linea(deB, "1", "70.00")).andExpect(status().isCreated());
        vender(tokenB, clave(), "70.00", linea(deB, "1", "70.00")).andExpect(status().isCreated());
        vender(tokenB, clave(), "70.00", linea(deB, "1", "70.00")).andExpect(status().isCreated());
        JsonNode a2 = json(vender(tokenA, clave(), "100.00", linea(deA, "1", "100.00")).andExpect(status().isCreated()));

        assertThat(a1.get("numero").asLong()).isEqualTo(1);
        assertThat(a2.get("numero").asLong()).isEqualTo(2);
    }

    @Test
    void ningunaRespuestaFiltraDatosDeOtraFerreteria() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = articulo(a, "Descripcion-secreta-de-A", "UNIDAD", "123.45", "10");
        long bajaDeA = articulo(a, "Baja-secreta-de-A", "UNIDAD", "99.00", "10", "IVA_21", false);
        long deB = articulo(b, "Tuerca", "UNIDAD", "70.00", "10");
        String tokenB = login(b.emailVendedor());

        var respuestas = new java.util.ArrayList<String>();
        // 201
        respuestas.add(vender(tokenB, clave(), "70.00", linea(deB, "1", "70.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 400 (cantidad decimal en articulo por unidad)
        respuestas.add(vender(tokenB, clave(), "70.00", linea(deB, "1.5", "70.00")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 404 con ids ajenos (activo y de baja)
        respuestas.add(vender(tokenB, clave(), "70.00", linea(deA, "1", "123.45"), linea(bajaDeA, "1", "99.00"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 409 de precios propio
        respuestas.add(vender(tokenB, clave(), "10.00", linea(deB, "1", "10.00")).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 422 de baja propia
        long bajaDeB = articulo(b, "Baja de B", "UNIDAD", "5.00", "1", "IVA_21", false);
        respuestas.add(vender(tokenB, clave(), "5.00", linea(bajaDeB, "1", "5.00")).andExpect(status().is(422))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        for (String cuerpo : respuestas) {
            assertThat(cuerpo).doesNotContain("secreta").doesNotContain("123.45");
        }
        assertThat(stock(deA)).isEqualByComparingTo("10");
        assertThat(stock(bajaDeA)).isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------- T-18: idempotencia y numeracion (secuencial)

    @Test
    void repetirLaVentaDa200ConLaMismaVentaYNoDescuentaDeNuevo() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();

        JsonNode original = json(vender(token, clave, "300.00", linea(a, "3", "100.00")).andExpect(status().isCreated()));
        JsonNode repetida = json(vender(token, clave, "300.00", linea(a, "3", "100.00")).andExpect(status().isOk()));

        assertThat(repetida.get("ventaId")).isEqualTo(original.get("ventaId"));
        assertThat(repetida.get("numero")).isEqualTo(original.get("numero"));
        assertThat(repetida.get("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(repetida.get("sinStock")).isEmpty();
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(movimientos(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("7");
    }

    @Test
    void laMismaClaveConOtroContenidoDa422SinTocarElStock() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        vender(token, clave, "300.00", linea(a, "3", "100.00")).andExpect(status().isCreated());

        JsonNode rechazo = json(vender(token, clave, "500.00", linea(a, "5", "100.00")).andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(rechazo.get("message").asText()).isEqualTo("Esta venta ya se registró con otros datos");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("7");
    }

    @Test
    void laMismaClaveConOtroMedioDePagoTambienEsOtroContenido() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        vender(token, clave, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());

        postJson("/api/ventas", token, cuerpo(clave, "DEBITO", "100.00", linea(a, "1", "100.00")))
                .andExpect(status().is(422));

        assertThat(comprobantes(t)).isEqualTo(1);
    }

    @Test
    void unPostRechazadoNoGuardaLaClave() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();

        vender(token, clave, "999.00", linea(a, "1", "999.00")).andExpect(status().isConflict());
        vender(token, clave, "150.00", linea(a, "1.5", "100.00")).andExpect(status().isBadRequest());

        long conClave = jdbc.queryForObject("select count(*) from comprobante where clave_idempotencia = ?",
                Long.class, clave);
        assertThat(conClave).isZero();
        vender(token, clave, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());
    }

    @Test
    void laRepeticionNoConsumeNumero() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String primera = clave();

        JsonNode v1 = json(vender(token, primera, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated()));
        vender(token, primera, "100.00", linea(a, "1", "100.00")).andExpect(status().isOk());
        vender(token, primera, "100.00", linea(a, "1", "100.00")).andExpect(status().isOk());
        JsonNode v2 = json(vender(token, clave(), "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated()));

        assertThat(v1.get("numero").asLong()).isEqualTo(1);
        assertThat(v2.get("numero").asLong()).isEqualTo(2);
    }

    @Test
    void unRechazoEntreDosVentasBuenasDejaNumerosConsecutivos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        JsonNode v1 = json(vender(token, clave(), "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated()));
        vender(token, clave(), "100.00", linea(a, "1", "50.00")).andExpect(status().isConflict());
        JsonNode v2 = json(vender(token, clave(), "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated()));

        assertThat(v1.get("numero").asLong()).isEqualTo(1);
        assertThat(v2.get("numero").asLong()).isEqualTo(2);
    }
}
