package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-022: el medio «cuenta corriente» existe y cabe en forma_pago.medio / pago.medio (VARCHAR(20)). Dominio puro. */
class MedioPagoTest {

    private static final int LARGO_COLUMNA_MEDIO = 20;

    @Test
    @DisplayName("CUENTA_CORRIENTE existe y se resuelve por nombre")
    void existeCuentaCorriente() {
        assertThat(MedioPago.valueOf("CUENTA_CORRIENTE")).isEqualTo(MedioPago.CUENTA_CORRIENTE);
        assertThat(MedioPago.CUENTA_CORRIENTE.name()).isEqualTo("CUENTA_CORRIENTE");
    }

    @Test
    @DisplayName("son cinco medios: los cuatro de siempre y la cuenta corriente")
    void cincoMedios() {
        assertThat(MedioPago.values()).containsExactlyInAnyOrder(MedioPago.EFECTIVO, MedioPago.DEBITO,
                MedioPago.CREDITO, MedioPago.TRANSFERENCIA, MedioPago.CUENTA_CORRIENTE);
    }

    @Test
    @DisplayName("todos los nombres caben en VARCHAR(20) de forma_pago.medio y pago.medio")
    void caben() {
        assertThat(Arrays.stream(MedioPago.values()).map(Enum::name))
                .allSatisfy(nombre -> assertThat(nombre.length()).isLessThanOrEqualTo(LARGO_COLUMNA_MEDIO));
        assertThat(MedioPago.CUENTA_CORRIENTE.name().length()).isEqualTo(16);
    }
}
