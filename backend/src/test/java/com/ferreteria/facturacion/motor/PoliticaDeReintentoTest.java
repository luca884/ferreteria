package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La configuración del job de reintento (cu-07-contingencias C5): se valida al arrancar (la pasada alcanza para una llamada
 * a WSFEv1 y el tope del backoff no es menor que el inicial) y el backoff de una serie arranca en el inicial, se duplica en
 * cada pasada seguida con error transitorio y no pasa del tope.
 */
class PoliticaDeReintentoTest {

    private static final Duration PRESUPUESTO = Duration.ofSeconds(60);

    private static PoliticaDeReintento politica(Duration topePasada, Duration inicial, Duration tope) {
        return new PoliticaDeReintento(Duration.ofSeconds(60), topePasada, inicial, tope, PRESUPUESTO);
    }

    @Test
    @DisplayName("una pasada más corta que una llamada a WSFEv1, o un tope del backoff menor que el inicial, no arrancan")
    void seValidaAlArrancar() {
        assertThatThrownBy(() -> politica(Duration.ofSeconds(59), Duration.ofSeconds(60), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Una pasada del job de reintento (app.facturacion.reintento.tope-pasada = PT59S) tiene que "
                        + "alcanzar para una llamada a WSFEv1 (app.facturacion.motor.presupuesto-llamada = PT1M).");
        assertThatThrownBy(() -> politica(Duration.ofSeconds(120), Duration.ofMinutes(31), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El tope del backoff (app.facturacion.reintento.backoff-tope = PT30M) no puede ser menor que "
                        + "el backoff inicial (app.facturacion.reintento.backoff-inicial = PT31M).");
        assertThatThrownBy(() -> politica(Duration.ofSeconds(120), Duration.ZERO, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> politica(PRESUPUESTO, Duration.ofMinutes(30), Duration.ofMinutes(30)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el backoff: 1 minuto, se duplica en cada pasada seguida con error y se queda en 30 minutos")
    void elBackoffSeDuplicaHastaElTope() {
        PoliticaDeReintento p = politica(Duration.ofSeconds(120), Duration.ofSeconds(60), Duration.ofMinutes(30));

        assertThat(p.espera(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.espera(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.espera(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(p.espera(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.espera(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.cada()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.topePasada()).isEqualTo(Duration.ofSeconds(120));
    }
}
