package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.common.exception.ErroresDeValidacion;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.CobranzaPedido;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CCT3-402 (CC-029, CC-030): la forma del pedido de cobranza, sin Spring. El mensaje de cada campo es el mismo que el del
 * servicio ({@code RegistroCobranza}), así el 400 es uno solo venga de donde venga.
 */
class CobranzaRequestTest {

    private static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final Validator VALIDADOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CobranzaRequest leer(String cuerpo) throws Exception {
        return JSON.readValue(cuerpo, CobranzaRequest.class);
    }

    private static String cuerpo(String clave, String clienteId, String medio, String total, String imputaciones) {
        return """
                {"clave":%s,"clienteId":%s,"medio":%s,"total":%s,"imputaciones":%s}"""
                .formatted(clave, clienteId, medio, total, imputaciones);
    }

    private static String valido(String total, String imputaciones) {
        return cuerpo("\"" + CLAVE + "\"", "7", "\"EFECTIVO\"", total, imputaciones);
    }

    /** Campo a mensaje, con la misma regla de prioridad que el handler global. */
    private static Map<String, String> errores(CobranzaRequest request) {
        ErroresDeValidacion errores = new ErroresDeValidacion();
        Set<ConstraintViolation<CobranzaRequest>> violaciones = VALIDADOR.validate(request);
        violaciones.forEach(v -> errores.agregar(v.getPropertyPath().toString(),
                v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(), v.getMessage()));
        return errores.mensajes();
    }

    @Test
    @DisplayName("un pedido bien armado no tiene errores y se vuelve un CobranzaPedido con las imputaciones en orden")
    void valido() throws Exception {
        CobranzaRequest request = leer(valido("1000.50", "[{\"comprobanteId\":3,\"importe\":600.25},{\"comprobanteId\":2,\"importe\":400}]"));

        assertThat(errores(request)).isEmpty();
        CobranzaPedido pedido = request.aPedido();
        assertThat(pedido.clave()).isEqualTo(CLAVE);
        assertThat(pedido.clienteId()).isEqualTo(7L);
        assertThat(pedido.medio()).isEqualTo(MedioPago.EFECTIVO);
        assertThat(pedido.total()).isEqualByComparingTo("1000.50");
        assertThat(pedido.imputaciones()).containsExactly(new Imputar(3L, new BigDecimal("600.25")),
                new Imputar(2L, new BigDecimal("400")));
    }

    @Test
    @DisplayName("imputaciones ausente, null o vacía es un cobro todo «a cuenta» (adelanto): válido y sin renglones")
    void sinImputaciones() throws Exception {
        for (String imputaciones : List.of("[]", "null")) {
            CobranzaRequest request = leer(valido("500", imputaciones));
            assertThat(errores(request)).as(imputaciones).isEmpty();
            assertThat(request.aPedido().imputaciones()).as(imputaciones).isEmpty();
        }
        CobranzaRequest sinClave = leer("{\"clave\":\"" + CLAVE + "\",\"clienteId\":7,\"medio\":\"TRANSFERENCIA\",\"total\":5}");
        assertThat(errores(sinClave)).isEmpty();
        assertThat(sinClave.aPedido().imputaciones()).isEmpty();
        assertThat(sinClave.aPedido().medio()).isEqualTo(MedioPago.TRANSFERENCIA);
    }

    @Test
    @DisplayName("clave ausente o que no es un UUID: error en «clave»; clienteId ausente: error en «clienteId»")
    void claveYCliente() throws Exception {
        assertThat(errores(leer(cuerpo("null", "7", "\"EFECTIVO\"", "10", "[]")))).containsOnlyKeys("clave");
        assertThat(errores(leer(cuerpo("\"no-es-uuid\"", "7", "\"EFECTIVO\"", "10", "[]"))))
                .containsEntry("clave", "La clave del cobro no es válida.");
        assertThat(errores(leer(cuerpo("\"" + CLAVE + "\"", "null", "\"EFECTIVO\"", "10", "[]"))))
                .containsEntry("clienteId", "Elegí el cliente a cobrar.");
    }

    @Test
    @DisplayName("total 0, negativo, con 3 decimales o sobre el tope: error en «total»; 100,10 es válido")
    void total() throws Exception {
        assertThat(errores(leer(valido("0", "[]")))).containsOnlyKeys("total");
        assertThat(errores(leer(valido("-5", "[]")))).containsOnlyKeys("total");
        assertThat(errores(leer(valido("100.001", "[]")))).containsEntry("total", "El total admite hasta 2 decimales.");
        assertThat(errores(leer(valido("10000000000000", "[]")))).containsEntry("total", "El total supera el máximo permitido.");
        assertThat(errores(leer(valido("null", "[]")))).containsOnlyKeys("total");
        // @Digits cuenta la escala del número tal como llegó (igual que en ventas): 100.1000 es 400 aunque valga 100,10.
        assertThat(errores(leer(valido("100.1000", "[]")))).containsOnlyKeys("total");
        assertThat(errores(leer(valido("100.10", "[]")))).isEmpty();
        assertThat(errores(leer(valido("9999999999999.99", "[]")))).isEmpty();
    }

    @Test
    @DisplayName("medio DEBITO, CREDITO, CUENTA_CORRIENTE, en minúscula o ausente: «Elegí efectivo o transferencia»")
    void medio() throws Exception {
        for (String medio : List.of("\"DEBITO\"", "\"CREDITO\"", "\"CUENTA_CORRIENTE\"", "\"efectivo\"", "\"\"", "null")) {
            assertThat(errores(leer(cuerpo("\"" + CLAVE + "\"", "7", medio, "10", "[]"))))
                    .as(medio).containsExactly(Map.entry("medio", "Elegí efectivo o transferencia"));
        }
    }

    @Test
    @DisplayName("importe de un renglón ≤ 0, con 3 decimales o sin comprobante: error con el índice, como lo da el servicio")
    void renglones() throws Exception {
        CobranzaRequest cero = leer(valido("100", "[{\"comprobanteId\":1,\"importe\":0}]"));
        CobranzaRequest tresDecimales = leer(valido("100", "[{\"comprobanteId\":1,\"importe\":10},{\"comprobanteId\":2,\"importe\":1.005}]"));
        CobranzaRequest sinComprobante = leer(valido("100", "[{\"importe\":10}]"));
        CobranzaRequest sinImporte = leer(valido("100", "[{\"comprobanteId\":1}]"));
        CobranzaRequest renglonNulo = leer(valido("100", "[null]"));

        assertThat(errores(cero)).containsExactly(Map.entry("imputaciones[0].importe", "El importe tiene que ser mayor que cero."));
        assertThat(errores(tresDecimales)).containsExactly(Map.entry("imputaciones[1].importe", "El importe admite hasta 2 decimales."));
        assertThat(errores(sinComprobante)).containsExactly(Map.entry("imputaciones[0].comprobanteId", "Elegí el comprobante."));
        assertThat(errores(sinImporte)).containsKey("imputaciones[0].importe");
        assertThat(errores(renglonNulo).keySet()).singleElement().asString().startsWith("imputaciones[0]");
    }

    @Test
    @DisplayName("más de 200 renglones de imputación: error en «imputaciones»; con 200 pasa")
    void tope() throws Exception {
        String renglon = "{\"comprobanteId\":%d,\"importe\":0.01}";
        StringBuilder doscientos = new StringBuilder("[");
        for (int i = 1; i <= 200; i++) {
            doscientos.append(i > 1 ? "," : "").append(renglon.formatted(i));
        }
        String con200 = doscientos + "]";
        String con201 = doscientos + "," + renglon.formatted(201) + "]";

        assertThat(errores(leer(valido("100", con200)))).isEmpty();
        assertThat(errores(leer(valido("100", con201)))).containsOnlyKeys("imputaciones");
    }
}
