package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRechazadaException.ExcesoLimite;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-005, CC-006, CC-007, CC-013: el cuerpo de los rechazos de cuenta corriente, y que los de siempre no cambian de forma. */
class VentaRechazadaResponseTest {

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    JsonNode cuerpoDe(VentaRechazadaException e) {
        return json.valueToTree(VentaRechazadaResponse.de(e));
    }

    static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("NO_HABILITADA: 422, el mensaje nombra al cliente, articulos vacío y sin objeto cuentaCorriente")
    void noHabilitada() {
        VentaRechazadaException e = VentaRechazadaException.cuentaNoHabilitada("Ferretería Norte SRL");

        JsonNode cuerpo = cuerpoDe(e);

        assertThat(e.motivo()).isEqualTo(Motivo.CUENTA_CORRIENTE_NO_HABILITADA);
        assertThat(cuerpo.get("status").asInt()).isEqualTo(422);
        assertThat(cuerpo.get("motivo").asText()).isEqualTo("CUENTA_CORRIENTE_NO_HABILITADA");
        assertThat(cuerpo.get("message").asText()).isEqualTo("Ferretería Norte SRL no tiene cuenta corriente habilitada.");
        assertThat(cuerpo.get("articulos").isArray()).isTrue();
        assertThat(cuerpo.get("articulos")).isEmpty();
        assertThat(cuerpo.has("cuentaCorriente")).isFalse();
    }

    @Test
    @DisplayName("LIMITE para el Dueño: 422, «Autorizala para seguir» y cuentaCorriente con puedeAutorizar y todas las cifras")
    void limiteDueno() {
        VentaRechazadaException e = VentaRechazadaException.limiteCuentaCorriente(
                new ExcesoLimite(true, bd("4000.00"), bd("5000.00"), bd("1000.00"), bd("2500.00"), bd("1500.00")));

        JsonNode cuerpo = cuerpoDe(e);

        assertThat(e.motivo()).isEqualTo(Motivo.LIMITE_CUENTA_CORRIENTE);
        assertThat(cuerpo.get("status").asInt()).isEqualTo(422);
        assertThat(cuerpo.get("motivo").asText()).isEqualTo("LIMITE_CUENTA_CORRIENTE");
        assertThat(cuerpo.get("message").asText())
                .isEqualTo("Esta venta supera el límite de cuenta corriente del cliente. Autorizala para seguir.");
        JsonNode cuenta = cuerpo.get("cuentaCorriente");
        assertThat(cuenta.get("puedeAutorizar").asBoolean()).isTrue();
        assertThat(cuenta.get("saldo").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(cuenta.get("limite").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(cuenta.get("disponible").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(cuenta.get("importe").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(cuenta.get("exceso").decimalValue()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("LIMITE para el Vendedor: «Pedile al Dueño» y cuentaCorriente solo con puedeAutorizar=false, sin ninguna cifra")
    void limiteVendedor() {
        VentaRechazadaException e = VentaRechazadaException.limiteCuentaCorriente(ExcesoLimite.sinCifras());

        JsonNode cuerpo = cuerpoDe(e);

        assertThat(cuerpo.get("status").asInt()).isEqualTo(422);
        assertThat(cuerpo.get("message").asText())
                .isEqualTo("Esta venta supera el límite de cuenta corriente del cliente. Pedile al Dueño que la autorice.");
        JsonNode cuenta = cuerpo.get("cuentaCorriente");
        assertThat(cuenta.get("puedeAutorizar").asBoolean()).isFalse();
        assertThat(cuenta.has("saldo")).isFalse();
        assertThat(cuenta.has("limite")).isFalse();
        assertThat(cuenta.has("disponible")).isFalse();
        assertThat(cuenta.has("importe")).isFalse();
        assertThat(cuenta.has("exceso")).isFalse();
        assertThat(cuerpo.toString()).doesNotContain("saldo", "limite\"", "exceso");
    }

    @Test
    @DisplayName("regresión: un rechazo de precios sigue trayendo total y artículos, y no trae cuentaCorriente")
    void preciosNoCambia() {
        VentaRechazadaException e = new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, "cambiaron",
                List.of(new VentaRechazadaException.Articulo(7L, "Tornillo", bd("10.00"), bd("12.00"), bd("12.00"))),
                bd("12.00"));

        JsonNode cuerpo = cuerpoDe(e);

        assertThat(cuerpo.get("status").asInt()).isEqualTo(409);
        assertThat(cuerpo.get("total").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(cuerpo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(cuerpo.has("cuentaCorriente")).isFalse();
    }
}
