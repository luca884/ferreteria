package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-003, CC-004: la regla del límite de cuenta corriente. Dominio puro, sin Spring ni base. */
class CupoTest {

    private static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    private static Cupo cupo(String limite, String saldo, String importe) {
        return Cupo.de(bd(limite), bd(saldo), bd(importe));
    }

    @Test
    @DisplayName("límite NULL: la cuenta corriente no está habilitada y no alcanza para nada")
    void limiteNullNoHabilitada() {
        Cupo c = cupo(null, "0", "100");

        assertThat(c.habilitada()).isFalse();
        assertThat(c.alcanza()).isFalse();
        assertThat(c.disponible()).isNull();
        assertThat(c.exceso()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("límite 0: está habilitada pero sin crédito, toda venta mayor a cero excede")
    void limiteCeroTodaVentaExcede() {
        Cupo c = cupo("0", "0", "0.01");

        assertThat(c.habilitada()).isTrue();
        assertThat(c.excede()).isTrue();
        assertThat(c.alcanza()).isFalse();
        assertThat(c.exceso()).isEqualByComparingTo("0.01");
        assertThat(c.disponible()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("saldo + importe igual al límite NO excede; un centavo más sí (límite 5000, saldo 4000)")
    void bordeDelLimite() {
        Cupo justo = cupo("5000", "4000", "1000");
        Cupo unCentavoMas = cupo("5000", "4000", "1000.01");
        Cupo mil1 = cupo("5000", "4000", "1001");

        assertThat(justo.excede()).isFalse();
        assertThat(justo.alcanza()).isTrue();
        assertThat(justo.exceso()).isEqualByComparingTo("0");
        assertThat(unCentavoMas.excede()).isTrue();
        assertThat(unCentavoMas.exceso()).isEqualByComparingTo("0.01");
        assertThat(mil1.excede()).isTrue();
        assertThat(mil1.exceso()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("el exceso es saldo + importe - límite (4000 + 2500 - 5000 = 1500) y disponible es límite - saldo")
    void excesoYDisponible() {
        Cupo c = cupo("5000", "4000", "2500");

        assertThat(c.exceso()).isEqualByComparingTo("1500");
        assertThat(c.disponible()).isEqualByComparingTo("1000");
        assertThat(c.limite()).isEqualByComparingTo("5000");
        assertThat(c.saldo()).isEqualByComparingTo("4000");
        assertThat(c.importe()).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("con saldo a favor (negativo) el disponible supera al límite")
    void saldoAFavor() {
        Cupo c = cupo("1000", "-200", "1100");

        assertThat(c.disponible()).isEqualByComparingTo("1200");
        assertThat(c.excede()).isFalse();
        assertThat(c.exceso()).isEqualByComparingTo("0");
        assertThat(cupo("1000", "-200", "1200.01").excede()).isTrue();
    }

    @Test
    @DisplayName("si el límite bajó por debajo del saldo, el disponible es negativo y toda venta excede")
    void limiteBajoElSaldo() {
        Cupo c = cupo("1000", "1500", "10");

        assertThat(c.disponible()).isEqualByComparingTo("-500");
        assertThat(c.excede()).isTrue();
        assertThat(c.exceso()).isEqualByComparingTo("510");
    }

    @Test
    @DisplayName("compara por valor, no por escala: 5000 vs 5000.00 y 4000.0 + 1000")
    void escalaDelBigDecimal() {
        assertThat(cupo("5000.00", "4000", "1000").excede()).isFalse();
        assertThat(cupo("5000", "4000.00", "1000.0").excede()).isFalse();
        assertThat(cupo("5000.0", "4000.0", "1000.01").excede()).isTrue();
    }

    @Test
    @DisplayName("importe cero (consulta sin monto): no excede mientras el saldo no pase el límite")
    void importeCero() {
        assertThat(cupo("1000", "400", "0").alcanza()).isTrue();
        assertThat(cupo("1000", "1000.01", "0").excede()).isTrue();
    }

    @Test
    @DisplayName("un importe negativo o datos faltantes no son un cupo válido")
    void datosInvalidos() {
        assertThatThrownBy(() -> cupo("1000", "0", "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cupo.de(bd("1000"), null, bd("1"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cupo.de(bd("1000"), bd("0"), null)).isInstanceOf(NullPointerException.class);
    }
}
