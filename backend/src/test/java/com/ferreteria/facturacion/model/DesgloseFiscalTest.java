package com.ferreteria.facturacion.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S7: el IVA se desglosa por alícuota a nivel comprobante. Por alícuota se suma el total con IVA (T) de sus renglones,
 * BaseImp = redondeo(T / (1 + a), 2) e Importe = T - BaseImp. Los importes salen con escala 2 ({@code isEqualTo}
 * compara también la escala).
 */
class DesgloseFiscalTest {

    private static BigDecimal pesos(String importe) {
        return new BigDecimal(importe);
    }

    private static RenglonFiscal renglon(Alicuota alicuota, String total) {
        return new RenglonFiscal(alicuota, pesos(total));
    }

    private static void sinDiscriminarOtrosImportes(DesgloseFiscal desglose) {
        assertThat(desglose.impTotConc()).as("ImpTotConc").isEqualTo(pesos("0.00"));
        assertThat(desglose.impOpEx()).as("ImpOpEx").isEqualTo(pesos("0.00"));
        assertThat(desglose.impTrib()).as("ImpTrib").isEqualTo(pesos("0.00"));
    }

    @Test
    @DisplayName("dos renglones de $ 100 al 21 % dan BaseImp 165,29 e Importe 34,71")
    void dosRenglonesDeCienAlVeintiuno() {
        DesgloseFiscal desglose = DesgloseFiscal.de(Letra.B,
                List.of(renglon(Alicuota.IVA_21, "100.00"), renglon(Alicuota.IVA_21, "100.00")));

        assertThat(desglose.alicuotas()).containsExactly(
                new AlicuotaDesglosada(Alicuota.IVA_21, pesos("165.29"), pesos("34.71")));
        assertThat(desglose.impTotal()).isEqualTo(pesos("200.00"));
        assertThat(desglose.impNeto()).isEqualTo(pesos("165.29"));
        assertThat(desglose.impIva()).isEqualTo(pesos("34.71"));
        sinDiscriminarOtrosImportes(desglose);
    }

    @Test
    @DisplayName("tres renglones de $ 100 al 21 % dan BaseImp 247,93 e Importe 52,07 (el total sin decimales sale con escala 2)")
    void tresRenglonesDeCienAlVeintiuno() {
        DesgloseFiscal desglose = DesgloseFiscal.de(Letra.B, List.of(renglon(Alicuota.IVA_21, "100"),
                renglon(Alicuota.IVA_21, "100"), renglon(Alicuota.IVA_21, "100")));

        assertThat(desglose.alicuotas()).containsExactly(
                new AlicuotaDesglosada(Alicuota.IVA_21, pesos("247.93"), pesos("52.07")));
        assertThat(desglose.impTotal()).isEqualTo(pesos("300.00"));
        assertThat(desglose.impNeto()).isEqualTo(pesos("247.93"));
        assertThat(desglose.impIva()).isEqualTo(pesos("52.07"));
        sinDiscriminarOtrosImportes(desglose);
    }

    @Test
    @DisplayName("sumar el desglose de cada renglón (82,64 + 17,36 cada uno) da 165,28 y 34,72: otra cosa que desglosar el comprobante")
    void desglosarPorRenglonDaOtraCosa() {
        AlicuotaDesglosada porRenglon = DesgloseFiscal.de(Letra.B, List.of(renglon(Alicuota.IVA_21, "100.00")))
                .alicuotas().get(0);
        assertThat(porRenglon.baseImp()).isEqualTo(pesos("82.64"));
        assertThat(porRenglon.importe()).isEqualTo(pesos("17.36"));

        BigDecimal netoSumado = porRenglon.baseImp().add(porRenglon.baseImp());
        BigDecimal ivaSumado = porRenglon.importe().add(porRenglon.importe());
        assertThat(netoSumado).isEqualTo(pesos("165.28"));
        assertThat(ivaSumado).isEqualTo(pesos("34.72"));

        DesgloseFiscal delComprobante = DesgloseFiscal.de(Letra.B,
                List.of(renglon(Alicuota.IVA_21, "100.00"), renglon(Alicuota.IVA_21, "100.00")));
        assertThat(delComprobante.impNeto()).isNotEqualTo(netoSumado);
        assertThat(delComprobante.impIva()).isNotEqualTo(ivaSumado);
    }

    @Test
    @DisplayName("varias alícuotas mezcladas se agrupan por alícuota, ordenadas por Id de WSFEv1, y los totales cierran")
    void variasAlicuotasMezcladas() {
        DesgloseFiscal desglose = DesgloseFiscal.de(Letra.A, List.of(
                renglon(Alicuota.IVA_21, "100.00"),
                renglon(Alicuota.IVA_10_5, "200.00"),
                renglon(Alicuota.IVA_0, "30.00"),
                renglon(Alicuota.IVA_21, "50.50"),
                renglon(Alicuota.IVA_27, "10.00"),
                renglon(Alicuota.IVA_5, "21.00"),
                renglon(Alicuota.IVA_2_5, "41.00")));

        assertThat(desglose.alicuotas()).containsExactly(
                new AlicuotaDesglosada(Alicuota.IVA_0, pesos("30.00"), pesos("0.00")),
                new AlicuotaDesglosada(Alicuota.IVA_10_5, pesos("181.00"), pesos("19.00")),
                new AlicuotaDesglosada(Alicuota.IVA_21, pesos("124.38"), pesos("26.12")),
                new AlicuotaDesglosada(Alicuota.IVA_27, pesos("7.87"), pesos("2.13")),
                new AlicuotaDesglosada(Alicuota.IVA_5, pesos("20.00"), pesos("1.00")),
                new AlicuotaDesglosada(Alicuota.IVA_2_5, pesos("40.00"), pesos("1.00")));
        assertThat(desglose.impTotal()).isEqualTo(pesos("452.50"));
        assertThat(desglose.impNeto()).isEqualTo(pesos("403.25"));
        assertThat(desglose.impIva()).isEqualTo(pesos("49.25"));
        sinDiscriminarOtrosImportes(desglose);
    }

    @Test
    @DisplayName("un comprobante de total 0 da todos los importes en 0 y no informa alícuotas")
    void totalCero() {
        DesgloseFiscal desglose = DesgloseFiscal.de(Letra.B, List.of(renglon(Alicuota.IVA_21, "0.00")));

        assertThat(desglose.alicuotas()).isEmpty();
        assertThat(desglose.impTotal()).isEqualTo(pesos("0.00"));
        assertThat(desglose.impNeto()).isEqualTo(pesos("0.00"));
        assertThat(desglose.impIva()).isEqualTo(pesos("0.00"));
        sinDiscriminarOtrosImportes(desglose);
    }

    @Test
    @DisplayName("en la C el neto es el total, el IVA es 0 y no hay alícuotas")
    void laCNoDiscriminaIva() {
        DesgloseFiscal desglose = DesgloseFiscal.de(Letra.C,
                List.of(renglon(Alicuota.IVA_21, "100.00"), renglon(Alicuota.IVA_10_5, "50.00")));

        assertThat(desglose.alicuotas()).isEmpty();
        assertThat(desglose.impTotal()).isEqualTo(pesos("150.00"));
        assertThat(desglose.impNeto()).isEqualTo(pesos("150.00"));
        assertThat(desglose.impIva()).isEqualTo(pesos("0.00"));
        sinDiscriminarOtrosImportes(desglose);
    }

    @Test
    @DisplayName("un renglón con total negativo o con más de dos decimales se rechaza")
    void renglonInvalido() {
        assertThatThrownBy(() -> new RenglonFiscal(Alicuota.IVA_21, pesos("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El total de un renglón no puede ser negativo.");
        assertThatThrownBy(() -> new RenglonFiscal(Alicuota.IVA_21, pesos("10.005")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El total de un renglón va en pesos con dos decimales como mucho.");
    }
}
