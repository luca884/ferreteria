package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Aviso de margen negativo de un articulo con precio manual (CU-14 punto 2): costo en pesos contra precio sin IVA. */
class MargenNegativoTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("costo 110 con precio 121 (100 sin IVA): avisa con los dos importes")
    void costoMayorQueElPrecioSinIva() {
        assertThat(MargenNegativo.mensaje(bd("110.00"), BigDecimal.ONE, AlicuotaIva.IVA_21, bd("121.00")))
            .contains("Margen negativo: el costo ($\u00a0110,00) supera el precio sin IVA ($\u00a0100,00). "
                + "Revisá el precio manual.");
    }

    @Test
    @DisplayName("costo igual al precio sin IVA: margen 0, no avisa")
    void margenCeroNoAvisa() {
        assertThat(MargenNegativo.mensaje(bd("100.00"), BigDecimal.ONE, AlicuotaIva.IVA_21, bd("121.00"))).isEmpty();
    }

    @Test
    @DisplayName("un centavo por encima del precio sin IVA ya avisa")
    void unCentavoAvisa() {
        assertThat(MargenNegativo.mensaje(bd("100.01"), BigDecimal.ONE, AlicuotaIva.IVA_21, bd("121.00"))).isPresent();
    }

    @Test
    @DisplayName("costo menor: no avisa")
    void costoMenorNoAvisa() {
        assertThat(MargenNegativo.mensaje(bd("50.00"), BigDecimal.ONE, AlicuotaIva.IVA_21, bd("121.00"))).isEmpty();
    }

    @Test
    @DisplayName("en dolares compara el costo en pesos: US$ 1 a 1000 contra 1200 (991,74 sin IVA) avisa")
    void enDolaresUsaLaCotizacion() {
        assertThat(MargenNegativo.mensaje(bd("1.00"), bd("1000"), AlicuotaIva.IVA_21, bd("1210.00"))).isEmpty();
        assertThat(MargenNegativo.mensaje(bd("1.00"), bd("1000"), AlicuotaIva.IVA_21, bd("1200.00")))
            .contains("Margen negativo: el costo ($\u00a01.000,00) supera el precio sin IVA ($\u00a0991,74). "
                + "Revisá el precio manual.");
    }

    @Test
    @DisplayName("IVA 0 %: el precio sin IVA es el precio final")
    void ivaCero() {
        assertThat(MargenNegativo.mensaje(bd("100.01"), BigDecimal.ONE, AlicuotaIva.IVA_0, bd("100.00"))).isPresent();
        assertThat(MargenNegativo.mensaje(bd("100.00"), BigDecimal.ONE, AlicuotaIva.IVA_0, bd("100.00"))).isEmpty();
    }
}
