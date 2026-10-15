package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class CantidadVendibleTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("una cantidad positiva de un articulo que admite decimales es valida")
    void positivaEsValida() {
        assertThat(CantidadVendible.validar("Cable", bd("2.5"), true)).isEmpty();
        assertThat(CantidadVendible.validar("Cable", bd("1"), true)).isEmpty();
    }

    @Test
    @DisplayName("0 y -1 son invalidas y el mensaje nombra el articulo")
    void ceroYNegativaSonInvalidas() {
        assertThat(CantidadVendible.validar("Tornillo 1/4", bd("0"), false))
                .contains("La cantidad de \"Tornillo 1/4\" tiene que ser mayor que 0.");
        assertThat(CantidadVendible.validar("Cable", bd("-1"), true))
                .contains("La cantidad de \"Cable\" tiene que ser mayor que 0.");
    }

    @Test
    @DisplayName("KILO: 0,001 es valida y 0,0005 (4 decimales) es invalida")
    void tresDecimalesComoMaximo() {
        assertThat(CantidadVendible.validar("Clavos", bd("0.001"), true)).isEmpty();
        assertThat(CantidadVendible.validar("Clavos", bd("0.0005"), true))
                .contains("La cantidad de \"Clavos\" admite hasta 3 decimales.");
    }

    @Test
    @DisplayName("los ceros a la derecha no cuentan como decimales")
    void cerosALaDerechaNoCuentan() {
        assertThat(CantidadVendible.validar("Clavos", bd("1.2500"), true)).isEmpty();
    }

    @Test
    @DisplayName("UNIDAD: \"2.000\" es entera y vale; \"2.5\" no")
    void unidadExigeEntero() {
        assertThat(CantidadVendible.validar("Tornillo 1/4", bd("2.000"), false)).isEmpty();
        assertThat(CantidadVendible.validar("Tornillo 1/4", bd("2.5"), false))
                .contains("\"Tornillo 1/4\" se vende por unidad: la cantidad tiene que ser un número entero.");
    }

    @Test
    @DisplayName("UNIDAD: un entero grande con ceros (100) sigue siendo entero")
    void enteroConCerosEsEntero() {
        assertThat(CantidadVendible.validar("Tornillo 1/4", bd("100"), false)).isEmpty();
        assertThat(CantidadVendible.validar("Tornillo 1/4", bd("1E+2"), false)).isEmpty();
    }

    @Test
    @DisplayName("METRO: 2,5 es valida")
    void metroAdmiteDecimales() {
        assertThat(CantidadVendible.validar("Cable", bd("2.5"), true)).isEmpty();
    }
}
