package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GDS-37: el total del comprobante es exactamente la suma de sus renglones. Dominio puro. */
class TotalesVentaTest {

    private static final BigDecimal IVA_21 = new BigDecimal("21.00");
    private static final BigDecimal[] ALICUOTAS = {
            new BigDecimal("0.00"), new BigDecimal("2.50"), new BigDecimal("5.00"),
            new BigDecimal("10.50"), new BigDecimal("21.00"), new BigDecimal("27.00")};

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("C2: 2 x 100,00 da total 200,00, neto 165,28 e IVA 34,72 (no 165,29 ni 34,71)")
    void casoC2() {
        RenglonVenta cien = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, IVA_21);

        TotalesVenta totales = TotalesVenta.de(List.of(cien, cien));

        assertThat(totales.total()).isEqualByComparingTo("200.00");
        assertThat(totales.neto()).isEqualByComparingTo("165.28");
        assertThat(totales.iva()).isEqualByComparingTo("34.72");
    }

    @Test
    @DisplayName("C1 + C3 en una venta: total 4.463,33")
    void casoC1MasC3() {
        RenglonVenta c1 = RenglonVenta.de(bd("1210.00"), bd("3"), IVA_21);
        RenglonVenta c3 = RenglonVenta.de(bd("333.33"), bd("2.5"), IVA_21);

        TotalesVenta totales = TotalesVenta.de(List.of(c1, c3));

        assertThat(totales.total()).isEqualByComparingTo("4463.33");
        assertThat(totales.neto()).isEqualByComparingTo("3688.70");
        assertThat(totales.iva()).isEqualByComparingTo("774.63");
    }

    @Test
    @DisplayName("el descuento global se prorratea: cada renglon redondea el suyo y el total es la suma, aunque no sea el 10 % del subtotal")
    void descuentoGlobalPorRenglon() {
        RenglonVenta a = RenglonVenta.de(bd("0.05"), BigDecimal.ONE, IVA_21, BigDecimal.ZERO, bd("10.00"));
        RenglonVenta b = RenglonVenta.de(bd("0.05"), BigDecimal.ONE, IVA_21, BigDecimal.ZERO, bd("10.00"));

        TotalesVenta totales = TotalesVenta.de(List.of(a, b));

        assertThat(totales.subtotal()).isEqualByComparingTo("0.10");
        assertThat(totales.total()).isEqualByComparingTo("0.10");
        assertThat(totales.neto().add(totales.iva())).isEqualByComparingTo(totales.total());
    }

    @Test
    @DisplayName("el subtotal es la suma de los brutos y el descuento es subtotal menos total")
    void subtotalYDescuento() {
        RenglonVenta a = RenglonVenta.de(bd("100.00"), BigDecimal.ONE, IVA_21, bd("10.00"), BigDecimal.ZERO);
        RenglonVenta b = RenglonVenta.de(bd("200.00"), BigDecimal.ONE, IVA_21, BigDecimal.ZERO, BigDecimal.ZERO);

        TotalesVenta totales = TotalesVenta.de(List.of(a, b));

        assertThat(totales.subtotal()).isEqualByComparingTo("300.00");
        assertThat(totales.total()).isEqualByComparingTo("290.00");
        assertThat(totales.descuento()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("un solo renglon: los totales son los del renglon")
    void unSoloRenglon() {
        RenglonVenta c1 = RenglonVenta.de(bd("1210.00"), bd("3"), IVA_21);

        TotalesVenta totales = TotalesVenta.de(List.of(c1));

        assertThat(totales.total()).isEqualByComparingTo(c1.total());
        assertThat(totales.neto()).isEqualByComparingTo(c1.neto());
        assertThat(totales.iva()).isEqualByComparingTo(c1.iva());
    }

    @Test
    @DisplayName("1.000 tickets al azar (semilla fija): total = suma de renglones, neto = suma de netos, neto + IVA = total")
    void propiedadDeCoherencia() {
        Random azar = new Random(37L);

        for (int ticket = 0; ticket < 1000; ticket++) {
            int cantidadDeRenglones = 1 + azar.nextInt(12);
            List<RenglonVenta> renglones = new ArrayList<>();
            BigDecimal sumaTotal = BigDecimal.ZERO;
            BigDecimal sumaNeto = BigDecimal.ZERO;
            for (int i = 0; i < cantidadDeRenglones; i++) {
                BigDecimal precio = BigDecimal.valueOf(1 + azar.nextInt(2_000_000), 2);
                BigDecimal cantidad = BigDecimal.valueOf(1 + azar.nextInt(50_000), 3);
                RenglonVenta r = RenglonVenta.de(precio, cantidad, ALICUOTAS[azar.nextInt(ALICUOTAS.length)]);
                renglones.add(r);
                sumaTotal = sumaTotal.add(r.total());
                sumaNeto = sumaNeto.add(r.neto());
            }

            TotalesVenta totales = TotalesVenta.de(renglones);

            assertThat(totales.total()).as("total del ticket %d", ticket).isEqualByComparingTo(sumaTotal);
            assertThat(totales.neto()).as("neto del ticket %d", ticket).isEqualByComparingTo(sumaNeto);
            assertThat(totales.neto().add(totales.iva())).as("neto + iva del ticket %d", ticket)
                    .isEqualByComparingTo(totales.total());
        }
    }
}
