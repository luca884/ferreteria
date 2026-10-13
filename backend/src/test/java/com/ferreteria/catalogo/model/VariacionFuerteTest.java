package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.VariacionFuerte.Sujeto;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class VariacionFuerteTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("el umbral es 50")
    void umbral() {
        assertThat(VariacionFuerte.UMBRAL_PORCENTAJE).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("costo que sube 60 %: advierte con el texto exacto")
    void costoSube() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("100.00"), bd("160.00"))).contains(
            "El costo sube un 60 % (de 100,00 a 160,00). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("costo que baja 60 %: advierte con el texto exacto")
    void costoBaja() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("100.00"), bd("40.00"))).contains(
            "El costo baja un 60 % (de 100,00 a 40,00). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("precio que sube 206,25 %: usa 'El precio' y formato argentino")
    void precioSube() {
        assertThat(VariacionFuerte.mensaje(Sujeto.PRECIO, bd("160.00"), bd("490.00"))).contains(
            "El precio sube un 206,25 % (de 160,00 a 490,00). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("precio que baja: usa 'El precio ... baja'")
    void precioBaja() {
        assertThat(VariacionFuerte.mensaje(Sujeto.PRECIO, bd("490.00"), bd("130.00"))).contains(
            "El precio baja un 73,47 % (de 490,00 a 130,00). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("miles con punto: 1.234,56")
    void milesConPunto() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("1000.00"), bd("2234.56"))).contains(
            "El costo sube un 123,46 % (de 1.000,00 a 2.234,56). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("exactamente 50 % (sube o baja) no advierte")
    void exactamente50NoAdvierte() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("100.00"), bd("150.00"))).isEmpty();
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("100.00"), bd("50.00"))).isEmpty();
    }

    @Test
    @DisplayName("50,01 % advierte, en ambos sentidos")
    void cincuentaConUnCentesimoAdvierte() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("10000.00"), bd("15001.00")))
            .contains("El costo sube un 50,01 % (de 10.000,00 a 15.001,00). Revisá que sea correcto.");
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("10000.00"), bd("4999.00")))
            .contains("El costo baja un 50,01 % (de 10.000,00 a 4.999,00). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("valor anterior 0 o negativo: no se puede medir, no advierte")
    void anteriorNoPositivoNoAdvierte() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("0.00"), bd("100.00"))).isEmpty();
        assertThat(VariacionFuerte.mensaje(Sujeto.PRECIO, bd("-1.00"), bd("100.00"))).isEmpty();
    }

    @Test
    @DisplayName("valores iguales: no advierte")
    void igualesNoAdvierte() {
        assertThat(VariacionFuerte.mensaje(Sujeto.COSTO, bd("100.00"), bd("100.00"))).isEmpty();
    }
}
