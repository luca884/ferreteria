package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class CambioPrecioTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("cambia el costo y el precio: devuelve los cuatro valores")
    void cambiaCostoYPrecio() {
        Optional<CambioPrecio> cambio = CambioPrecio.entre(bd("100.00"), bd("200.00"), bd("170.00"), bd("330.00"));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("200");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("330");
    }

    @Test
    @DisplayName("cambia solo el precio: es un cambio (costo 100 -> 100, precio 170 -> 190)")
    void cambiaSoloElPrecio() {
        assertThat(CambioPrecio.entre(bd("100.00"), bd("100.00"), bd("170.00"), bd("190.00"))).isPresent();
    }

    @Test
    @DisplayName("cambia solo el costo: es un cambio")
    void cambiaSoloElCosto() {
        assertThat(CambioPrecio.entre(bd("100.00"), bd("120.00"), bd("170.00"), bd("170.00"))).isPresent();
    }

    @Test
    @DisplayName("no cambia nada: vacio")
    void sinCambios() {
        assertThat(CambioPrecio.entre(bd("100.00"), bd("100.00"), bd("170.00"), bd("170.00"))).isEmpty();
    }

    @Test
    @DisplayName("mismos valores con distinta escala (170 vs 170.00): vacio, se compara con compareTo")
    void distintaEscalaNoEsCambio() {
        assertThat(CambioPrecio.entre(bd("100"), bd("100.00"), bd("170.0"), bd("170.00"))).isEmpty();
    }

    @Test
    @DisplayName("alta: costo y precio anteriores null cuentan como cambio")
    void anterioresNullCuentanComoCambio() {
        Optional<CambioPrecio> cambio = CambioPrecio.entre(null, bd("100.00"), null, bd("170.00"));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isNull();
        assertThat(cambio.get().precioAnterior()).isNull();
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("100");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("170");
    }

    @Test
    @DisplayName("solo el costo anterior es null: es un cambio aunque el precio sea igual")
    void costoAnteriorNull() {
        assertThat(CambioPrecio.entre(null, bd("100.00"), bd("170.00"), bd("170.00"))).isPresent();
    }

    @Test
    @DisplayName("solo el precio anterior es null: es un cambio aunque el costo sea igual")
    void precioAnteriorNull() {
        assertThat(CambioPrecio.entre(bd("100.00"), bd("100.00"), null, bd("170.00"))).isPresent();
    }
}
