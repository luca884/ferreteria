package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ajustes sobre el precio final (CU-14): dominio puro. */
class AjustesDePrecioTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static AjustePrecio porcentaje(String valor) {
        return new AjustePrecio(null, TipoAjustePrecio.PORCENTAJE, bd(valor));
    }

    private static AjustePrecio monto(String valor) {
        return new AjustePrecio(null, TipoAjustePrecio.MONTO, bd(valor));
    }

    private static AjustesDePrecio de(AjustePrecio... ajustes) {
        return new AjustesDePrecio(List.of(ajustes));
    }

    @Test
    @DisplayName("sin ajustes el precio calculado queda igual")
    void sinAjustes() {
        assertThat(AjustesDePrecio.NINGUNO.aplicar(bd("163.35"), null)).isEqualTo(bd("163.35"));
    }

    @Test
    @DisplayName("todos sobre el precio calculado: +10 % y −$100 sobre 1000 da 1000, en cualquier orden")
    void todosSobreElPrecioCalculado() {
        assertThat(de(porcentaje("10"), monto("-100")).aplicar(bd("1000.00"), null)).isEqualTo(bd("1000.00"));
        assertThat(de(monto("-100"), porcentaje("10")).aplicar(bd("1000.00"), null)).isEqualTo(bd("1000.00"));
    }

    @Test
    @DisplayName("dos porcentajes se suman, no se encadenan: +10 % y +10 % sobre 1000 da 1200")
    void porcentajesNoEncadenados() {
        assertThat(de(porcentaje("10"), porcentaje("10")).aplicar(bd("1000.00"), null)).isEqualTo(bd("1200.00"));
    }

    @Test
    @DisplayName("redondea hacia arriba al centavo, como el precio calculado")
    void redondeaAlCentavo() {
        assertThat(de(porcentaje("7")).aplicar(bd("999.99"), null)).isEqualTo(bd("1069.99"));
        assertThat(de(porcentaje("-3.33")).aplicar(bd("10.00"), BigDecimal.ZERO)).isEqualTo(bd("9.67"));
    }

    @Test
    @DisplayName("con múltiplo redondea hacia arriba al múltiplo")
    void redondeaAlMultiplo() {
        assertThat(de(porcentaje("3")).aplicar(bd("1000.00"), bd("10"))).isEqualTo(bd("1030.00"));
        assertThat(de(monto("1")).aplicar(bd("1000.00"), bd("10"))).isEqualTo(bd("1010.00"));
    }

    @Test
    @DisplayName("un monto en pesos se suma tal cual")
    void montoTalCual() {
        assertThat(de(monto("250.50")).aplicar(bd("1000.00"), null)).isEqualTo(bd("1250.50"));
    }

    @Test
    @DisplayName("el porcentaje va de −99,99 a 1000")
    void rangoDelPorcentaje() {
        assertThat(porcentaje("-99.99").valor()).isEqualTo(bd("-99.99"));
        assertThat(porcentaje("1000").valor()).isEqualTo(bd("1000.00"));
        assertThatThrownBy(() -> porcentaje("-100")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> porcentaje("1000.01")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un monto puede ser negativo y no tiene rango propio")
    void montoSinRango() {
        assertThat(monto("-5000").valor()).isEqualTo(bd("-5000.00"));
    }

    @Test
    @DisplayName("el nombre es opcional, se recorta y tiene hasta 40 caracteres")
    void nombre() {
        assertThat(new AjustePrecio("  Flete ", TipoAjustePrecio.MONTO, bd("1")).nombre()).isEqualTo("Flete");
        assertThat(new AjustePrecio("   ", TipoAjustePrecio.MONTO, bd("1")).nombre()).isNull();
        assertThat(new AjustePrecio("a".repeat(40), TipoAjustePrecio.MONTO, bd("1")).nombre()).hasSize(40);
        assertThatThrownBy(() -> new AjustePrecio("a".repeat(41), TipoAjustePrecio.MONTO, bd("1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tipo y valor son obligatorios")
    void obligatorios() {
        assertThatThrownBy(() -> new AjustePrecio(null, null, bd("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AjustePrecio(null, TipoAjustePrecio.MONTO, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("los ajustes dejan el precio en 0 o menos solo si lo llevaron ahí: el costo 0 de siempre no cuenta")
    void dejanElPrecioEnCeroOMenos() {
        assertThat(AjustesDePrecio.dejanElPrecioEnCeroOMenos(bd("100.00"), bd("0.00"))).isTrue();
        assertThat(AjustesDePrecio.dejanElPrecioEnCeroOMenos(bd("0.00"), bd("-5.00"))).isTrue();
        assertThat(AjustesDePrecio.dejanElPrecioEnCeroOMenos(bd("0.00"), bd("0.00"))).isFalse();
        assertThat(AjustesDePrecio.dejanElPrecioEnCeroOMenos(bd("100.00"), bd("0.01"))).isFalse();
    }

    @Test
    @DisplayName("la lista nula es «sin ajustes» y no se puede modificar desde afuera")
    void listaNula() {
        assertThat(new AjustesDePrecio(null).lista()).isEmpty();
        assertThat(new AjustesDePrecio(null).estaVacia()).isTrue();
        assertThatThrownBy(() -> de(monto("1")).lista().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
