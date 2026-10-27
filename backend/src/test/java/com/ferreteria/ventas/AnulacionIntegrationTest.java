package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.clientes.CuentaCorrienteTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CCT3B1-602 y CCT3B1-606 (CC-055..CC-060, CC-062, CC-065..CC-067, CC-069, CC-070, CC-066, CC-084):
 * {@code POST /api/recibos/{id}/anulacion} de punta a punta (HTTP real, MySQL real). Solo el Dueño; el cuerpo gana al 404, el 404
 * al estado de la caja, la caja al estado del recibo y el cliente desactivado al 409. Un rechazo no guarda, no consume número ni
 * cambia el saldo. El aislamiento por ferretería (606) está al final. Cada escenario deja I1, I2, I2b, I2e e I-inactivo.
 * CUIT 30-8506NNNN-1.
 */
class AnulacionIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";
    private static final DateTimeFormatter COMO_SE_MUESTRA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    protected String prefijoDeCuit() {
        return "30-8506";
    }

    /** Una ferretería con su Dueño, su Vendedor y la caja abierta hoy. */
    private record Tienda(Ferreteria f, Logins login, long caja) {

        String dueno(AnulacionIntegrationTest t) throws Exception {
            return t.login(login.dueno());
        }

        String vendedor(AnulacionIntegrationTest t) throws Exception {
            return t.login(login.vendedor());
        }
    }

    private Tienda tienda() {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        return new Tienda(f, login, cajaAbiertaHoy(f.id()));
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private static String imp(long comprobanteId, String importe) {
        return "{\"comprobanteId\":" + comprobanteId + ",\"importe\":" + importe + "}";
    }

    private static String cuerpoDeCobro(String clave, long clienteId, String medio, String total, String... imputaciones) {
        return "{\"clave\":\"" + clave + "\",\"clienteId\":" + clienteId + ",\"medio\":\"" + medio + "\",\"total\":" + total
                + ",\"imputaciones\":[" + String.join(",", imputaciones) + "]}";
    }

    private static String motivo(String texto) {
        return "{\"motivo\":\"" + texto + "\"}";
    }

    private String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(texto(resultado));
    }

    /** Un cobro por HTTP (201) y el recibo que devolvió. */
    private JsonNode cobrar(String token, long cliente, String medio, String total, String... imputaciones) throws Exception {
        return json(postJson("/api/cobranzas", token, cuerpoDeCobro(clave(), cliente, medio, total, imputaciones))
                .andExpect(status().isCreated()));
    }

    private ResultActions anular(String token, long recibo, String cuerpo) throws Exception {
        return postJson("/api/recibos/" + recibo + "/anulacion", token, cuerpo);
    }

    private ResultActions anular(String token, long recibo) throws Exception {
        return anular(token, recibo, motivo(MOTIVO));
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private String estado(long reciboId) {
        return jdbc.queryForObject("select estado from recibo where id = ?", String.class, reciboId);
    }

    private long anulaciones(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, clienteId);
    }

    private long vigentes(long reciboId) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 1", Long.class, reciboId);
    }

    /** Nada se escribió: recibo EMITIDO, sin ANULACION, imputaciones vigentes y el saldo esperado. */
    private void sinCambios(long cliente, long recibo, String saldoEsperado) {
        assertThat(estado(recibo)).isEqualTo("EMITIDO");
        assertThat(jdbc.queryForMap("select fecha_anulacion, usuario_anulacion_id, caja_anulacion_id, motivo_anulacion from recibo "
                + "where id = ?", recibo).values()).containsOnlyNulls();
        assertThat(anulaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo(saldoEsperado);
    }

    private void cerrarCaja(long cajaId) {
        jdbc.update("update caja set abierta = null where id = ?", cajaId);
    }

    // ------------------------------------------------------------------ 200

    @Test
    @DisplayName("200: ReciboResponse ANULADO con estado, anuladoPor, fechaAnulacion y el motivo recortado; mismas imputaciones y total; GET /api/recibos/{id} dice lo mismo; el N.º de recibo no se consume")
    void anulacionFeliz() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "5000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "1000");
        String dueno = t.dueno(this);
        JsonNode cobro = cobrar(dueno, cliente, "EFECTIVO", "1000", imp(venta.comprobanteId(), "700"));
        long recibo = cobro.get("id").asLong();
        assertThat(cobro.get("estado").asText()).isEqualTo("EMITIDO");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");

        JsonNode anulado = json(anular(dueno, recibo, motivo("  " + MOTIVO + "  ")).andExpect(status().isOk()));

        assertThat(anulado.get("id").asLong()).isEqualTo(recibo);
        assertThat(anulado.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(anulado.get("anuladoPor").asText()).isEqualTo("Dueno CC");
        assertThat(anulado.get("motivoAnulacion").asText()).isEqualTo(MOTIVO);
        assertThat(LocalDateTime.parse(anulado.get("fechaAnulacion").asText())).isBetween(LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(5));
        assertThat(anulado.get("numero")).isEqualTo(cobro.get("numero"));
        assertThat(anulado.get("total").decimalValue()).isEqualByComparingTo("1000");
        assertThat(anulado.get("aCuenta").decimalValue()).isEqualByComparingTo("300");
        assertThat(anulado.get("imputaciones")).as("las originales").isEqualTo(cobro.get("imputaciones"));
        assertThat(anulado.get("imputaciones")).hasSize(1);
        assertThat(json(getResult("/api/recibos/" + recibo, dueno).andExpect(status().isOk()))).as("GET idéntico").isEqualTo(anulado);

        assertThat(estado(recibo)).isEqualTo("ANULADO");
        assertThat(vigentes(recibo)).isZero();
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).as("el cobro vuelve a la deuda").isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("select caja_anulacion_id from recibo where id = ?", Long.class, recibo)).isEqualTo(t.caja());
        assertThat(ultimoNumero(t.f().id())).as("anular no consume número").isEqualTo(1L);
        assertThat(cobrar(dueno, cliente, "EFECTIVO", "50").get("numero").asLong()).as("el próximo cobro toma el N.º 2").isEqualTo(2L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("200: un adelanto (sin imputaciones) y un cobro por TRANSFERENCIA también se anulan; el adelanto deja el recibo ANULADO con la lista de imputaciones vacía")
    void anulaAdelantoYTransferencia() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        JsonNode adelanto = cobrar(dueno, cliente, "EFECTIVO", "400");
        JsonNode transferencia = cobrar(dueno, cliente, "TRANSFERENCIA", "250");
        assertThat(saldo(cliente)).isEqualByComparingTo("-650");

        JsonNode a = json(anular(dueno, adelanto.get("id").asLong()).andExpect(status().isOk()));
        JsonNode b = json(anular(dueno, transferencia.get("id").asLong()).andExpect(status().isOk()));

        assertThat(a.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(a.get("imputaciones")).isEmpty();
        assertThat(a.get("aCuenta").decimalValue()).isEqualByComparingTo("400");
        assertThat(b.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(b.get("medio").asText()).isEqualTo("TRANSFERENCIA");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(anulaciones(cliente)).isEqualTo(2);
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ CC-056: el motivo

    @Test
    @DisplayName("400 en motivo («abc», «    », ausente, null, sin cuerpo, 201 caracteres) sin tocar la base: recibo EMITIDO, sin ANULACION, saldo igual")
    void motivoInvalido() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "300").get("id").asLong();
        String corto = "Escribí el motivo de la anulación (al menos 5 letras).";

        for (String cuerpo : List.of(motivo("abc"), motivo("    "), motivo(""), "{}", "{\"motivo\":null}", motivo("1234"))) {
            JsonNode error = json(anular(dueno, recibo, cuerpo).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("motivo").asText()).as(cuerpo).isEqualTo(corto);
        }
        JsonNode largo = json(anular(dueno, recibo, motivo("a".repeat(201))).andExpect(status().isBadRequest()));
        assertThat(largo.get("errors").get("motivo").asText()).isEqualTo("El motivo admite hasta 200 caracteres.");
        // sin cuerpo (con y sin Content-Type): el motivo está ausente
        for (boolean conTipo : new boolean[] {true, false}) {
            var pedido = post("/api/recibos/" + recibo + "/anulacion").header("Authorization", "Bearer " + dueno);
            JsonNode error = objectMapper.readTree(mvc.perform(conTipo ? pedido.contentType(MediaType.APPLICATION_JSON) : pedido)
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(error.get("errors").get("motivo").asText()).as("sin cuerpo, Content-Type=" + conTipo).isEqualTo(corto);
        }

        sinCambios(cliente, recibo, "-300");
        assertThat(ultimoNumero(t.f().id())).isEqualTo(1L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un cuerpo que no es JSON es 400 (el cuerpo del pedido no es válido) y no toca nada")
    void cuerpoIlegible() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "300").get("id").asLong();

        JsonNode error = json(anular(dueno, recibo, "{motivo: esto no es json").andExpect(status().isBadRequest()));

        assertThat(error.get("message").asText()).isEqualTo("El cuerpo del pedido no es válido");
        sinCambios(cliente, recibo, "-300");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("5 y 200 caracteres valen, «12345» vale (S-1), 200 con espacios alrededor vale y se guarda recortado")
    void bordesDelMotivo() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        String[] validos = {"12345", "abcde", "a".repeat(200), "  " + "b".repeat(200) + "  "};
        for (String valido : validos) {
            long recibo = cobrar(dueno, cliente, "EFECTIVO", "10").get("id").asLong();

            JsonNode anulado = json(anular(dueno, recibo, motivo(valido)).andExpect(status().isOk()));

            assertThat(anulado.get("motivoAnulacion").asText()).isEqualTo(valido.trim());
            assertThat(jdbc.queryForObject("select motivo_anulacion from recibo where id = ?", String.class, recibo))
                    .isEqualTo(valido.trim());
            assertThat(jdbc.queryForObject("select motivo from movimiento_cuenta_cliente where recibo_id = ? and tipo = 'ANULACION'",
                    String.class, recibo)).isEqualTo(valido.trim());
        }
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ CC-057/CC-058/CC-059: 404, 422, 409 y su orden

    @Test
    @DisplayName("404 «Recibo no encontrado» con un recibo inexistente, sin cambios")
    void reciboInexistente() throws Exception {
        Tienda t = tienda();

        JsonNode error = json(anular(t.dueno(this), Long.MAX_VALUE).andExpect(status().isNotFound()));

        assertThat(error.get("message").asText()).isEqualTo("Recibo no encontrado");
    }

    @Test
    @DisplayName("el cuerpo gana al 404: un recibo inexistente con motivo inválido es 400, no 404")
    void cuerpoGanaAl404() throws Exception {
        Tienda t = tienda();

        JsonNode error = json(anular(t.dueno(this), Long.MAX_VALUE, motivo("abc")).andExpect(status().isBadRequest()));

        assertThat(error.get("errors").get("motivo").asText()).startsWith("Escribí el motivo");
    }

    @Test
    @DisplayName("422 CAJA_CERRADA «Abrí la caja para anular.» (también por transferencia): recibo EMITIDO, sin ANULACION, número intacto")
    void sinCaja() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long efectivo = cobrar(dueno, cliente, "EFECTIVO", "300").get("id").asLong();
        long transferencia = cobrar(dueno, cliente, "TRANSFERENCIA", "100").get("id").asLong();
        cerrarCaja(t.caja());

        for (long recibo : new long[] {efectivo, transferencia}) {
            JsonNode error = json(anular(dueno, recibo).andExpect(status().isUnprocessableEntity()));
            assertThat(error.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
            assertThat(error.get("message").asText()).isEqualTo("Abrí la caja para anular.");
            assertThat(error.get("status").asInt()).isEqualTo(422);
            assertThat(estado(recibo)).isEqualTo("EMITIDO");
        }

        assertThat(anulaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("-400");
        assertThat(ultimoNumero(t.f().id())).isEqualTo(2L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("422 CAJA_DE_OTRO_DIA «La caja del dd/MM sigue abierta. Cerrala antes de anular.» con la caja abierta de otro día")
    void cajaDeOtroDia() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "300").get("id").asLong();
        jdbc.update("update caja set fecha_apertura = ? where id = ?", LocalDateTime.now().minusDays(2), t.caja());

        JsonNode error = json(anular(dueno, recibo).andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("motivo").asText()).isEqualTo("CAJA_DE_OTRO_DIA");
        assertThat(error.get("message").asText()).startsWith("La caja del ").endsWith(" sigue abierta. Cerrala antes de anular.");
        sinCambios(cliente, recibo, "-300");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("422 «El cliente está desactivado.» (sin motivo en el cuerpo) y, reactivado, 200")
    void clienteDesactivado() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "100");
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "100", imp(venta.comprobanteId(), "100")).get("id").asLong();
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        JsonNode error = json(anular(dueno, recibo).andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("message").asText()).isEqualTo("El cliente está desactivado.");
        assertThat(error.has("motivo")).as("no es un rechazo de cobranza: sin motivo").isFalse();
        sinCambios(cliente, recibo, "0");
        assertThat(vigentes(recibo)).isEqualTo(1);
        assertInvariante(cliente);

        jdbc.update("update cliente set activo = 1 where id = ?", cliente);
        anular(dueno, recibo).andExpect(status().isOk());
        assertThat(saldo(cliente)).isEqualByComparingTo("100");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("409 RECIBO_YA_ANULADO con «Este recibo ya fue anulado por <nombre> el dd/MM/yyyy HH:mm.», anuladoPor y fechaAnulacion; una sola ANULACION y el saldo no cambia")
    void yaAnulado() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "5000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "800");
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "800", imp(venta.comprobanteId(), "800")).get("id").asLong();
        JsonNode primera = json(anular(dueno, recibo).andExpect(status().isOk()));

        JsonNode conflicto = json(anular(dueno, recibo, motivo("Otra vez, por las dudas")).andExpect(status().isConflict()));

        assertThat(conflicto.get("status").asInt()).isEqualTo(409);
        assertThat(conflicto.get("motivo").asText()).isEqualTo("RECIBO_YA_ANULADO");
        assertThat(conflicto.get("anuladoPor").asText()).isEqualTo("Dueno CC");
        assertThat(conflicto.get("fechaAnulacion").asText()).isEqualTo(primera.get("fechaAnulacion").asText());
        assertThat(conflicto.get("message").asText()).isEqualTo("Este recibo ya fue anulado por Dueno CC el "
                + LocalDateTime.parse(primera.get("fechaAnulacion").asText()).format(COMO_SE_MUESTRA) + ".");
        assertThat(conflicto.has("comprobantes")).isFalse();
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("800");
        assertThat(jdbc.queryForObject("select motivo_anulacion from recibo where id = ?", String.class, recibo)).isEqualTo(MOTIVO);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("orden de CC-057: la caja gana al estado (anulado + caja cerrada → 422), el cliente desactivado gana al 409 y, con todo en orden, 409")
    void ordenDeLosErrores() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "300").get("id").asLong();
        anular(dueno, recibo).andExpect(status().isOk());

        cerrarCaja(t.caja());
        JsonNode sinCaja = json(anular(dueno, recibo).andExpect(status().isUnprocessableEntity()));
        assertThat(sinCaja.get("motivo").asText()).as("la caja gana al estado").isEqualTo("CAJA_CERRADA");

        long cajaNueva = cajaAbiertaHoy(t.f().id());
        assertThat(cajaNueva).isNotEqualTo(t.caja());
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);
        JsonNode desactivado = json(anular(dueno, recibo).andExpect(status().isUnprocessableEntity()));
        assertThat(desactivado.get("message").asText()).as("el cliente desactivado gana al 409").isEqualTo("El cliente está desactivado.");

        jdbc.update("update cliente set activo = 1 where id = ?", cliente);
        anular(dueno, recibo).andExpect(status().isConflict());
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un recibo de una caja ya cerrada se anula con la caja de HOY abierta: 200 y la caja de la anulación es la de hoy")
    void reciboDeCajaCerrada() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "700").get("id").asLong();
        cerrarCaja(t.caja());
        long cajaDeHoy = cajaAbiertaHoy(t.f().id());

        anular(dueno, recibo).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select caja_anulacion_id from recibo where id = ?", Long.class, recibo)).isEqualTo(cajaDeHoy);
        assertThat(jdbc.queryForObject("select caja_id from recibo where id = ?", Long.class, recibo)).isEqualTo(t.caja());
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ CC-065, CC-067, CC-070

    private long articulo(Ferreteria f) {
        String codigo = "C-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 1.00, 'IVA_21', "
                + "'UNIDAD', 10000000, 0, 1)", f.id(), codigo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
    }

    /** Una venta íntegra a cuenta corriente de {@code unidades} × $ 1,00, por HTTP. */
    private ResultActions ventaCuentaCorriente(Tienda t, String token, long articulo, long cliente, int unidades) throws Exception {
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Long.class, t.f().id());
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articulo);
        renglon.put("cantidad", new BigDecimal(unidades));
        renglon.put("precioUnitario", new BigDecimal("1.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave());
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal(unidades));
        cuerpo.put("formaPagoId", forma);
        cuerpo.put("clienteId", cliente);
        return postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo));
    }

    @Test
    @DisplayName("CC-065 (Q5): anular puede dejar al cliente por encima del límite (saldo 6.000 con límite 5.000, sin excedio_limite) y la venta CC siguiente da 422 LIMITE_CUENTA_CORRIENTE")
    void anulacionExcedeElLimite() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "5000.00");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "6000.00");
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "2000", imp(venta.comprobanteId(), "2000")).get("id").asLong();
        assertThat(saldo(cliente)).isEqualByComparingTo("4000");

        anular(dueno, recibo).andExpect(status().isOk());

        assertThat(saldo(cliente)).isEqualByComparingTo("6000");
        assertThat(jdbc.queryForObject("select excedio_limite from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Boolean.class, cliente)).isFalse();
        long comprobantesAntes = jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class,
                t.f().id());
        JsonNode rechazo = json(ventaCuentaCorriente(t, dueno, articulo(t.f()), cliente, 100).andExpect(status().isUnprocessableEntity()));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("LIMITE_CUENTA_CORRIENTE");
        assertThat(jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, t.f().id()))
                .as("la venta rechazada no dejó comprobante").isEqualTo(comprobantesAntes);
        assertThat(saldo(cliente)).isEqualByComparingTo("6000");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-070: repetir la cobranza de un recibo ANULADO (misma clave y contenido) es 200 con el recibo ANULADO y SIN recibo nuevo; con otro total, 422 CLAVE_CON_OTRO_CONTENIDO; con otra clave es un recibo nuevo (201, N.º siguiente)")
    void cobranzaRepetidaDeUnReciboAnulado() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        String clave = clave();
        String cuerpo = cuerpoDeCobro(clave, cliente, "EFECTIVO", "300");
        JsonNode original = json(postJson("/api/cobranzas", dueno, cuerpo).andExpect(status().isCreated()));
        long recibo = original.get("id").asLong();
        anular(dueno, recibo).andExpect(status().isOk());

        JsonNode repetida = json(postJson("/api/cobranzas", dueno, cuerpo).andExpect(status().isOk()));

        assertThat(repetida.get("id").asLong()).isEqualTo(recibo);
        assertThat(repetida.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(repetida.get("anuladoPor").asText()).isEqualTo("Dueno CC");
        assertThat(repetida.get("motivoAnulacion").asText()).isEqualTo(MOTIVO);
        assertThat(recibos(cliente)).as("ningún recibo nuevo").isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        JsonNode otroTotal = json(postJson("/api/cobranzas", dueno, cuerpoDeCobro(clave, cliente, "EFECTIVO", "301"))
                .andExpect(status().isUnprocessableEntity()));
        assertThat(otroTotal.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");

        JsonNode nuevo = json(postJson("/api/cobranzas", dueno, cuerpoDeCobro(clave(), cliente, "EFECTIVO", "300"))
                .andExpect(status().isCreated()));
        assertThat(nuevo.get("numero").asLong()).as("N.º siguiente (CC-067)").isEqualTo(2L);
        assertThat(nuevo.get("estado").asText()).isEqualTo("EMITIDO");
        assertThat(recibos(cliente)).isEqualTo(2);
        assertThat(saldo(cliente)).isEqualByComparingTo("-300");
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ CC-055: el rol

    @Test
    @DisplayName("el Vendedor recibe 403 con motivo válido y con motivo vacío (no 400), y no se guarda nada")
    void vendedorEs403() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        long recibo = cobrar(t.dueno(this), cliente, "EFECTIVO", "300").get("id").asLong();
        String vendedor = t.vendedor(this);

        for (String cuerpo : List.of(motivo(MOTIVO), motivo(""), "{}", "esto no es json")) {
            String respuesta = texto(anular(vendedor, recibo, cuerpo).andExpect(status().isForbidden()));
            assertThat(respuesta).as(cuerpo).isEqualTo("{\"status\":403,\"message\":\"No tenés permiso para hacer esto\"}");
        }

        sinCambios(cliente, recibo, "-300");
        assertThat(ultimoNumero(t.f().id())).isEqualTo(1L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("el SUPER_ADMIN (sin ferretería) es 403 y sin token es 401")
    void superAdminYSinToken() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        long recibo = cobrar(t.dueno(this), cliente, "EFECTIVO", "300").get("id").asLong();

        anular(login("admin@demo.local"), recibo).andExpect(status().isForbidden());
        mvc.perform(post("/api/recibos/" + recibo + "/anulacion").contentType(MediaType.APPLICATION_JSON).content(motivo(MOTIVO)))
                .andExpect(status().isUnauthorized());

        sinCambios(cliente, recibo, "-300");
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ CCT3B1-606: aislamiento por ferretería (regla 7)

    @Test
    @DisplayName("tenant: el Dueño de A anula el recibo de B → 404 «Recibo no encontrado» y B queda intacta; GET del recibo de B → 404; cada Dueño anula el suyo con la caja de SU ferretería")
    void reciboAjeno() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long clienteA = cliente(a.f(), null);
        long clienteB = cliente(b.f(), null);
        long reciboA = cobrar(a.dueno(this), clienteA, "EFECTIVO", "100").get("id").asLong();
        long reciboB = cobrar(b.dueno(this), clienteB, "EFECTIVO", "75").get("id").asLong();

        JsonNode error = json(anular(a.dueno(this), reciboB).andExpect(status().isNotFound()));
        ((ObjectNode) error).remove("timestamp"); // la hora puede traer «75» por azar

        assertThat(error.get("message").asText()).isEqualTo("Recibo no encontrado");
        assertThat(error.toString()).doesNotContain("75").doesNotContain("Cliente");
        getResult("/api/recibos/" + reciboB, a.dueno(this)).andExpect(status().isNotFound());
        sinCambios(clienteB, reciboB, "-75");
        assertThat(saldo(clienteA)).isEqualByComparingTo("-100");
        assertThat(anulaciones(clienteA)).isZero();

        anular(b.dueno(this), reciboB).andExpect(status().isOk());
        anular(a.dueno(this), reciboA).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select caja_anulacion_id from recibo where id = ?", Long.class, reciboA)).isEqualTo(a.caja());
        assertThat(jdbc.queryForObject("select caja_anulacion_id from recibo where id = ?", Long.class, reciboB)).isEqualTo(b.caja());
        assertThat(jdbc.queryForObject("select ferreteria_id from movimiento_cuenta_cliente where recibo_id = ? and tipo = 'ANULACION'",
                Long.class, reciboA)).isEqualTo(a.f().id());
        assertInvariante(clienteA);
        assertInvariante(clienteB);
    }

    @Test
    @DisplayName("tenant: el resumen de caja de A no suma las anulaciones de B (cada caja ve solo las suyas) y la de B sí las suyas")
    void resumenDeCajaPorFerreteria() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long clienteA = cliente(a.f(), null);
        long clienteB = cliente(b.f(), null);
        long reciboA = cobrar(a.dueno(this), clienteA, "EFECTIVO", "100").get("id").asLong();
        long reciboB = cobrar(b.dueno(this), clienteB, "EFECTIVO", "700").get("id").asLong();
        anular(b.dueno(this), reciboB).andExpect(status().isOk());

        JsonNode cajaA = json(getResult("/api/cajas/" + a.caja(), a.dueno(this)).andExpect(status().isOk()));
        assertThat(cajaA.get("totalAnulaciones").decimalValue()).as("A todavía no anuló nada").isEqualByComparingTo("0");
        assertThat(cajaA.get("totalCobranzas").decimalValue()).isEqualByComparingTo("100");

        anular(a.dueno(this), reciboA).andExpect(status().isOk());
        cajaA = json(getResult("/api/cajas/" + a.caja(), a.dueno(this)).andExpect(status().isOk()));
        JsonNode cajaB = json(getResult("/api/cajas/" + b.caja(), b.dueno(this)).andExpect(status().isOk()));

        assertThat(cajaA.get("totalAnulaciones").decimalValue()).isEqualByComparingTo("100");
        assertThat(cajaA.get("anulaciones").get("EFECTIVO").decimalValue()).isEqualByComparingTo("100");
        assertThat(cajaB.get("totalAnulaciones").decimalValue()).isEqualByComparingTo("700");
        getResult("/api/cajas/" + b.caja(), a.dueno(this)).andExpect(status().isNotFound());
        assertInvariante(clienteA);
        assertInvariante(clienteB);
    }

    @Test
    @DisplayName("GET /api/recibos/{id} de un recibo EMITIDO trae estado EMITIDO y los tres campos de la anulación en null; sin token es 401")
    void reciboEmitido() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        String dueno = t.dueno(this);
        long recibo = cobrar(dueno, cliente, "EFECTIVO", "300").get("id").asLong();

        JsonNode emitido = json(getResult("/api/recibos/" + recibo, dueno).andExpect(status().isOk()));

        assertThat(emitido.get("estado").asText()).isEqualTo("EMITIDO");
        assertThat(emitido.get("fechaAnulacion").isNull()).isTrue();
        assertThat(emitido.get("anuladoPor").isNull()).isTrue();
        assertThat(emitido.get("motivoAnulacion").isNull()).isTrue();
        mvc.perform(get("/api/recibos/" + recibo)).andExpect(status().isUnauthorized());
        assertInvariante(cliente);
    }
}
