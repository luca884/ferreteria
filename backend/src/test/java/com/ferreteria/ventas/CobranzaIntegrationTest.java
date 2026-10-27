package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CCT3-403 y CCT3-404 (CC-028, CC-029, CC-030, CC-033..CC-036, CC-038, CC-040, CC-041): {@code POST /api/cobranzas} y
 * {@code GET /api/recibos/{id}} de punta a punta (HTTP real, MySQL real). Solo el Dueño: el Vendedor, el SUPER_ADMIN y quien no
 * manda token no cobran ni ven recibos, y lo de otra ferretería da 404. Cada escenario deja I1 + I2 al final. CUIT 30-8406NNNN-1.
 */
class CobranzaIntegrationTest extends CuentaCorrienteTestBase {

    @Override
    protected String prefijoDeCuit() {
        return "30-8406";
    }

    /** Una ferretería con su Dueño, su Vendedor y la caja abierta hoy. */
    private record Tienda(Ferreteria f, Logins login, long caja) {

        String dueno(CobranzaIntegrationTest t) throws Exception {
            return t.login(login.dueno());
        }

        String vendedor(CobranzaIntegrationTest t) throws Exception {
            return t.login(login.vendedor());
        }
    }

    private Tienda tienda() {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        return new Tienda(f, login, cajaAbiertaHoy(f.id()));
    }

    private Tienda tiendaSinCaja() {
        Ferreteria f = ferreteria();
        return new Tienda(f, logins(f), 0);
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    /** Un renglón de imputación como JSON. */
    private static String imp(long comprobanteId, String importe) {
        return "{\"comprobanteId\":" + comprobanteId + ",\"importe\":" + importe + "}";
    }

    private static String cuerpo(String clave, long clienteId, String medio, String total, String... imputaciones) {
        return "{\"clave\":\"" + clave + "\",\"clienteId\":" + clienteId + ",\"medio\":\"" + medio + "\",\"total\":" + total
                + ",\"imputaciones\":[" + String.join(",", imputaciones) + "]}";
    }

    private ResultActions cobrar(String token, String cuerpo) throws Exception {
        return postJson("/api/cobranzas", token, cuerpo);
    }

    private String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(texto(resultado));
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private long recibosDe(long ferreteriaId) {
        return jdbc.queryForObject("select count(*) from recibo where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    /** No quedó nada: ni recibo ni número consumido ni COBRO del cliente. */
    private void nadaGuardado(Tienda t, long cliente) {
        assertThat(recibosDe(t.f().id())).as("recibos").isZero();
        assertThat(ultimoNumero(t.f().id())).as("número consumido").isNull();
        assertThat(cobros(cliente)).as("COBRO").isZero();
        assertThat(imputaciones(cliente)).as("imputaciones").isZero();
    }

    // ------------------------------------------------------------------ 201 y numeración

    @Test
    @DisplayName("201: recibo N.º 1 con la caja del día, una imputación, un COBRO y el saldo en 0; el siguiente es el N.º 2")
    void cobroNuevo() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "1000");
        String dueno = t.dueno(this);

        ResultActions respuesta = cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "1000", imp(venta.comprobanteId(), "1000")));
        JsonNode recibo = json(respuesta.andExpect(status().isCreated()));

        assertThat(recibo.get("numero").asLong()).isEqualTo(1L);
        assertThat(recibo.get("clienteId").asLong()).isEqualTo(cliente);
        assertThat(recibo.get("clienteNombre").asText()).startsWith("Cliente ");
        assertThat(recibo.get("medio").asText()).isEqualTo("EFECTIVO");
        assertThat(recibo.get("total").decimalValue()).isEqualByComparingTo("1000");
        assertThat(recibo.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
        assertThat(recibo.get("cobradoPor").asText()).isEqualTo("Dueno CC");
        assertThat(recibo.has("fecha")).isTrue();
        assertThat(recibo.has("saldo")).isFalse();
        assertThat(recibo.get("imputaciones")).hasSize(1);
        assertThat(recibo.get("imputaciones").get(0).get("comprobanteId").asLong()).isEqualTo(venta.comprobanteId());
        assertThat(recibo.get("imputaciones").get(0).get("referencia").asText()).isEqualTo("Venta N.º " + venta.numero());
        assertThat(recibo.get("imputaciones").get(0).get("importe").decimalValue()).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("select caja_id from recibo where id = ?", Long.class, recibo.get("id").asLong()))
                .isEqualTo(t.caja());
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("1000");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);

        long otra = cliente(t.f(), null);
        JsonNode segundo = json(cobrar(dueno, cuerpo(clave(), otra, "TRANSFERENCIA", "50")).andExpect(status().isCreated()));
        assertThat(segundo.get("numero").asLong()).isEqualTo(2L);
        assertThat(segundo.get("medio").asText()).isEqualTo("TRANSFERENCIA");
        assertInvariante(otra);
    }

    @Test
    @DisplayName("un rechazo no consume número: 201 (N.º 1), 409, 201 (N.º 2)")
    void sinSaltosPorRechazos() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "100");
        String dueno = t.dueno(this);

        JsonNode primero = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "100", imp(venta.comprobanteId(), "100")))
                .andExpect(status().isCreated()));
        cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "10", imp(venta.comprobanteId(), "10"))).andExpect(status().isConflict());
        JsonNode segundo = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "10")).andExpect(status().isCreated()));

        assertThat(primero.get("numero").asLong()).isEqualTo(1L);
        assertThat(segundo.get("numero").asLong()).isEqualTo(2L);
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ idempotencia

    @Test
    @DisplayName("200 con la misma clave y contenido: el MISMO JSON (literal) que el 201, sin segundo recibo ni COBRO ni cambio de saldo")
    void repetida() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta a = ventaEnLibro(t.f(), cliente, "300");
        VentaSuelta b = ventaEnLibro(t.f(), cliente, "200");
        String dueno = t.dueno(this);
        String clave = clave();
        // Importes sin ceros: el 201 sale con la escala del pedido y el 200 con la de la base; el JSON no puede notarlo.
        String pedido = cuerpo(clave, cliente, "EFECTIVO", "300", imp(a.comprobanteId(), "100"), imp(b.comprobanteId(), "50.5"));

        String original = texto(cobrar(dueno, pedido).andExpect(status().isCreated()));
        BigDecimal saldoDespues = saldo(cliente);
        String repetida = texto(cobrar(dueno, pedido).andExpect(status().isOk()));

        assertThat(repetida).isEqualTo(original);
        assertThat(original).contains("\"total\":300.00", "\"aCuenta\":149.50", "\"importe\":100.00", "\"importe\":50.50");
        assertThat(recibosDe(t.f().id())).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputaciones(cliente)).isEqualTo(2);
        assertThat(saldo(cliente)).isEqualByComparingTo(saldoDespues);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("200 también si las imputaciones vienen en otro orden o con otra escala (300 contra 300.00)")
    void repetidaOtroOrdenYEscala() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta a = ventaEnLibro(t.f(), cliente, "300");
        VentaSuelta b = ventaEnLibro(t.f(), cliente, "200");
        String dueno = t.dueno(this);
        String clave = clave();

        String original = texto(cobrar(dueno, cuerpo(clave, cliente, "EFECTIVO", "300", imp(a.comprobanteId(), "100"),
                imp(b.comprobanteId(), "50"))).andExpect(status().isCreated()));
        String repetida = texto(cobrar(dueno, cuerpo(clave, cliente, "EFECTIVO", "300.00", imp(b.comprobanteId(), "50.00"),
                imp(a.comprobanteId(), "100.00"))).andExpect(status().isOk()));

        assertThat(repetida).isEqualTo(original);
        assertThat(recibosDe(t.f().id())).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
    }

    @Test
    @DisplayName("200 aunque la caja ya cerró o el cliente se haya desactivado: la clave ya registrada se resuelve primero")
    void repetidaConLaCajaCerrada() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        String dueno = t.dueno(this);
        String pedido = cuerpo(clave(), cliente, "EFECTIVO", "40");
        cobrar(dueno, pedido).andExpect(status().isCreated());
        jdbc.update("update caja set abierta = null where id = ?", t.caja());
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        cobrar(dueno, pedido).andExpect(status().isOk());

        assertThat(recibosDe(t.f().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma clave con otro total, otro medio u otro cliente es 422 CLAVE_CON_OTRO_CONTENIDO y no cambia nada")
    void claveConOtroContenido() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        long otro = cliente(t.f(), "1000");
        String dueno = t.dueno(this);
        String clave = clave();
        cobrar(dueno, cuerpo(clave, cliente, "EFECTIVO", "40")).andExpect(status().isCreated());

        for (String distinto : List.of(cuerpo(clave, cliente, "EFECTIVO", "41"), cuerpo(clave, cliente, "TRANSFERENCIA", "40"),
                cuerpo(clave, otro, "EFECTIVO", "40"))) {
            ResultActions respuesta = cobrar(dueno, distinto).andExpect(status().isUnprocessableEntity());
            JsonNode error = json(respuesta);
            assertThat(error.get("motivo").asText()).as(distinto).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
            assertThat(error.get("message").asText()).isEqualTo("Este cobro ya se registró con otros datos");
        }

        assertThat(recibosDe(t.f().id())).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("-40");
        assertThat(saldo(otro)).isEqualByComparingTo("0");
        assertInvariante(cliente);
        assertInvariante(otro);
    }

    // ------------------------------------------------------------------ caja

    @Test
    @DisplayName("sin caja abierta: 422 CAJA_CERRADA «Abrí la caja para cobrar.», sin recibo ni número consumido")
    void sinCaja() throws Exception {
        Tienda t = tiendaSinCaja();
        long cliente = cliente(t.f(), "1000");

        JsonNode error = json(cobrar(t.dueno(this), cuerpo(clave(), cliente, "EFECTIVO", "40")).andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
        assertThat(error.get("message").asText()).isEqualTo("Abrí la caja para cobrar.");
        assertThat(error.has("comprobantes")).isFalse();
        nadaGuardado(t, cliente);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("la caja abierta es de otro día: 422 CAJA_DE_OTRO_DIA, sin recibo ni número consumido")
    void cajaDeOtroDia() throws Exception {
        Tienda t = tiendaSinCaja();
        cajaAbiertaEl(t.f().id(), LocalDateTime.now(HoraArgentina.ZONA).minusDays(2));
        long cliente = cliente(t.f(), "1000");

        JsonNode error = json(cobrar(t.dueno(this), cuerpo(clave(), cliente, "EFECTIVO", "40")).andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("motivo").asText()).isEqualTo("CAJA_DE_OTRO_DIA");
        assertThat(error.get("message").asText()).startsWith("La caja del ").endsWith("sigue abierta. Cerrala antes de cobrar.");
        nadaGuardado(t, cliente);
    }

    // ------------------------------------------------------------------ cliente y comprobantes

    @Test
    @DisplayName("un cliente desactivado es 422 «El cliente está desactivado.»; uno de otra ferretería o inexistente, 404")
    void clienteDesactivadoYAjeno() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long desactivado = cliente(t.f(), "1000");
        jdbc.update("update cliente set activo = 0 where id = ?", desactivado);
        long ajeno = cliente(otra.f(), "1000");
        String dueno = t.dueno(this);

        JsonNode error = json(cobrar(dueno, cuerpo(clave(), desactivado, "EFECTIVO", "40")).andExpect(status().isUnprocessableEntity()));
        cobrar(dueno, cuerpo(clave(), ajeno, "EFECTIVO", "40")).andExpect(status().isNotFound());
        cobrar(dueno, cuerpo(clave(), Long.MAX_VALUE, "EFECTIVO", "40")).andExpect(status().isNotFound());

        assertThat(error.get("message").asText()).isEqualTo("El cliente está desactivado.");
        nadaGuardado(t, desactivado);
        assertThat(recibosDe(otra.f().id())).isZero();
        assertInvariante(ajeno);
    }

    @Test
    @DisplayName("un comprobante de otro cliente, de otra ferretería o inexistente es 404, sin recibo ni número consumido")
    void comprobanteAjeno() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long cliente = cliente(t.f(), "1000");
        long otroCliente = cliente(t.f(), "1000");
        VentaSuelta deOtroCliente = ventaEnLibro(t.f(), otroCliente, "100");
        long clienteAjeno = cliente(otra.f(), "1000");
        VentaSuelta deOtraFerreteria = ventaEnLibro(otra.f(), clienteAjeno, "100");
        String dueno = t.dueno(this);

        for (long comprobante : List.of(deOtroCliente.comprobanteId(), deOtraFerreteria.comprobanteId(), Long.MAX_VALUE)) {
            JsonNode error = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "50", imp(comprobante, "50")))
                    .andExpect(status().isNotFound()));
            assertThat(error.get("message").asText()).isEqualTo("Comprobante no encontrado en la cuenta del cliente");
        }

        nadaGuardado(t, cliente);
        assertThat(imputadoA(deOtroCliente.comprobanteId())).isEqualByComparingTo("0");
        assertThat(imputadoA(deOtraFerreteria.comprobanteId())).isEqualByComparingTo("0");
        assertInvariante(cliente);
        assertInvariante(otroCliente);
        assertInvariante(clienteAjeno);
    }

    @Test
    @DisplayName("sobre-imputación: 409 IMPUTACION_EXCEDE_DEUDA con la deuda ACTUAL de los comprobantes afectados; no guarda nada")
    void sobreImputacion() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta a = ventaEnLibro(t.f(), cliente, "100");
        VentaSuelta b = ventaEnLibro(t.f(), cliente, "500");
        String dueno = t.dueno(this);
        cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "80", imp(a.comprobanteId(), "80"))).andExpect(status().isCreated());
        long recibos = recibosDe(t.f().id());
        Long numero = ultimoNumero(t.f().id());

        ResultActions respuesta = cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "150", imp(a.comprobanteId(), "50"),
                imp(b.comprobanteId(), "100")));
        String cuerpoCrudo = texto(respuesta.andExpect(status().isConflict()));
        JsonNode error = objectMapper.readTree(cuerpoCrudo);

        assertThat(error.get("status").asInt()).isEqualTo(409);
        assertThat(error.get("motivo").asText()).isEqualTo("IMPUTACION_EXCEDE_DEUDA");
        assertThat(error.get("message").asText()).isEqualTo("La deuda de algún comprobante cambió. Revisá la imputación y confirmá de nuevo.");
        assertThat(error.get("comprobantes")).hasSize(1);
        assertThat(error.get("comprobantes").get(0).get("comprobanteId").asLong()).isEqualTo(a.comprobanteId());
        assertThat(error.get("comprobantes").get(0).get("referencia").asText()).isEqualTo("Venta N.º " + a.numero());
        assertThat(error.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("20");
        assertThat(cuerpoCrudo).contains("\"pendiente\":20.00");
        assertThat(recibosDe(t.f().id())).isEqualTo(recibos);
        assertThat(ultimoNumero(t.f().id())).isEqualTo(numero);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("80");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("0");
        assertThat(saldo(cliente)).isEqualByComparingTo("520");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un comprobante ya saldado (pendiente 0) también es 409, no un 201 ni un 422")
    void comprobanteSaldado() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "100");
        String dueno = t.dueno(this);
        cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "100", imp(venta.comprobanteId(), "100"))).andExpect(status().isCreated());

        JsonNode error = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "1", imp(venta.comprobanteId(), "1")))
                .andExpect(status().isConflict()));

        assertThat(error.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ adelanto y a cuenta

    @Test
    @DisplayName("ADELANTO (#5574): cliente activo con saldo 0, sin pendientes y sin cuenta habilitada → 201, aCuenta = total, saldo −total")
    void adelanto() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);

        JsonNode recibo = json(cobrar(t.dueno(this), cuerpo(clave(), cliente, "EFECTIVO", "500")).andExpect(status().isCreated()));

        assertThat(recibo.get("imputaciones")).isEmpty();
        assertThat(recibo.get("aCuenta").decimalValue()).isEqualByComparingTo("500");
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("-500");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("imputaciones [] (o sin la clave) con pendientes: 201 todo a cuenta; la deuda de los comprobantes no cambia")
    void sinImputarConPendientes() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "300");
        String dueno = t.dueno(this);

        JsonNode vacia = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "100")).andExpect(status().isCreated()));
        JsonNode sinClave = json(postJson("/api/cobranzas", dueno, "{\"clave\":\"" + clave() + "\",\"clienteId\":" + cliente
                + ",\"medio\":\"TRANSFERENCIA\",\"total\":50}").andExpect(status().isCreated()));

        assertThat(vacia.get("aCuenta").decimalValue()).isEqualByComparingTo("100");
        assertThat(sinClave.get("aCuenta").decimalValue()).isEqualByComparingTo("50");
        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("0");
        assertThat(saldo(cliente)).isEqualByComparingTo("150");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("cobrar más que la deuda: el excedente queda a cuenta y el saldo a favor")
    void excedenteACuenta() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "300");

        JsonNode recibo = json(cobrar(t.dueno(this), cuerpo(clave(), cliente, "EFECTIVO", "500", imp(venta.comprobanteId(), "300")))
                .andExpect(status().isCreated()));

        assertThat(recibo.get("aCuenta").decimalValue()).isEqualByComparingTo("200");
        assertThat(saldo(cliente)).isEqualByComparingTo("-200");
        assertInvariante(cliente);
    }

    // ------------------------------------------------------------------ 400

    @Test
    @DisplayName("400 por campo, sin guardar nada ni consumir número: medio, total, clave, renglón ≤ 0, comprobante repetido, Σ > total")
    void formaInvalida() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta a = ventaEnLibro(t.f(), cliente, "300");
        VentaSuelta b = ventaEnLibro(t.f(), cliente, "300");
        String dueno = t.dueno(this);

        JsonNode medio = json(cobrar(dueno, cuerpo(clave(), cliente, "DEBITO", "10")).andExpect(status().isBadRequest()));
        JsonNode cuentaCorriente = json(cobrar(dueno, cuerpo(clave(), cliente, "CUENTA_CORRIENTE", "10")).andExpect(status().isBadRequest()));
        JsonNode total = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "0")).andExpect(status().isBadRequest()));
        JsonNode muchosDecimales = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "10.001")).andExpect(status().isBadRequest()));
        JsonNode sobreElTope = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "10000000000000")).andExpect(status().isBadRequest()));
        JsonNode claveRota = json(cobrar(dueno, cuerpo("no-es-uuid", cliente, "EFECTIVO", "10")).andExpect(status().isBadRequest()));
        JsonNode renglon = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "100", imp(a.comprobanteId(), "0")))
                .andExpect(status().isBadRequest()));
        JsonNode repetido = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "100", imp(a.comprobanteId(), "10"),
                imp(a.comprobanteId(), "20"))).andExpect(status().isBadRequest()));
        JsonNode suma = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "100", imp(a.comprobanteId(), "60"),
                imp(b.comprobanteId(), "60"))).andExpect(status().isBadRequest()));

        assertThat(medio.get("errors").get("medio").asText()).isEqualTo("Elegí efectivo o transferencia");
        assertThat(cuentaCorriente.get("errors").get("medio").asText()).isEqualTo("Elegí efectivo o transferencia");
        assertThat(total.get("errors").has("total")).isTrue();
        assertThat(muchosDecimales.get("errors").has("total")).isTrue();
        assertThat(sobreElTope.get("errors").has("total")).isTrue();
        assertThat(claveRota.get("errors").has("clave")).isTrue();
        // Decisión de claves: el renglón inválido lleva su índice (imputaciones[i].campo) para marcar la fila; lo que afecta al
        // conjunto (repetido, Σ > total) va en «imputaciones».
        assertThat(renglon.get("errors").get("imputaciones[0].importe").asText()).isEqualTo("El importe tiene que ser mayor que cero.");
        assertThat(repetido.get("errors").get("imputaciones").asText()).isEqualTo("Cada comprobante va una sola vez.");
        assertThat(suma.get("errors").get("imputaciones").asText()).isEqualTo("Lo imputado supera el total del recibo.");
        nadaGuardado(t, cliente);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un cuerpo que no es JSON es 400, nunca 500")
    void cuerpoRoto() throws Exception {
        Tienda t = tienda();

        postJson("/api/cobranzas", t.dueno(this), "{no es json").andExpect(status().isBadRequest());
        postJson("/api/cobranzas", t.dueno(this), "").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ GET del recibo

    @Test
    @DisplayName("GET /api/recibos/{id} dice lo mismo que el 201 y la repetida, literalmente")
    void getRecibo() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        VentaSuelta a = ventaEnLibro(t.f(), cliente, "300");
        String dueno = t.dueno(this);
        String pedido = cuerpo(clave(), cliente, "TRANSFERENCIA", "300", imp(a.comprobanteId(), "100"));
        String original = texto(cobrar(dueno, pedido).andExpect(status().isCreated()));
        long id = objectMapper.readTree(original).get("id").asLong();

        String consultado = texto(getResult("/api/recibos/" + id, dueno).andExpect(status().isOk()));
        String repetida = texto(cobrar(dueno, pedido).andExpect(status().isOk()));

        assertThat(consultado).isEqualTo(original);
        assertThat(repetida).isEqualTo(original);
    }

    @Test
    @DisplayName("GET de un recibo inexistente es 404")
    void getReciboInexistente() throws Exception {
        Tienda t = tienda();

        JsonNode error = json(getResult("/api/recibos/" + Long.MAX_VALUE, t.dueno(this)).andExpect(status().isNotFound()));

        assertThat(error.get("message").asText()).isEqualTo("Recibo no encontrado");
    }

    // ------------------------------------------------------------------ CCT3-404: rol

    @Test
    @DisplayName("el Vendedor recibe 403 en POST y en GET, también con un cuerpo inválido, y no se consume ningún número")
    void vendedorNoCobra() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        String dueno = t.dueno(this);
        long recibo = json(cobrar(dueno, cuerpo(clave(), cliente, "EFECTIVO", "10")).andExpect(status().isCreated())).get("id").asLong();
        Long numero = ultimoNumero(t.f().id());
        String vendedor = t.vendedor(this);

        cobrar(vendedor, cuerpo(clave(), cliente, "EFECTIVO", "10")).andExpect(status().isForbidden());
        cobrar(vendedor, cuerpo("no-es-uuid", cliente, "DEBITO", "-5")).andExpect(status().isForbidden());
        cobrar(vendedor, "{no es json").andExpect(status().isForbidden());
        String cuerpoGet = texto(getResult("/api/recibos/" + recibo, vendedor).andExpect(status().isForbidden()));

        assertThat(cuerpoGet).doesNotContain("clienteNombre").doesNotContain("total");
        assertThat(recibosDe(t.f().id())).isEqualTo(1);
        assertThat(ultimoNumero(t.f().id())).isEqualTo(numero);
        assertThat(saldo(cliente)).isEqualByComparingTo("-10");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("el SUPER_ADMIN (sin ferretería) es 403 en POST y GET")
    void superAdminFuera() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), "1000");
        String admin = login("admin@demo.local");

        cobrar(admin, cuerpo(clave(), cliente, "EFECTIVO", "10")).andExpect(status().isForbidden());
        getResult("/api/recibos/1", admin).andExpect(status().isForbidden());

        nadaGuardado(t, cliente);
    }

    @Test
    @DisplayName("sin token es 401 en POST y GET")
    void sinToken() throws Exception {
        mvc.perform(post("/api/cobranzas").contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo(clave(), 1, "EFECTIVO", "10"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recibos/1")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ CCT3-404: tenant

    @Test
    @DisplayName("el recibo de otra ferretería es 404 por GET (aunque el id exista) y no se filtra ningún dato")
    void reciboAjeno() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long clienteB = cliente(b.f(), "1000");
        JsonNode reciboB = json(cobrar(b.dueno(this), cuerpo(clave(), clienteB, "EFECTIVO", "75")).andExpect(status().isCreated()));

        JsonNode ajeno = json(getResult("/api/recibos/" + reciboB.get("id").asLong(), a.dueno(this)).andExpect(status().isNotFound()));
        ((ObjectNode) ajeno).remove("timestamp"); // la hora puede traer «75» por azar
        getResult("/api/recibos/" + reciboB.get("id").asLong(), b.dueno(this)).andExpect(status().isOk());

        assertThat(ajeno.toString()).doesNotContain("75").doesNotContain("clienteNombre");
    }

    @Test
    @DisplayName("la misma clave en dos ferreterías no choca: dos recibos N.º 1 independientes")
    void mismaClaveEnDosFerreterias() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long clienteA = cliente(a.f(), "1000");
        long clienteB = cliente(b.f(), "1000");
        String clave = clave();

        JsonNode deA = json(cobrar(a.dueno(this), cuerpo(clave, clienteA, "EFECTIVO", "30")).andExpect(status().isCreated()));
        JsonNode deB = json(cobrar(b.dueno(this), cuerpo(clave, clienteB, "EFECTIVO", "30")).andExpect(status().isCreated()));

        assertThat(deA.get("numero").asLong()).isEqualTo(1L);
        assertThat(deB.get("numero").asLong()).isEqualTo(1L);
        assertThat(deA.get("id").asLong()).isNotEqualTo(deB.get("id").asLong());
        assertThat(deA.get("clienteId").asLong()).isEqualTo(clienteA);
        assertThat(deB.get("clienteId").asLong()).isEqualTo(clienteB);
        assertInvariante(clienteA);
        assertInvariante(clienteB);
    }

    @Test
    @DisplayName("el resumen de la caja de A suma solo los recibos de A, no los de B")
    void resumenDeCajaSoloPropio() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long clienteA = cliente(a.f(), "1000");
        long clienteB = cliente(b.f(), "1000");
        cobrar(a.dueno(this), cuerpo(clave(), clienteA, "EFECTIVO", "30")).andExpect(status().isCreated());
        cobrar(b.dueno(this), cuerpo(clave(), clienteB, "EFECTIVO", "900")).andExpect(status().isCreated());
        cobrar(b.dueno(this), cuerpo(clave(), clienteB, "TRANSFERENCIA", "100")).andExpect(status().isCreated());

        JsonNode cajaA = getJson("/api/cajas/" + a.caja(), a.dueno(this));
        getResult("/api/cajas/" + b.caja(), a.dueno(this)).andExpect(status().isNotFound());

        assertThat(cajaA.get("cobranzas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("30");
        assertThat(cajaA.get("cobranzas").get("TRANSFERENCIA").decimalValue()).isEqualByComparingTo("0");
        assertThat(cajaA.get("totalCobranzas").decimalValue()).isEqualByComparingTo("30");
        assertThat(cajaA.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("30");
    }
}
