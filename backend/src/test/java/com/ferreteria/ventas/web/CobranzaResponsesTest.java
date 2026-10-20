package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException.DeudaActual;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import com.ferreteria.ventas.service.CobroRegistrado;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** CCT3-402 (CC-036, CC-038): el recibo que devuelve la cobranza y el cuerpo de sus rechazos, sin Spring. */
class CobranzaResponsesTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Recibo recibo(String total) {
        Recibo recibo = Recibo.emitir(3L, 12L, "7c9e6679-7425-40de-944b-e07fc1f90ae7", 5L, "Ferretería Norte SRL", 40L, 9L,
                LocalDateTime.of(2026, 10, 19, 11, 30), MedioPago.TRANSFERENCIA, bd(total));
        ReflectionTestUtils.setField(recibo, "id", 77L);
        return recibo;
    }

    private static ImputacionAsentada imputacion(long comprobanteId, String referencia, String importe) {
        return new ImputacionAsentada(comprobanteId, referencia, bd(importe));
    }

    @Test
    @DisplayName("ReciboResponse lleva id, número, fecha, cliente, medio, total, aCuenta, cobradoPor e imputaciones; sin saldo ni «resta»")
    void formaDelRecibo() {
        CobroRegistrado cobro = new CobroRegistrado(recibo("1000.00"),
                List.of(imputacion(8L, "Venta N.º 8", "300.00"), imputacion(9L, "Venta N.º 9", "200.00")), "Ana Dueña", false);

        JsonNode json = JSON.valueToTree(ReciboResponse.de(cobro));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "numero", "fecha", "clienteId", "clienteNombre",
                "medio", "total", "aCuenta", "cobradoPor", "imputaciones");
        assertThat(json.get("id").asLong()).isEqualTo(77L);
        assertThat(json.get("numero").asLong()).isEqualTo(12L);
        assertThat(json.get("clienteId").asLong()).isEqualTo(5L);
        assertThat(json.get("clienteNombre").asText()).isEqualTo("Ferretería Norte SRL");
        assertThat(json.get("medio").asText()).isEqualTo("TRANSFERENCIA");
        assertThat(json.get("cobradoPor").asText()).isEqualTo("Ana Dueña");
        assertThat(json.get("imputaciones")).hasSize(2);
        assertThat(json.get("imputaciones").get(0).get("comprobanteId").asLong()).isEqualTo(8L);
        assertThat(json.get("imputaciones").get(0).get("referencia").asText()).isEqualTo("Venta N.º 8");
    }

    @Test
    @DisplayName("aCuenta = total − Σ imputaciones; sin imputaciones es todo el total (adelanto)")
    void aCuenta() {
        ReciboResponse parcial = ReciboResponse.de(new CobroRegistrado(recibo("1000.00"),
                List.of(imputacion(8L, "Venta N.º 8", "300.00"), imputacion(9L, "Venta N.º 9", "200.00")), "Ana", false));
        ReciboResponse adelanto = ReciboResponse.de(new CobroRegistrado(recibo("500.00"), List.of(), "Ana", false));
        ReciboResponse exacto = ReciboResponse.de(new CobroRegistrado(recibo("300.00"),
                List.of(imputacion(8L, "Venta N.º 8", "300.00")), "Ana", false));

        assertThat(parcial.aCuenta()).isEqualByComparingTo("500");
        assertThat(adelanto.aCuenta()).isEqualByComparingTo("500");
        assertThat(exacto.aCuenta()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("la repetida da el MISMO JSON que la original aunque las escalas sean otras (300 contra 300.00): literalmente igual")
    void laRepetidaEsIdentica() throws Exception {
        // La original sale con la escala del pedido; la repetida se lee de la base (escala 2): es el hallazgo de B5.
        CobroRegistrado original = new CobroRegistrado(recibo("300"),
                List.of(imputacion(8L, "Venta N.º 8", "100"), imputacion(9L, "Venta N.º 9", "50.5")), "Ana", false);
        CobroRegistrado repetida = new CobroRegistrado(recibo("300.00"),
                List.of(imputacion(8L, "Venta N.º 8", "100.00"), imputacion(9L, "Venta N.º 9", "50.50")), "Ana", true);

        String jsonOriginal = JSON.writeValueAsString(ReciboResponse.de(original));
        String jsonRepetida = JSON.writeValueAsString(ReciboResponse.de(repetida));

        assertThat(jsonRepetida).isEqualTo(jsonOriginal);
        assertThat(jsonOriginal).contains("\"total\":300.00", "\"aCuenta\":149.50", "\"importe\":100.00", "\"importe\":50.50");
    }

    @Test
    @DisplayName("el rechazo 409 lleva el motivo, el mensaje y las deudas actuales con 2 decimales")
    void rechazo409() throws Exception {
        CobranzaRechazadaException e = new CobranzaRechazadaException(Motivo.IMPUTACION_EXCEDE_DEUDA,
                "La deuda de algún comprobante cambió. Revisá la imputación y confirmá de nuevo.",
                List.of(new DeudaActual(8L, "Venta N.º 8", bd("0")), new DeudaActual(9L, "Venta N.º 9", bd("75.5"))));

        CobranzaRechazadaResponse respuesta = CobranzaRechazadaResponse.de(e);
        JsonNode json = JSON.valueToTree(respuesta);

        assertThat(json.get("status").asInt()).isEqualTo(409);
        assertThat(json.get("motivo").asText()).isEqualTo("IMPUTACION_EXCEDE_DEUDA");
        assertThat(json.get("message").asText()).isEqualTo("La deuda de algún comprobante cambió. Revisá la imputación y confirmá de nuevo.");
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.get("comprobantes")).hasSize(2);
        assertThat(json.get("comprobantes").get(0).get("comprobanteId").asLong()).isEqualTo(8L);
        assertThat(json.get("comprobantes").get(0).get("referencia").asText()).isEqualTo("Venta N.º 8");
        assertThat(JSON.writeValueAsString(respuesta)).contains("\"pendiente\":0.00", "\"pendiente\":75.50");
    }

    @Test
    @DisplayName("los rechazos 422 no llevan la clave «comprobantes»")
    void rechazos422() {
        for (Motivo motivo : List.of(Motivo.CAJA_CERRADA, Motivo.CAJA_DE_OTRO_DIA, Motivo.CLAVE_CON_OTRO_CONTENIDO)) {
            JsonNode json = JSON.valueToTree(CobranzaRechazadaResponse.de(new CobranzaRechazadaException(motivo, "Mensaje")));

            assertThat(json.get("status").asInt()).as(motivo.name()).isEqualTo(422);
            assertThat(json.get("motivo").asText()).isEqualTo(motivo.name());
            assertThat(json.get("message").asText()).isEqualTo("Mensaje");
            assertThat(json.has("comprobantes")).as(motivo.name()).isFalse();
        }
    }
}
