package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CCT3-401 y CCT3-404 (CC-028, CC-039, CC-050, CC-041) contra MySQL real: {@code GET /api/clientes/{id}/cuenta-corriente/pendientes}
 * es solo del Dueño; el Vendedor, el SUPER_ADMIN y quien no manda token no ven nada, y un cliente de otra ferretería da 404.
 * CUIT 30-8408NNNN-1 (el 30-8405 es de ReciboRepositoryTest).
 */
class PendientesIntegrationTest extends CuentaCorrienteTestBase {

    @Override
    protected String prefijoDeCuit() {
        return "30-8408";
    }

    private static String url(long clienteId) {
        return "/api/clientes/" + clienteId + "/cuenta-corriente/pendientes";
    }

    private String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("el Dueño recibe saldo, aCuenta y los comprobantes con pendiente > 0 en orden FIFO; los saldados no figuran")
    void duenoVeLosPendientesEnFifo() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "1000");
        VentaSuelta a = ventaEnLibro(f, cliente, "300");
        VentaSuelta b = ventaEnLibro(f, cliente, "200");
        VentaSuelta c = ventaEnLibro(f, cliente, "100");
        // Un recibo de 400: 100 a A, 100 a C (queda saldado) y 200 a cuenta.
        long recibo = cobroEnLibro(f, cliente, "400");
        imputar(f, cliente, recibo, a.comprobanteId(), "100");
        imputar(f, cliente, recibo, c.comprobanteId(), "100");

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("200");
        assertThat(respuesta.get("aCuenta").decimalValue()).isEqualByComparingTo("200");
        JsonNode comprobantes = respuesta.get("comprobantes");
        assertThat(comprobantes).hasSize(2);
        assertThat(comprobantes.get(0).get("comprobanteId").asLong()).isEqualTo(a.comprobanteId());
        assertThat(comprobantes.get(0).get("referencia").asText()).isEqualTo("Venta N.º " + a.numero());
        assertThat(comprobantes.get(0).get("importe").decimalValue()).isEqualByComparingTo("300");
        assertThat(comprobantes.get(0).get("imputado").decimalValue()).isEqualByComparingTo("100");
        assertThat(comprobantes.get(0).get("pendiente").decimalValue()).isEqualByComparingTo("200");
        assertThat(comprobantes.get(0).has("fecha")).isTrue();
        assertThat(comprobantes.get(1).get("comprobanteId").asLong()).isEqualTo(b.comprobanteId());
        assertThat(comprobantes.get(1).get("imputado").decimalValue()).isEqualByComparingTo("0");
        assertThat(comprobantes.get(1).get("pendiente").decimalValue()).isEqualByComparingTo("200");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("la respuesta cumple I2 por sí sola: Σ pendiente − aCuenta = saldo")
    void laRespuestaCumpleI2() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "5000");
        VentaSuelta a = ventaEnLibro(f, cliente, "333.33");
        ventaEnLibro(f, cliente, "100.10", "50.05");
        ventaEnLibro(f, cliente, "700");
        long recibo = cobroEnLibro(f, cliente, "900.50");
        imputar(f, cliente, recibo, a.comprobanteId(), "133.33");

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        BigDecimal pendientes = BigDecimal.ZERO;
        for (JsonNode c : respuesta.get("comprobantes")) {
            pendientes = pendientes.add(c.get("pendiente").decimalValue());
        }
        assertThat(pendientes.subtract(respuesta.get("aCuenta").decimalValue()))
                .isEqualByComparingTo(respuesta.get("saldo").decimalValue());
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("los importes viajan con 2 decimales en el JSON")
    void escalaDosEnElJson() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "1000");
        ventaEnLibro(f, cliente, "300");

        String cuerpo = texto(getResult(url(cliente), login(login.dueno())).andExpect(status().isOk()));

        assertThat(cuerpo).contains("\"saldo\":300.00", "\"aCuenta\":0.00", "\"importe\":300.00", "\"imputado\":0.00",
                "\"pendiente\":300.00");
    }

    @Test
    @DisplayName("cliente sin deuda: lista vacía, saldo 0 y aCuenta 0, aunque no tenga cuenta habilitada")
    void sinDeuda() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, null);

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        assertThat(respuesta.get("comprobantes")).isEmpty();
        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("0");
        assertThat(respuesta.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("adelanto: lista vacía, saldo −500 y aCuenta 500")
    void adelanto() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, null);
        cobroEnLibro(f, cliente, "500");

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        assertThat(respuesta.get("comprobantes")).isEmpty();
        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("-500");
        assertThat(respuesta.get("aCuenta").decimalValue()).isEqualByComparingTo("500");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CCT3B1-210: dos ventas 1.000 y 500, la primera pagada con un recibo después anulado → [1.000, 500], saldo 1.500, aCuenta 0")
    void reciboAnuladoVuelveAPendientes() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "5000");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000");
        VentaSuelta b = ventaEnLibro(f, cliente, "500");
        long recibo = cobroEnLibro(f, cliente, "1000");
        imputar(f, cliente, recibo, a.comprobanteId(), "1000");
        assertInvariante(cliente);
        anularRecibo(f, cliente, recibo);

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("1500");
        assertThat(respuesta.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
        JsonNode comprobantes = respuesta.get("comprobantes");
        assertThat(comprobantes).hasSize(2);
        assertThat(comprobantes.get(0).get("comprobanteId").asLong()).isEqualTo(a.comprobanteId());
        assertThat(comprobantes.get(0).get("imputado").decimalValue()).isEqualByComparingTo("0");
        assertThat(comprobantes.get(0).get("pendiente").decimalValue()).isEqualByComparingTo("1000");
        assertThat(comprobantes.get(1).get("comprobanteId").asLong()).isEqualTo(b.comprobanteId());
        assertThat(comprobantes.get(1).get("pendiente").decimalValue()).isEqualByComparingTo("500");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CCT3B1-210: un recibo con imputaciones no vigentes y otro vigente al mismo comprobante: solo restan las vigentes")
    void soloRestanLasImputacionesVigentes() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "5000");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000");
        long anulado = cobroEnLibro(f, cliente, "400");
        imputar(f, cliente, anulado, a.comprobanteId(), "400");
        anularRecibo(f, cliente, anulado);
        long vigente = cobroEnLibro(f, cliente, "300");
        imputar(f, cliente, vigente, a.comprobanteId(), "300");

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        JsonNode comprobantes = respuesta.get("comprobantes");
        assertThat(comprobantes).hasSize(1);
        assertThat(comprobantes.get(0).get("imputado").decimalValue()).as("300 de la vigente, no 700").isEqualByComparingTo("300");
        assertThat(comprobantes.get(0).get("pendiente").decimalValue()).isEqualByComparingTo("700");
        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("700");
        assertThat(respuesta.get("aCuenta").decimalValue()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un cliente desactivado se consulta bien: 200 con su deuda")
    void clienteDesactivado() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "1000");
        ventaEnLibro(f, cliente, "250");
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        JsonNode respuesta = getJson(url(cliente), login(login.dueno()));

        assertThat(respuesta.get("comprobantes")).hasSize(1);
        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("250");
    }

    // ---- CCT3-404: rol y tenant ----

    @Test
    @DisplayName("el Vendedor recibe 403 aunque el cliente sea de su ferretería, y no se le filtra ninguna cifra")
    void vendedorNoVe() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "1000");
        ventaEnLibro(f, cliente, "250");

        String cuerpo = texto(getResult(url(cliente), login(login.vendedor())).andExpect(status().isForbidden()));

        assertThat(cuerpo).doesNotContain("250").doesNotContain("saldo");
    }

    @Test
    @DisplayName("el SUPER_ADMIN (sin ferretería) también es 403")
    void superAdminFuera() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000");

        getResult(url(cliente), login("admin@demo.local")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sin token es 401")
    void sinToken() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000");

        mvc.perform(get(url(cliente))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un cliente de otra ferretería (o inexistente) da 404 sin importar qué ferretería tiene la deuda")
    void clienteAjeno() throws Exception {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        Logins loginA = logins(a);
        Logins loginB = logins(b);
        long deB = cliente(b, "1000");
        ventaEnLibro(b, deB, "250");
        long deA = cliente(a, "1000");
        ventaEnLibro(a, deA, "75");

        getResult(url(deB), login(loginA.dueno())).andExpect(status().isNotFound());
        getResult(url(Long.MAX_VALUE), login(loginA.dueno())).andExpect(status().isNotFound());
        JsonNode propio = getJson(url(deA), login(loginA.dueno()));
        JsonNode ajenoVistoPorSuDueno = getJson(url(deB), login(loginB.dueno()));

        assertThat(propio.get("saldo").decimalValue()).isEqualByComparingTo("75");
        assertThat(ajenoVistoPorSuDueno.get("saldo").decimalValue()).isEqualByComparingTo("250");
    }
}
