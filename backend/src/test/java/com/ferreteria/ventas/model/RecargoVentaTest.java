package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RC-004: el recargo de la forma de pago y su reparto entre los renglones. Dominio puro. */
class RecargoVentaTest {

    private static final BigDecimal IVA_21 = new BigDecimal("21.00");
    private static final BigDecimal[] ALICUOTAS = {
            new BigDecimal("0.00"), new BigDecimal("10.50"), new BigDecimal("21.00"), new BigDecimal("27.00")};

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static RenglonVenta renglon(String precio) {
        return RenglonVenta.de(bd(precio), BigDecimal.ONE, IVA_21);
    }

    @ParameterizedTest(name = "{0} al {1} % es {2}")
    @CsvSource({
            "6000.00, 10.00, 600.00",
            "33.33,   10.00, 3.33",
            "0.05,    10.00, 0.01",
            "0.04,    10.00, 0.00",
            "100.00,  0.00,  0.00",
            "1000.00, 200.00, 2000.00",
            "1234.57, 12.35, 152.47"})
    @DisplayName("el recargo de un pago es monto x % / 100, a centavos HALF_UP")
    void recargoDeUnPago(String monto, String porcentaje, String esperado) {
        assertThat(RecargoVenta.deCobro(bd(monto), bd(porcentaje))).isEqualByComparingTo(esperado);
    }

    @Test
    @DisplayName("con un solo renglon, el recargo entero es suyo")
    void unSoloRenglon() {
        List<RenglonVenta> repartidos = RecargoVenta.repartir(List.of(renglon("100.00")), bd("10.00"));

        assertThat(repartidos).hasSize(1);
        assertThat(repartidos.get(0).recargo()).isEqualByComparingTo("10.00");
        assertThat(repartidos.get(0).total()).isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("se reparte en proporcion al total de cada renglon")
    void enProporcion() {
        List<RenglonVenta> repartidos = RecargoVenta.repartir(List.of(renglon("300.00"), renglon("100.00")),
                bd("40.00"));

        assertThat(repartidos.get(0).recargo()).isEqualByComparingTo("30.00");
        assertThat(repartidos.get(1).recargo()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("los centavos que sobran van a los restos mayores, y a igual resto, al primero: nunca queda un recargo negativo")
    void centavosSobrantes() {
        RenglonVenta cinco = RenglonVenta.de(bd("0.05"), BigDecimal.ONE, IVA_21);

        List<RenglonVenta> repartidos = RecargoVenta.repartir(List.of(cinco, cinco, cinco), bd("0.02"));

        assertThat(repartidos.get(0).recargo()).isEqualByComparingTo("0.01");
        assertThat(repartidos.get(1).recargo()).isEqualByComparingTo("0.01");
        assertThat(repartidos.get(2).recargo()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("el recargo va despues de los descuentos: se reparte por el total ya descontado")
    void despuesDeLosDescuentos() {
        RenglonVenta conDescuento = RenglonVenta.de(bd("200.00"), BigDecimal.ONE, IVA_21, bd("50.00"), BigDecimal.ZERO);
        RenglonVenta sinDescuento = renglon("100.00");

        List<RenglonVenta> repartidos = RecargoVenta.repartir(List.of(conDescuento, sinDescuento), bd("20.00"));

        assertThat(repartidos.get(0).recargo()).isEqualByComparingTo("10.00");
        assertThat(repartidos.get(1).recargo()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("recargo cero devuelve los renglones tal cual")
    void recargoCero() {
        List<RenglonVenta> renglones = List.of(renglon("100.00"), renglon("50.00"));

        assertThat(RecargoVenta.repartir(renglones, BigDecimal.ZERO)).isEqualTo(renglones);
    }

    @ParameterizedTest(name = "{0} al {1} % es {2}")
    @CsvSource({
            "1000.00, -10.00, -100.00",
            "0.05,    -10.00, -0.01",
            "0.04,    -10.00, 0.00",
            "1234.57, -12.35, -152.47",
            "100.00,  -99.99, -99.99"})
    @DisplayName("descuento por forma de pago: el % negativo da un recargo negativo, HALF_UP lejos del cero, espejo del positivo")
    void recargoNegativoDeUnPago(String monto, String porcentaje, String esperado) {
        assertThat(RecargoVenta.deCobro(bd(monto), bd(porcentaje))).isEqualByComparingTo(esperado);
    }

    @Test
    @DisplayName("un recargo negativo se reparte como el positivo, en espejo: los centavos van a los restos mayores")
    void recargoNegativoEnEspejo() {
        RenglonVenta cinco = RenglonVenta.de(bd("0.05"), BigDecimal.ONE, IVA_21);

        List<RenglonVenta> unico = RecargoVenta.repartir(List.of(renglon("1000.00")), bd("-100.00"));
        List<RenglonVenta> proporcion = RecargoVenta.repartir(List.of(renglon("300.00"), renglon("100.00")),
                bd("-40.00"));
        List<RenglonVenta> centavos = RecargoVenta.repartir(List.of(cinco, cinco, cinco), bd("-0.02"));

        assertThat(unico.get(0).total()).isEqualByComparingTo("900.00");
        assertThat(proporcion.get(0).recargo()).isEqualByComparingTo("-30.00");
        assertThat(proporcion.get(1).recargo()).isEqualByComparingTo("-10.00");
        assertThat(centavos).extracting(RenglonVenta::recargo)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("-0.01"), bd("-0.01"), bd("0.00"));
    }

    @Test
    @DisplayName("un recargo negativo mayor que el total se rechaza: la venta no puede quedar negativa")
    void recargoNegativoMayorQueElTotal() {
        assertThatThrownBy(() -> RecargoVenta.repartir(List.of(renglon("100.00")), bd("-100.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2.000 tickets al azar con recargo negativo (semilla fija): suma exacta, ningun renglon negativo ni positivo de mas")
    void propiedadDeCoherenciaNegativa() {
        Random azar = new Random(23L);

        for (int ticket = 0; ticket < 2000; ticket++) {
            List<RenglonVenta> renglones = new ArrayList<>();
            int cantidad = 1 + azar.nextInt(10);
            for (int i = 0; i < cantidad; i++) {
                BigDecimal precio = BigDecimal.valueOf(1 + azar.nextInt(500_000), 2);
                BigDecimal cant = BigDecimal.valueOf(1 + azar.nextInt(20_000), 3);
                renglones.add(RenglonVenta.de(precio, cant, ALICUOTAS[azar.nextInt(ALICUOTAS.length)]));
            }
            BigDecimal totalSinRecargo = renglones.stream().map(RenglonVenta::total).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            if (totalSinRecargo.signum() == 0) {
                continue;
            }
            BigDecimal porcentaje = BigDecimal.valueOf(-(1 + azar.nextInt(9_999)), 2);
            BigDecimal recargo = RecargoVenta.deCobro(totalSinRecargo, porcentaje);

            List<RenglonVenta> repartidos = RecargoVenta.repartir(renglones, recargo);

            BigDecimal suma = BigDecimal.ZERO;
            for (int i = 0; i < repartidos.size(); i++) {
                RenglonVenta r = repartidos.get(i);
                assertThat(r.recargo().signum()).as("recargo %d del ticket %d", i, ticket).isLessThanOrEqualTo(0);
                assertThat(r.total().signum()).as("total %d del ticket %d", i, ticket).isGreaterThanOrEqualTo(0);
                assertThat(r.neto().add(r.iva())).as("neto + iva %d del ticket %d", i, ticket)
                        .isEqualByComparingTo(r.total());
                suma = suma.add(r.recargo());
            }
            assertThat(suma).as("suma de recargos del ticket %d", ticket).isEqualByComparingTo(recargo);
        }
    }

    @Test
    @DisplayName("un recargo sobre renglones que suman cero se rechaza: no hay a quien repartirlo")
    void sinTotalSobreElQueRepartir() {
        RenglonVenta cero = RenglonVenta.de(bd("0.00"), BigDecimal.ONE, IVA_21);

        assertThatThrownBy(() -> RecargoVenta.repartir(List.of(cero), bd("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2.000 tickets al azar (semilla fija): lo repartido suma el recargo, ninguno es negativo y neto + IVA = total en cada renglon")
    void propiedadDeCoherencia() {
        Random azar = new Random(11L);

        for (int ticket = 0; ticket < 2000; ticket++) {
            List<RenglonVenta> renglones = new ArrayList<>();
            int cantidad = 1 + azar.nextInt(10);
            for (int i = 0; i < cantidad; i++) {
                BigDecimal precio = BigDecimal.valueOf(1 + azar.nextInt(500_000), 2);
                BigDecimal cant = BigDecimal.valueOf(1 + azar.nextInt(20_000), 3);
                renglones.add(RenglonVenta.de(precio, cant, ALICUOTAS[azar.nextInt(ALICUOTAS.length)]));
            }
            BigDecimal recargo = BigDecimal.valueOf(azar.nextInt(1_000_000), 2);
            BigDecimal totalSinRecargo = renglones.stream().map(RenglonVenta::total).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            if (totalSinRecargo.signum() == 0) {
                continue;
            }

            List<RenglonVenta> repartidos = RecargoVenta.repartir(renglones, recargo);

            BigDecimal suma = BigDecimal.ZERO;
            for (int i = 0; i < repartidos.size(); i++) {
                RenglonVenta r = repartidos.get(i);
                assertThat(r.recargo().signum()).as("recargo %d del ticket %d", i, ticket).isGreaterThanOrEqualTo(0);
                assertThat(r.total()).as("total %d del ticket %d", i, ticket)
                        .isEqualByComparingTo(renglones.get(i).total().add(r.recargo()));
                assertThat(r.neto().add(r.iva())).as("neto + iva %d del ticket %d", i, ticket)
                        .isEqualByComparingTo(r.total());
                suma = suma.add(r.recargo());
            }
            assertThat(suma).as("suma de recargos del ticket %d", ticket).isEqualByComparingTo(recargo);
            assertThat(TotalesVenta.de(repartidos).total()).as("total del ticket %d", ticket)
                    .isEqualByComparingTo(totalSinRecargo.add(recargo));
        }
    }
}
