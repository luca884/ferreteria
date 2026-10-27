package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CCT3B1-603 y CCT3B1-606 (CC-072..CC-078, CC-080, CC-101): {@code GET /api/clientes/{id}/cuenta-corriente/extracto} de punta a
 * punta (HTTP real, MySQL real), con el libro armado por los endpoints REALES (venta a cuenta corriente, cobranza y anulación).
 * Solo el Dueño. El orden de evaluación es 403 → 400 → 404. El plan de la consulta (ix_mcc_cliente) lo prueba
 * {@code MovimientoCuentaClienteRepositoryExtractoTest} sobre la @Query real. CUIT 30-8506NNNN-1.
 */
class ExtractoIntegrationTest extends CuentaCorrienteTestBase {

    @Override
    protected String prefijoDeCuit() {
        return "30-8506";
    }

    private record Tienda(Ferreteria f, Logins login, String dueno, long articulo) {}

    private Tienda tienda() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        cajaAbiertaHoy(f.id());
        String codigo = "X-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, alicuota_iva, unidad, "
                + "stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 1.00, 'IVA_21', 'UNIDAD', 10000000, 0, 1)",
                f.id(), codigo);
        long articulo = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
        return new Tienda(f, login, login(login.dueno()), articulo);
    }

    private String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(texto(resultado));
    }

    /** Una venta íntegra a cuenta corriente de {@code unidades} × $ 1,00, por HTTP; devuelve el id del comprobante. */
    private long venta(Tienda t, long cliente, int unidades, boolean autorizaExceso) throws Exception {
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Long.class, t.f().id());
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", t.articulo());
        renglon.put("cantidad", new BigDecimal(unidades));
        renglon.put("precioUnitario", new BigDecimal("1.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", UUID.randomUUID().toString());
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal(unidades));
        cuerpo.put("formaPagoId", forma);
        cuerpo.put("clienteId", cliente);
        cuerpo.put("autorizaExceso", autorizaExceso);
        return json(postJson("/api/ventas", t.dueno(), objectMapper.writeValueAsString(cuerpo)).andExpect(status().isCreated()))
                .get("ventaId").asLong();
    }

    /** Un cobro en efectivo imputado a un comprobante, por HTTP; devuelve el id del recibo. */
    private long cobro(Tienda t, long cliente, String total, long comprobante) throws Exception {
        String cuerpo = "{\"clave\":\"" + UUID.randomUUID() + "\",\"clienteId\":" + cliente + ",\"medio\":\"EFECTIVO\",\"total\":" + total
                + ",\"imputaciones\":[{\"comprobanteId\":" + comprobante + ",\"importe\":" + total + "}]}";
        return json(postJson("/api/cobranzas", t.dueno(), cuerpo).andExpect(status().isCreated())).get("id").asLong();
    }

    private void anular(Tienda t, long recibo) throws Exception {
        postJson("/api/recibos/" + recibo + "/anulacion", t.dueno(), "{\"motivo\":\"Cobro mal cargado\"}").andExpect(status().isOk());
    }

    private static String url(long cliente) {
        return "/api/clientes/" + cliente + "/cuenta-corriente/extracto";
    }

    /** El cliente del ejemplo: VENTA 1.000, COBRO 400 (anulado), ANULACION 400, VENTA 600. Saldos: 1.000, 600, 1.000, 1.600. */
    private record Libro(long cliente, long venta1, long recibo, long venta2) {}

    private Libro libro(Tienda t) throws Exception {
        long cliente = cliente(t.f(), "5000");
        long venta1 = venta(t, cliente, 1000, false);
        long recibo = cobro(t, cliente, "400", venta1);
        anular(t, recibo);
        long venta2 = venta(t, cliente, 600, false);
        assertThat(saldo(cliente)).isEqualByComparingTo("1600");
        return new Libro(cliente, venta1, recibo, venta2);
    }

    // ------------------------------------------------------------------ 200: el contrato

    @Test
    @DisplayName("200: encabezado (saldoInicial 0, totales, saldoFinal == saldoActual == saldo de la línea más nueva), líneas de la más nueva a la más vieja con saldo, usuario, reciboId, comprobanteId, motivo y anulado")
    void contrato() throws Exception {
        Tienda t = tienda();
        Libro l = libro(t);

        JsonNode extracto = json(getResult(url(l.cliente()), t.dueno()).andExpect(status().isOk()));

        assertThat(extracto.get("clienteId").asLong()).isEqualTo(l.cliente());
        assertThat(extracto.get("clienteNombre").asText()).startsWith("Cliente ");
        assertThat(extracto.get("saldoActual").decimalValue()).isEqualByComparingTo("1600");
        assertThat(extracto.get("desde").isNull()).isTrue();
        assertThat(extracto.get("hasta").isNull()).isTrue();
        assertThat(extracto.get("tipo").isNull()).isTrue();
        assertThat(extracto.get("saldoInicial").decimalValue()).isEqualByComparingTo("0");
        assertThat(extracto.get("totalDebe").decimalValue()).isEqualByComparingTo("2000");
        assertThat(extracto.get("totalHaber").decimalValue()).isEqualByComparingTo("400");
        assertThat(extracto.get("saldoFinal").decimalValue()).isEqualByComparingTo("1600");
        assertThat(extracto.get("truncado").asBoolean()).isFalse();
        JsonNode pagina = extracto.get("movimientos");
        assertThat(pagina.get("page").asInt()).isZero();
        assertThat(pagina.get("size").asInt()).isEqualTo(20);
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(4);
        assertThat(pagina.get("totalPages").asInt()).isEqualTo(1);

        JsonNode items = pagina.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).get("tipo").asText()).isEqualTo("VENTA");
        assertThat(items.get(0).get("comprobanteId").asLong()).isEqualTo(l.venta2());
        assertThat(items.get(0).get("debe").decimalValue()).isEqualByComparingTo("600");
        assertThat(items.get(0).get("saldo").decimalValue()).as("saldo de la línea más nueva = saldo del cliente")
                .isEqualByComparingTo("1600").isEqualByComparingTo(extracto.get("saldoActual").decimalValue());
        assertThat(items.get(0).get("referencia").asText()).startsWith("Venta N.º ");
        assertThat(items.get(0).get("usuario").asText()).isEqualTo("Dueno CC");
        assertThat(items.get(0).get("excedioLimite").asBoolean()).isFalse();
        assertThat(items.get(0).get("autorizadoPor").isNull()).isTrue();
        assertThat(items.get(0).get("anulado").asBoolean()).isFalse();
        assertThat(items.get(0).get("reciboId").isNull()).isTrue();

        JsonNode anulacion = items.get(1);
        assertThat(anulacion.get("tipo").asText()).isEqualTo("ANULACION");
        assertThat(anulacion.get("reciboId").asLong()).isEqualTo(l.recibo());
        assertThat(anulacion.get("motivo").asText()).isEqualTo("Cobro mal cargado");
        assertThat(anulacion.get("usuario").asText()).isEqualTo("Dueno CC");
        assertThat(anulacion.get("debe").decimalValue()).isEqualByComparingTo("400");
        assertThat(anulacion.get("haber").decimalValue()).isEqualByComparingTo("0");
        assertThat(anulacion.get("saldo").decimalValue()).isEqualByComparingTo("1000");
        assertThat(anulacion.get("anulado").asBoolean()).as("la ANULACION no está «anulada»").isFalse();
        assertThat(anulacion.get("referencia").asText()).startsWith("Anulación Recibo N.º ");

        JsonNode cobro = items.get(2);
        assertThat(cobro.get("tipo").asText()).isEqualTo("COBRO");
        assertThat(cobro.get("reciboId").asLong()).isEqualTo(l.recibo());
        assertThat(cobro.get("anulado").asBoolean()).as("el COBRO cuyo recibo se anuló").isTrue();
        assertThat(cobro.get("haber").decimalValue()).isEqualByComparingTo("400");
        assertThat(cobro.get("saldo").decimalValue()).isEqualByComparingTo("600");
        assertThat(cobro.get("motivo").isNull()).isTrue();

        JsonNode primera = items.get(3);
        assertThat(primera.get("tipo").asText()).isEqualTo("VENTA");
        assertThat(primera.get("comprobanteId").asLong()).isEqualTo(l.venta1());
        assertThat(primera.get("saldo").decimalValue()).isEqualByComparingTo("1000");
        assertThat(LocalDateTime.parse(primera.get("fecha").asText())).isBetween(LocalDateTime.now(HoraArgentina.ZONA).minusHours(1),
                LocalDateTime.now(HoraArgentina.ZONA).plusHours(1));
        assertInvariante(l.cliente());
    }

    @Test
    @DisplayName("una venta con exceso autorizado trae excedioLimite = true y quién la autorizó")
    void excesoAutorizado() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "100");
        venta(t, cliente, 500, true);

        JsonNode linea = json(getResult(url(cliente), t.dueno()).andExpect(status().isOk())).get("movimientos").get("items").get(0);

        assertThat(linea.get("excedioLimite").asBoolean()).isTrue();
        assertThat(linea.get("autorizadoPor").asText()).isEqualTo("Dueno CC");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("tipo=COBRO: una sola línea con el saldo REAL del libro (600, no −400); el encabezado no cambia el saldoFinal por el tipo")
    void filtroPorTipo() throws Exception {
        Tienda t = tienda();
        Libro l = libro(t);

        JsonNode extracto = json(getResult(url(l.cliente()) + "?tipo=COBRO", t.dueno()).andExpect(status().isOk()));

        assertThat(extracto.get("tipo").asText()).isEqualTo("COBRO");
        JsonNode items = extracto.get("movimientos").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("tipo").asText()).isEqualTo("COBRO");
        assertThat(items.get(0).get("saldo").decimalValue()).isEqualByComparingTo("600");
        assertThat(extracto.get("totalDebe").decimalValue()).isEqualByComparingTo("0");
        assertThat(extracto.get("totalHaber").decimalValue()).isEqualByComparingTo("400");
        assertThat(extracto.get("saldoFinal").decimalValue()).isEqualByComparingTo("1600");
        assertThat(extracto.get("movimientos").get("totalElements").asLong()).isEqualTo(1);

        JsonNode ventas = json(getResult(url(l.cliente()) + "?tipo=VENTA", t.dueno()).andExpect(status().isOk()));
        assertThat(ventas.get("movimientos").get("items")).extracting(i -> i.get("tipo").asText()).containsExactly("VENTA", "VENTA");
    }

    @Test
    @DisplayName("desde/hasta: el día de hoy trae todo; desde mañana trae 0 líneas con saldoInicial = saldo; hasta ayer trae 0 líneas con saldoFinal 0")
    void filtroPorFecha() throws Exception {
        Tienda t = tienda();
        Libro l = libro(t);
        LocalDate hoy = HoraArgentina.hoy();

        JsonNode hoyTodo = json(getResult(url(l.cliente()) + "?desde=" + hoy + "&hasta=" + hoy, t.dueno()).andExpect(status().isOk()));
        assertThat(hoyTodo.get("desde").asText()).isEqualTo(hoy.toString());
        assertThat(hoyTodo.get("hasta").asText()).isEqualTo(hoy.toString());
        assertThat(hoyTodo.get("movimientos").get("items")).hasSize(4);
        assertThat(hoyTodo.get("saldoInicial").decimalValue()).isEqualByComparingTo("0");
        assertThat(hoyTodo.get("saldoFinal").decimalValue()).isEqualByComparingTo("1600");

        JsonNode futuro = json(getResult(url(l.cliente()) + "?desde=" + hoy.plusDays(1), t.dueno()).andExpect(status().isOk()));
        assertThat(futuro.get("movimientos").get("items")).isEmpty();
        assertThat(futuro.get("saldoInicial").decimalValue()).isEqualByComparingTo("1600");
        assertThat(futuro.get("saldoFinal").decimalValue()).isEqualByComparingTo("1600");

        JsonNode pasado = json(getResult(url(l.cliente()) + "?hasta=" + hoy.minusDays(1), t.dueno()).andExpect(status().isOk()));
        assertThat(pasado.get("movimientos").get("items")).isEmpty();
        assertThat(pasado.get("saldoInicial").decimalValue()).isEqualByComparingTo("0");
        assertThat(pasado.get("saldoFinal").decimalValue()).isEqualByComparingTo("0");
        assertThat(pasado.get("saldoActual").decimalValue()).isEqualByComparingTo("1600");
    }

    @Test
    @DisplayName("paginación: size=2 parte las 4 líneas en 2 páginas; size=500 se acota a 100; size=0 se acota a 1; sin size son 20")
    void paginacion() throws Exception {
        Tienda t = tienda();
        Libro l = libro(t);

        JsonNode primera = json(getResult(url(l.cliente()) + "?size=2&page=0", t.dueno()).andExpect(status().isOk())).get("movimientos");
        JsonNode segunda = json(getResult(url(l.cliente()) + "?size=2&page=1", t.dueno()).andExpect(status().isOk())).get("movimientos");

        assertThat(primera.get("items")).extracting(i -> i.get("tipo").asText()).containsExactly("VENTA", "ANULACION");
        assertThat(segunda.get("items")).extracting(i -> i.get("tipo").asText()).containsExactly("COBRO", "VENTA");
        assertThat(segunda.get("items").get(1).get("saldo").decimalValue()).isEqualByComparingTo("1000");
        assertThat(primera.get("totalElements").asLong()).isEqualTo(4);
        assertThat(primera.get("totalPages").asInt()).isEqualTo(2);
        assertThat(segunda.get("page").asInt()).isEqualTo(1);

        assertThat(json(getResult(url(l.cliente()) + "?size=500", t.dueno()).andExpect(status().isOk())).get("movimientos").get("size").asInt())
                .isEqualTo(100);
        assertThat(json(getResult(url(l.cliente()) + "?size=0", t.dueno()).andExpect(status().isOk())).get("movimientos").get("items"))
                .hasSize(1);
    }

    @Test
    @DisplayName("size=500 con 130 líneas trae a lo sumo 100 (el tope), no las 130")
    void topeDeCien() throws Exception {
        Tienda t = tienda();
        long cliente = clienteConLibroGrande(t.f(), 130);

        JsonNode pagina = json(getResult(url(cliente) + "?size=500", t.dueno()).andExpect(status().isOk())).get("movimientos");

        assertThat(pagina.get("items")).hasSize(100);
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(130);
        assertThat(pagina.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("impresion=true con 2.001 líneas: truncado = true y las 2.000 más nuevas; con 2.000 exactas, no")
    void impresionTruncada() throws Exception {
        Tienda t = tienda();
        long grande = clienteConLibroGrande(t.f(), 2001);
        long justo = clienteConLibroGrande(t.f(), 2000);

        JsonNode truncada = json(getResult(url(grande) + "?impresion=true", t.dueno()).andExpect(status().isOk()));
        JsonNode exacta = json(getResult(url(justo) + "?impresion=true", t.dueno()).andExpect(status().isOk()));

        assertThat(truncada.get("truncado").asBoolean()).isTrue();
        assertThat(truncada.get("movimientos").get("items")).hasSize(2000);
        assertThat(truncada.get("movimientos").get("totalElements").asLong()).isEqualTo(2001);
        assertThat(truncada.get("movimientos").get("items").get(0).get("saldo").decimalValue()).isEqualByComparingTo(saldo(grande));
        assertThat(exacta.get("truncado").asBoolean()).isFalse();
        assertThat(exacta.get("movimientos").get("items")).hasSize(2000);
        assertThat(exacta.get("saldoFinal").decimalValue()).isEqualByComparingTo(saldo(justo));
    }

    /** Un cliente con {@code n} movimientos AJUSTE/NC sembrados por SQL (no piden comprobante ni recibo) y su saldo al día. */
    private long clienteConLibroGrande(Ferreteria f, int n) {
        long cliente = cliente(f, null);
        List<Object[]> filas = new ArrayList<>();
        BigDecimal saldo = BigDecimal.ZERO;
        BigDecimal importe = new BigDecimal("1.50");
        // V28: cada AJUSTE del libro (los que van al debe) lleva su ajuste
        long conDebe = java.util.stream.IntStream.range(0, n).filter(i -> i % 3 != 2).count();
        java.util.Iterator<Long> ajustes = SemillaAjuste.ajustes(jdbc, f.id(), cliente, f.usuarioId(), "RECARGO",
                java.util.Collections.nCopies((int) conDebe, importe)).iterator();
        for (int i = 0; i < n; i++) {
            boolean debe = i % 3 != 2;
            saldo = debe ? saldo.add(importe) : saldo.subtract(importe);
            filas.add(new Object[] {f.id(), cliente, debe ? "AJUSTE" : "NC", debe ? importe : BigDecimal.ZERO,
                    debe ? BigDecimal.ZERO : importe, "Mov " + i, debe ? "Ajuste de prueba" : null, f.usuarioId(),
                    debe ? ajustes.next() : null});
        }
        jdbc.batchUpdate("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, referencia, motivo, "
                + "fecha, usuario_id, ajuste_id) values (?, ?, ?, ?, ?, ?, ?, now(6), ?, ?)", filas);
        jdbc.update("update cliente set saldo = ? where id = ?", saldo, cliente);
        return cliente;
    }

    @Test
    @DisplayName("un cliente desactivado se consulta (S-5): 200 con su extracto")
    void desactivadoSeConsulta() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        JsonNode extracto = json(getResult(url(cliente), t.dueno()).andExpect(status().isOk()));

        assertThat(extracto.get("clienteId").asLong()).isEqualTo(cliente);
        assertThat(extracto.get("movimientos").get("items")).isEmpty();
        assertThat(extracto.get("movimientos").get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("el GET no escribe nada: ni libro, ni saldo, ni recibos")
    void soloLectura() throws Exception {
        Tienda t = tienda();
        Libro l = libro(t);
        long movimientos = movimientos(l.cliente());
        BigDecimal saldo = saldo(l.cliente());

        getResult(url(l.cliente()), t.dueno()).andExpect(status().isOk());
        getResult(url(l.cliente()) + "?tipo=COBRO&size=1&page=0", t.dueno()).andExpect(status().isOk());

        assertThat(movimientos(l.cliente())).isEqualTo(movimientos);
        assertThat(saldo(l.cliente())).isEqualByComparingTo(saldo);
        assertThat(recibos(l.cliente())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 400 (antes que el 404)

    @Test
    @DisplayName("400: tipo desconocido «Parámetro inválido: tipo», fecha mal formada, page/size no numéricos")
    void parametrosMalFormados() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);

        assertThat(json(getResult(url(cliente) + "?tipo=PRESTAMO", t.dueno()).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("Parámetro inválido: tipo");
        assertThat(json(getResult(url(cliente) + "?desde=basura", t.dueno()).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("Parámetro inválido: desde");
        assertThat(json(getResult(url(cliente) + "?hasta=2026-13-45", t.dueno()).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("Parámetro inválido: hasta");
        assertThat(json(getResult(url(cliente) + "?page=abc", t.dueno()).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("Parámetro inválido: page");
        assertThat(json(getResult(url(cliente) + "?size=x", t.dueno()).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("Parámetro inválido: size");
        assertThat(json(getResult(url(cliente) + "?impresion=quizas", t.dueno()).andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("Parámetro inválido: impresion");
    }

    @Test
    @DisplayName("CC-073 nunca 500: una página cuyo offset no entra en un int y una fecha fuera de [1000-01-01, 9999-12-30] dan 400 (con su clave), y los bordes válidos 200")
    void bordesDeParametros() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);

        for (String consulta : List.of("?page=2147483647&size=100", "?page=30000000&size=100", "?page=21474837&size=100",
                "?page=2147483647&size=2")) {
            JsonNode error = json(getResult(url(cliente) + consulta, t.dueno()).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("page").asText()).as(consulta).isEqualTo("La página pedida está fuera de rango.");
        }
        for (String consulta : List.of("?hasta=9999-12-31", "?hasta=0999-12-31")) {
            JsonNode error = json(getResult(url(cliente) + consulta, t.dueno()).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("hasta").asText()).as(consulta).isEqualTo("La fecha hasta tiene que estar entre el 01/01/1000 y el 30/12/9999.");
        }
        for (String consulta : List.of("?desde=0999-12-31", "?desde=9999-12-31", "?desde=0001-01-01")) {
            JsonNode error = json(getResult(url(cliente) + consulta, t.dueno()).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("desde").asText()).as(consulta).isEqualTo("La fecha desde tiene que estar entre el 01/01/1000 y el 30/12/9999.");
        }
        // los bordes que sí entran
        for (String consulta : List.of("?page=21474836&size=100", "?page=2147483647&size=1", "?page=2147483647&impresion=true", "?hasta=9999-12-30",
                "?desde=1000-01-01", "?desde=9999-12-30&hasta=9999-12-30", "?desde=1000-01-01&hasta=9999-12-30")) {
            JsonNode ok = json(getResult(url(cliente) + consulta, t.dueno()).andExpect(status().isOk()));
            assertThat(ok.get("movimientos").get("items")).as(consulta).isEmpty();
        }
        // el rango inválido va antes del 404, como desde > hasta: un cliente ajeno también da 400
        Tienda otra = tienda();
        getResult(url(cliente(otra.f(), null)) + "?hasta=9999-12-31", t.dueno()).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400 con clave desde: «La fecha desde no puede ser posterior a hasta.» — y va ANTES del 404 (un cliente ajeno con rango inválido da 400)")
    void desdePosteriorAHasta() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long cliente = cliente(t.f(), null);
        long ajeno = cliente(otra.f(), null);

        JsonNode error = json(getResult(url(cliente) + "?desde=2026-10-20&hasta=2026-10-19", t.dueno()).andExpect(status().isBadRequest()));
        assertThat(error.get("errors").get("desde").asText()).isEqualTo("La fecha desde no puede ser posterior a hasta.");
        getResult(url(ajeno) + "?desde=2026-10-20&hasta=2026-10-19", t.dueno()).andExpect(status().isBadRequest());
        getResult(url(cliente) + "?desde=2026-10-19&hasta=2026-10-19", t.dueno()).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ CCT3B1-606: aislamiento por ferretería (regla 7)

    @Test
    @DisplayName("tenant: el extracto del cliente de B (o inexistente) es 404 para el Dueño de A, sin datos; el extracto de A no trae ningún movimiento de B")
    void clienteAjeno() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        Libro libroA = libro(a);
        Libro libroB = libro(b);

        JsonNode error = json(getResult(url(libroB.cliente()), a.dueno()).andExpect(status().isNotFound()));
        ((ObjectNode) error).remove("timestamp"); // la hora puede traer «1600» por azar
        getResult(url(Long.MAX_VALUE), a.dueno()).andExpect(status().isNotFound());
        JsonNode propio = json(getResult(url(libroA.cliente()), a.dueno()).andExpect(status().isOk()));
        JsonNode ajenoVistoPorSuDueno = json(getResult(url(libroB.cliente()), b.dueno()).andExpect(status().isOk()));

        assertThat(error.toString()).contains("Cliente no encontrado").doesNotContain("1600").doesNotContain("saldo").doesNotContain("movimientos");
        List<Long> ids = new ArrayList<>();
        propio.get("movimientos").get("items").forEach(i -> ids.add(i.get("id").asLong()));
        assertThat(ids).hasSize(4);
        assertThat(ids).allSatisfy(id -> assertThat(jdbc.queryForObject("select ferreteria_id from movimiento_cuenta_cliente where id = ?",
                Long.class, id)).isEqualTo(a.f().id()));
        assertThat(ids).allSatisfy(id -> assertThat(jdbc.queryForObject("select cliente_id from movimiento_cuenta_cliente where id = ?",
                Long.class, id)).isEqualTo(libroA.cliente()));
        assertThat(ajenoVistoPorSuDueno.get("movimientos").get("items")).hasSize(4);
        assertInvariante(libroA.cliente());
        assertInvariante(libroB.cliente());
    }

    @Test
    @DisplayName("sin token es 401")
    void sinToken() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);

        mvc.perform(get(url(cliente))).andExpect(status().isUnauthorized());
    }
}
