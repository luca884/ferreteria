package com.ferreteria.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RangoDeFechasTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 9);

    @Test
    @DisplayName("«Hasta» anterior a «Desde»: 400 en la clave hasta con el texto único de los rangos (bugs-relevamiento-celular, S4)")
    void hastaAnteriorADesde() {
        assertThatThrownBy(() -> RangoDeFechas.validar(HOY, HOY.minusDays(1)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                    assertThat(e.errors()).containsOnlyKeys("hasta");
                    assertThat(e.errors().get("hasta")).isEqualTo("La fecha «Hasta» no puede ser anterior a «Desde».");
                });
    }

    @Test
    @DisplayName("el mismo día, un rango en orden, o una sola fecha o ninguna: no se valida nada")
    void rangosValidos() {
        assertThatCode(() -> RangoDeFechas.validar(HOY, HOY)).doesNotThrowAnyException();
        assertThatCode(() -> RangoDeFechas.validar(HOY.minusDays(10), HOY)).doesNotThrowAnyException();
        assertThatCode(() -> RangoDeFechas.validar(HOY, null)).doesNotThrowAnyException();
        assertThatCode(() -> RangoDeFechas.validar(null, HOY)).doesNotThrowAnyException();
        assertThatCode(() -> RangoDeFechas.validar(null, null)).doesNotThrowAnyException();
    }
}
