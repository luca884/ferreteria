package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Dominio puro: sin Spring ni base de datos. */
class RenglonVentaTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("C1: 3 u a 1.210,00 con IVA 21 % da total 3.630,00, neto 3.000,00 e IVA 630,00")
    void casoC1() {
        RenglonVenta r = RenglonVenta.de(bd("1210.00"), bd("3"), bd("21.00"));

        assertThat(r.total()).isEqualByComparingTo("3630.00");
        assertThat(r.neto()).isEqualByComparingTo("3000.00");
        assertThat(r.iva()).isEqualByComparingTo("630.00");
    }

    @Test
    @DisplayName("C3: 2,5 m a 333,33 da total 833,33 (833,325 HALF_UP), neto 688,70 e IVA 144,63")
    void casoC3() {
        RenglonVenta r = RenglonVenta.de(bd("333.33"), bd("2.5"), bd("21.00"));

        assertThat(r.total()).isEqualByComparingTo("833.33");
        assertThat(r.neto()).isEqualByComparingTo("688.70");
        assertThat(r.iva()).isEqualByComparingTo("144.63");
    }

    @Test
    @DisplayName("0,05 x 0,1 = 0,005 redondea a 0,01 (HALF_UP; HALF_EVEN daria 0,00)")
    void halfUpEnElMedio() {
        RenglonVenta r = RenglonVenta.de(bd("0.05"), bd("0.1"), bd("21.00"));

        assertThat(r.total()).isEqualByComparingTo("0.01");
        assertThat(r.neto()).isEqualByComparingTo("0.01");
        assertThat(r.iva()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("el renglon conserva el precio y la cantidad con que se armo")
    void conservaPrecioYCantidad() {
        RenglonVenta r = RenglonVenta.de(bd("170.00"), bd("3"), bd("21.00"));

        assertThat(r.precioUnitario()).isEqualByComparingTo("170.00");
        assertThat(r.cantidad()).isEqualByComparingTo("3");
        assertThat(r.total()).isEqualByComparingTo("510.00");
        assertThat(r.neto()).isEqualByComparingTo("421.49");
        assertThat(r.iva()).isEqualByComparingTo("88.51");
    }

    @ParameterizedTest(name = "IVA {0} %: 100,00 da neto {1} e IVA {2}")
    @DisplayName("cada alicuota: neto e IVA salen de total x 100 / (100 + %)")
    @CsvSource({
            "0.00,   100.00, 0.00",
            "2.50,   97.56,  2.44",
            "5.00,   95.24,  4.76",
            "10.50,  90.50,  9.50",
            "21.00,  82.64,  17.36",
            "27.00,  78.74,  21.26"
    })
    void cadaAlicuota(String porcentaje, String neto, String iva) {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd(porcentaje));

        assertThat(r.neto()).isEqualByComparingTo(neto);
        assertThat(r.iva()).isEqualByComparingTo(iva);
    }

    @Test
    @DisplayName("neto + IVA = total en una grilla de precios, cantidades y alicuotas")
    void netoMasIvaEsTotal() {
        String[] precios = {"0.01", "1.99", "33.33", "100.00", "1210.00", "99999.99"};
        String[] cantidades = {"0.001", "0.5", "1", "2.5", "3", "17.333"};
        String[] alicuotas = {"0", "2.5", "5", "10.5", "21", "27"};
        int casos = 0;

        for (String precio : precios) {
            for (String cantidad : cantidades) {
                for (String alicuota : alicuotas) {
                    RenglonVenta r = RenglonVenta.de(bd(precio), bd(cantidad), bd(alicuota));
                    assertThat(r.neto().add(r.iva())).isEqualByComparingTo(r.total());
                    casos++;
                }
            }
        }
        assertThat(casos).isEqualTo(216);
    }

    @Test
    @DisplayName("D1: 10 % de descuento del renglon sobre 100,00 da bruto 100,00, total 90,00, neto 74,38 e IVA 15,62")
    void descuentoDelRenglon() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"), bd("10.00"), BigDecimal.ZERO);

        assertThat(r.bruto()).isEqualByComparingTo("100.00");
        assertThat(r.descuentoPorcentaje()).isEqualByComparingTo("10.00");
        assertThat(r.total()).isEqualByComparingTo("90.00");
        assertThat(r.neto()).isEqualByComparingTo("74.38");
        assertThat(r.iva()).isEqualByComparingTo("15.62");
    }

    @Test
    @DisplayName("D2: el descuento global se aplica a cada renglon y da lo mismo que uno propio de igual porcentaje")
    void descuentoGlobalDelRenglon() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"), BigDecimal.ZERO, bd("10.00"));

        assertThat(r.descuentoGlobalPorcentaje()).isEqualByComparingTo("10.00");
        assertThat(r.total()).isEqualByComparingTo("90.00");
        assertThat(r.neto().add(r.iva())).isEqualByComparingTo(r.total());
    }

    @Test
    @DisplayName("D3: primero el descuento del renglon y despues el global, cada uno redondeado a centavos: 100 -> 90 -> 81")
    void losDosDescuentosEnCadena() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"), bd("10.00"), bd("10.00"));

        assertThat(r.total()).isEqualByComparingTo("81.00");
    }

    @Test
    @DisplayName("D4: 0,99 con 50 % es 0,495 y redondea a 0,50 (HALF_UP)")
    void descuentoRedondeaHalfUp() {
        RenglonVenta r = RenglonVenta.de(bd("0.99"), BigDecimal.ONE, bd("21.00"), bd("50.00"), BigDecimal.ZERO);

        assertThat(r.total()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("sin descuentos el renglon es el de siempre: bruto = total y porcentajes en cero")
    void sinDescuentos() {
        RenglonVenta r = RenglonVenta.de(bd("170.00"), bd("3"), bd("21.00"));

        assertThat(r.bruto()).isEqualByComparingTo("510.00");
        assertThat(r.total()).isEqualByComparingTo("510.00");
        assertThat(r.descuentoPorcentaje()).isEqualByComparingTo("0");
        assertThat(r.descuentoGlobalPorcentaje()).isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "descuento {0} % es invalido")
    @DisplayName("el descuento va de 0 a 99,99 %: negativo, 100 o mas no se calcula")
    @CsvSource({"-0.01", "100.00", "150.00"})
    void descuentoFueraDeRango(String porcentaje) {
        assertThatThrownBy(() -> RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"), bd(porcentaje), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"), BigDecimal.ZERO, bd(porcentaje)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("neto + IVA = total tambien con descuentos, en una grilla de porcentajes")
    void netoMasIvaEsTotalConDescuentos() {
        String[] porcentajes = {"0", "0.01", "5", "10", "33.33", "50", "99.99"};

        for (String renglon : porcentajes) {
            for (String global : porcentajes) {
                RenglonVenta r = RenglonVenta.de(bd("1999.99"), bd("2.5"), bd("21"), bd(renglon), bd(global));
                assertThat(r.neto().add(r.iva())).as("%s y %s", renglon, global).isEqualByComparingTo(r.total());
            }
        }
    }

    @Test
    @DisplayName("el total, el neto y el IVA salen a escala 2")
    void escalaDos() {
        RenglonVenta r = RenglonVenta.de(bd("33.33"), bd("0.015"), bd("21.00"));

        assertThat(r.total()).isEqualTo(bd("0.50"));
        assertThat(r.neto().scale()).isEqualTo(2);
        assertThat(r.iva().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("sin recargo, el recargo del renglon es cero")
    void sinRecargo() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"));

        assertThat(r.recargo()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("con recargo, el total lo suma y el neto y el IVA salen del total nuevo: 100 + 10 da 110, neto 90,91 e IVA 19,09")
    void conRecargo() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00")).conRecargo(bd("10.00"));

        assertThat(r.recargo()).isEqualByComparingTo("10.00");
        assertThat(r.total()).isEqualByComparingTo("110.00");
        assertThat(r.neto()).isEqualByComparingTo("90.91");
        assertThat(r.iva()).isEqualByComparingTo("19.09");
        assertThat(r.bruto()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("el recargo usa la alicuota del renglon: a 10,5 % el neto de 88,00 es 79,64")
    void conRecargoYAlicuotaPropia() {
        RenglonVenta r = RenglonVenta.de(bd("80.00"), BigDecimal.ONE, bd("10.50")).conRecargo(bd("8.00"));

        assertThat(r.total()).isEqualByComparingTo("88.00");
        assertThat(r.neto()).isEqualByComparingTo("79.64");
        assertThat(r.iva()).isEqualByComparingTo("8.36");
    }

    @Test
    @DisplayName("el total sin recargo es el total menos el recargo")
    void totalSinRecargo() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00")).conRecargo(bd("10.00"));

        assertThat(r.totalSinRecargo()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("un recargo negativo se rechaza")
    void recargoNegativo() {
        RenglonVenta r = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, bd("21.00"));

        assertThatThrownBy(() -> r.conRecargo(bd("-0.01"))).isInstanceOf(IllegalArgumentException.class);
    }
}
