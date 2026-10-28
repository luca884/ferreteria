package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El TTL del lease tiene que superar el presupuesto de la llamada (L5): si la toma venciera con la llamada en vuelo, otra
 * instancia vería N−1, reenviaría y ARCA contestaría 10016. Con una configuración que no lo cumple el motor no arranca.
 */
class MotorFiscalLeaseTest {

    private final Clock reloj = Clock.fixed(Instant.parse("2026-10-28T13:15:30Z"), ZoneOffset.UTC);

    private MotorFiscal motor(Duration lease, Duration presupuesto) {
        return new MotorFiscal(mock(PasosFiscales.class), mock(WsfePorAmbiente.class), reloj, lease, presupuesto);
    }

    @Test
    @DisplayName("un lease que no supera el presupuesto de la llamada no arranca; uno que lo supera, sí")
    void elLeaseSuperaElPresupuesto() {
        assertThatThrownBy(() -> motor(Duration.ofSeconds(60), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("La toma de una serie (app.facturacion.motor.lease = PT1M) tiene que durar más que una llamada "
                        + "a WSFEv1 (app.facturacion.motor.presupuesto-llamada = PT1M): si venciera con la llamada en "
                        + "vuelo, otra instancia reenviaría el mismo número.");
        assertThatThrownBy(() -> motor(Duration.ofSeconds(30), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> motor(Duration.ofSeconds(61), Duration.ofSeconds(60))).doesNotThrowAnyException();
    }
}
