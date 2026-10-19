package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.util.Arrays;
import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-001, CC-002: el límite de cuenta corriente del cliente y el saldo de solo lectura para JPA. Dominio puro. */
class ClienteTest {

    private static Cliente nuevo() {
        return Cliente.de(1L, "Cliente", null, null, CondicionIvaCliente.CONSUMIDOR_FINAL, null, null, null, null);
    }

    @Test
    @DisplayName("un cliente nuevo no tiene cuenta corriente (límite null) y su saldo es 0")
    void nacesinCuentaCorriente() {
        Cliente c = nuevo();

        assertThat(c.getLimiteCuentaCorriente()).isNull();
        assertThat(c.getSaldo()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("cambiarLimite acepta un valor positivo, el cero (habilitada sin crédito) y null (deshabilita)")
    void cambiarLimiteValido() {
        Cliente c = nuevo();

        c.cambiarLimite(new BigDecimal("1500.00"));
        assertThat(c.getLimiteCuentaCorriente()).isEqualByComparingTo("1500.00");

        c.cambiarLimite(BigDecimal.ZERO);
        assertThat(c.getLimiteCuentaCorriente()).isEqualByComparingTo("0");

        c.cambiarLimite(null);
        assertThat(c.getLimiteCuentaCorriente()).isNull();
    }

    @Test
    @DisplayName("cambiarLimite rechaza un límite negativo y no lo cambia")
    void cambiarLimiteNegativo() {
        Cliente c = nuevo();
        c.cambiarLimite(new BigDecimal("100.00"));

        assertThatThrownBy(() -> c.cambiarLimite(new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(c.getLimiteCuentaCorriente()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("el saldo no se inserta ni se actualiza desde JPA y no hay setter; la entidad actualiza solo columnas sucias")
    void saldoDeSoloLectura() throws NoSuchFieldException {
        Column columna = Cliente.class.getDeclaredField("saldo").getAnnotation(Column.class);

        assertThat(columna.insertable()).isFalse();
        assertThat(columna.updatable()).isFalse();
        assertThat(Cliente.class.isAnnotationPresent(DynamicUpdate.class)).isTrue();
        assertThat(Arrays.stream(Cliente.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("setSaldo");
    }
}
