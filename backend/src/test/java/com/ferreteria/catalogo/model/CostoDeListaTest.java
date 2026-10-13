package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Costo de lista: IVA y bonificaciones en cascada con un unico redondeo final. */
class CostoDeListaTest {

    private static Bonificaciones bonif(String b1, String b2, String b3) {
        return new Bonificaciones(valor(b1), valor(b2), valor(b3));
    }

    private static BigDecimal valor(String texto) {
        return texto == null || texto.isBlank() ? null : new BigDecimal(texto);
    }

    @ParameterizedTest(name = "lista {0} {1} incluyeIva={2} bonif {3}/{4}/{5} -> {6}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        // precio | alicuota | incluyeIva | b1 | b2 | b3 | costo
        "1000    | IVA_21   | false | 40 | 10 | -  | 540.00",
        "1210    | IVA_21   | true  | 40 | -  | -  | 600.00",
        "1210    | IVA_21   | true  | 40 | 10 | -  | 540.00",
        "121     | IVA_21   | true  | -  | -  | -  | 100.00",
        "121     | IVA_10_5 | true  | -  | -  | -  | 109.50",
        "1105    | IVA_10_5 | true  | 10 | -  | -  | 900.00",
        "100     | IVA_21   | true  | -  | -  | -  | 82.64",
        "100     | IVA_21   | false | 10 | 10 | 10 | 72.90",
        "10.05   | IVA_21   | false | 10 | 10 | -  | 8.14",
        "12.3456 | IVA_21   | false | -  | -  | -  | 12.35",
        "12.344  | IVA_21   | false | -  | -  | -  | 12.34",
        "0.004   | IVA_21   | false | -  | -  | -  | 0.00",
        "100     | IVA_21   | false | 100 | - | -  | 0.00",
        "1000    | IVA_21   | false | 10 | -  | -  | 900.00",
        "1000    | IVA_21   | false | -  | -  | -  | 1000.00",
        "110.5   | IVA_10_5 | true  | -  | -  | -  | 100.00",
        "1.005   | IVA_21   | false | -  | -  | -  | 1.01",
        "1000    | IVA_0    | true  | -  | -  | -  | 1000.00",
    })
    @DisplayName("tabla de costos")
    void tabla(String precio, AlicuotaIva alicuota, boolean incluyeIva,
               String b1, String b2, String b3, String esperado) {
        BigDecimal costo = CostoDeLista.costo(new BigDecimal(precio.trim()), alicuota, incluyeIva, bonif(b1, b2, b3));

        assertThat(costo).isEqualTo(new BigDecimal(esperado.trim()));
        assertThat(costo.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("redondea una sola vez al final: 10,05 con 10 + 10 es 8.14 y nunca 8.15")
    void redondeoUnico() {
        BigDecimal costo = CostoDeLista.costo(new BigDecimal("10.05"), AlicuotaIva.IVA_21, false, bonif("10", "10", null));

        assertThat(costo).isEqualTo(new BigDecimal("8.14"));
        assertThat(costo).isNotEqualTo(new BigDecimal("8.15"));
    }

    @Test
    @DisplayName("la alicuota solo importa cuando la lista incluye IVA")
    void alicuotaIgnoradaSinIva() {
        BigDecimal conIva21 = CostoDeLista.costo(new BigDecimal("121"), AlicuotaIva.IVA_21, false, Bonificaciones.NINGUNA);
        BigDecimal conIva27 = CostoDeLista.costo(new BigDecimal("121"), AlicuotaIva.IVA_27, false, Bonificaciones.NINGUNA);

        assertThat(conIva21).isEqualTo(new BigDecimal("121.00"));
        assertThat(conIva27).isEqualTo(new BigDecimal("121.00"));
    }

    @Test
    @DisplayName("bonificaciones null equivale a NINGUNA")
    void bonificacionesNull() {
        assertThat(CostoDeLista.costo(new BigDecimal("50"), AlicuotaIva.IVA_21, false, null))
            .isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("un precio null es un error de programacion")
    void precioNull() {
        assertThatThrownBy(() -> CostoDeLista.costo(null, AlicuotaIva.IVA_21, false, Bonificaciones.NINGUNA))
            .isInstanceOf(NullPointerException.class);
    }
}
