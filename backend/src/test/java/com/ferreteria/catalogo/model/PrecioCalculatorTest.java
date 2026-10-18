package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class PrecioCalculatorTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static void assertPrecio(BigDecimal real, String esperado) {
        // Se compara valor y escala: el precio final siempre sale con 2 decimales.
        assertThat(real).isEqualTo(bd(esperado));
        assertThat(real.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("caso base: 100 + 35 % + IVA 21 % redondeado a multiplo 10 da 170.00")
    void casoBase() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_21, bd("10")), "170.00");
    }

    @Test
    @DisplayName("multiplo 1 sube al peso entero: 163.35 pasa a 164.00")
    void multiploUno() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_21, bd("1")), "164.00");
    }

    @Test
    @DisplayName("multiplo 50: 163.35 sube a 200.00")
    void multiploCincuenta() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_21, bd("50")), "200.00");
    }

    @Test
    @DisplayName("multiplo 0 significa sin multiplo: queda en 163.35")
    void multiploCero() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_21, BigDecimal.ZERO), "163.35");
    }

    @Test
    @DisplayName("multiplo null significa sin multiplo: queda en 163.35")
    void multiploNull() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_21, null), "163.35");
    }

    @Test
    @DisplayName("multiplo negativo significa sin multiplo: queda en 163.35")
    void multiploNegativo() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_21, bd("-10")), "163.35");
    }

    @Test
    @DisplayName("sin multiplo igual redondea hacia arriba al centavo, nunca al mas cercano")
    void sinMultiploRedondeaHaciaArribaAlCentavo() {
        // 100.01 * 1.0001 = 100.020001, que sube a 100.03 (redondear al mas cercano daria 100.02).
        assertPrecio(PrecioCalculator.precioFinal(bd("100.01"), bd("0.01"), AlicuotaIva.IVA_0, null), "100.03");
    }

    @Test
    @DisplayName("H11, sin multiplo: la arandela de costo 2 + 30 % + IVA 21 % (3,146) queda en 3,15, no en 10")
    void arandelaSinMultiplo() {
        assertPrecio(PrecioCalculator.precioFinal(bd("2"), bd("30"), AlicuotaIva.IVA_21, BigDecimal.ZERO), "3.15");
    }

    @Test
    @DisplayName("H11, sin multiplo en dolares: US$0,01 x 1535 + 30 % + IVA 21 % (24,14555) queda en 24,15, no en 30")
    void dolaresSinMultiplo() {
        assertPrecio(PrecioCalculator.precioFinal(
                bd("0.01"), bd("30"), AlicuotaIva.IVA_21, BigDecimal.ZERO, bd("1535")), "24.15");
    }

    @Test
    @DisplayName("sin multiplo, un valor exacto de dos decimales queda igual")
    void sinMultiploValorExacto() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("20"), AlicuotaIva.IVA_0, null), "120.00");
    }

    @Test
    @DisplayName("un valor que ya es multiplo exacto no sube: 30000 + 30 % + 21 % = 47190.00")
    void multiploExacto() {
        assertPrecio(PrecioCalculator.precioFinal(bd("30000"), bd("30"), AlicuotaIva.IVA_21, bd("10")), "47190.00");
    }

    @Test
    @DisplayName("100 + 20 % sin IVA da exactamente 120.00 y no sube a 130")
    void multiploExactoSinIva() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("20"), AlicuotaIva.IVA_0, bd("10")), "120.00");
    }

    @Test
    @DisplayName("siempre hacia arriba: 1020 + 0 % + 21 % = 1234.20 sube a 1240.00, nunca 1230")
    void siempreHaciaArriba() {
        assertPrecio(PrecioCalculator.precioFinal(bd("1020"), BigDecimal.ZERO, AlicuotaIva.IVA_21, bd("10")), "1240.00");
    }

    @Test
    @DisplayName("IVA 10,5 %: 200 + 30 % = 260, con IVA 287.30, sube a 290.00")
    void iva10coma5() {
        assertPrecio(PrecioCalculator.precioFinal(bd("200"), bd("30"), AlicuotaIva.IVA_10_5, bd("10")), "290.00");
    }

    @Test
    @DisplayName("IVA 10,5 % con 135 de neto: 149.175 sube a 150.00")
    void iva10coma5ConNeto135() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("35"), AlicuotaIva.IVA_10_5, bd("10")), "150.00");
    }

    @Test
    @DisplayName("IVA 27 %: 100 + 20 % = 120, con IVA 152.40, sube a 160.00")
    void iva27() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), bd("20"), AlicuotaIva.IVA_27, bd("10")), "160.00");
    }

    @Test
    @DisplayName("IVA 2,5 % conserva los decimales de la alicuota: 100 + 0 % = 102.50")
    void iva2coma5() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), BigDecimal.ZERO, AlicuotaIva.IVA_2_5, null), "102.50");
    }

    @Test
    @DisplayName("costo 0 da precio 0.00")
    void costoCero() {
        assertPrecio(PrecioCalculator.precioFinal(BigDecimal.ZERO, bd("35"), AlicuotaIva.IVA_21, bd("10")), "0.00");
    }

    @Test
    @DisplayName("la escala completa se mantiene hasta el redondeo final")
    void precisionCompleta() {
        // 0.07 * 1.005 = 0.07035: el neto no se redondea antes, y el total sube a 0.08 (no queda en 0.07).
        assertPrecio(PrecioCalculator.precioFinal(bd("0.07"), bd("0.50"), AlicuotaIva.IVA_0, null), "0.08");
    }

    @Test
    @DisplayName("el redondeo no depende de la escala con que llegan los parametros")
    void escalaDeLosParametros() {
        assertPrecio(PrecioCalculator.precioFinal(bd("100.00"), bd("35.00"), AlicuotaIva.IVA_21, bd("10.00")), "170.00");
    }

    @Test
    @DisplayName("costo null lanza IllegalArgumentException")
    void costoNull() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(null, bd("35"), AlicuotaIva.IVA_21, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("costo negativo lanza IllegalArgumentException")
    void costoNegativo() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("-1"), bd("35"), AlicuotaIva.IVA_21, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("margen null lanza IllegalArgumentException")
    void margenNull() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("100"), null, AlicuotaIva.IVA_21, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("margen negativo lanza IllegalArgumentException")
    void margenNegativo() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("100"), bd("-5"), AlicuotaIva.IVA_21, bd("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IVA null lanza IllegalArgumentException")
    void ivaNull() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("100"), bd("35"), null, bd("10")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La alícuota de IVA es obligatoria.");
    }

    @Test
    @DisplayName("con cotizacion: 10 USD x 1000 + 30 % + IVA 21 % a multiplo 10 da 15730.00")
    void conCotizacion1000() {
        assertPrecio(PrecioCalculator.precioFinal(bd("10"), bd("30"), AlicuotaIva.IVA_21, bd("10"), bd("1000")), "15730.00");
    }

    @Test
    @DisplayName("con cotizacion 1100 el mismo articulo sube a 17310.00")
    void conCotizacion1100() {
        assertPrecio(PrecioCalculator.precioFinal(bd("10"), bd("30"), AlicuotaIva.IVA_21, bd("10"), bd("1100")), "17310.00");
    }

    @Test
    @DisplayName("con cotizacion 950 el mismo articulo baja a 14950.00")
    void conCotizacion950() {
        assertPrecio(PrecioCalculator.precioFinal(bd("10"), bd("30"), AlicuotaIva.IVA_21, bd("10"), bd("950")), "14950.00");
    }

    @Test
    @DisplayName("la cotizacion con 4 decimales entra exacta, sin punto flotante: 10 x 1000.5000 = 10005.00")
    void cotizacionConCuatroDecimales() {
        assertPrecio(PrecioCalculator.precioFinal(bd("10"), BigDecimal.ZERO, AlicuotaIva.IVA_0, null, bd("1000.5000")), "10005.00");
    }

    @Test
    @DisplayName("la cotizacion se multiplica antes del unico redondeo: 0.01 x 1000.3333 sube a 10.01")
    void cotizacionNoRedondeaAntes() {
        // 0.01 x 1000.3333 = 10.003333, que sube a 10.01 (si se redondeara antes a 10.00 daria 10.00).
        assertPrecio(PrecioCalculator.precioFinal(bd("0.01"), BigDecimal.ZERO, AlicuotaIva.IVA_0, null, bd("1000.3333")), "10.01");
    }

    @Test
    @DisplayName("cotizacion 1 (pesos) da lo mismo que la sobrecarga sin cotizacion: costo 1000 -> 1580.00")
    void cotizacionUnoEsPesos() {
        assertPrecio(PrecioCalculator.precioFinal(bd("1000"), bd("30"), AlicuotaIva.IVA_21, bd("10"), BigDecimal.ONE), "1580.00");
        assertPrecio(PrecioCalculator.precioFinal(bd("1000"), bd("30"), AlicuotaIva.IVA_21, bd("10")), "1580.00");
    }

    @Test
    @DisplayName("cotizacion null lanza IllegalArgumentException")
    void cotizacionNull() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("10"), bd("30"), AlicuotaIva.IVA_21, bd("10"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La cotización debe ser un valor mayor a 0.");
    }

    @Test
    @DisplayName("cotizacion 0 o negativa lanza IllegalArgumentException")
    void cotizacionCeroONegativa() {
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("10"), bd("30"), AlicuotaIva.IVA_21, bd("10"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrecioCalculator.precioFinal(bd("10"), bd("30"), AlicuotaIva.IVA_21, bd("10"), bd("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("margen efectivo: el propio del articulo gana sobre rubro y ferreteria")
    void margenPropioGana() {
        assertThat(PrecioCalculator.margenEfectivo(bd("50"), bd("35"), bd("30"))).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("margen efectivo: sin margen propio hereda el del rubro")
    void margenHeredaDelRubro() {
        assertThat(PrecioCalculator.margenEfectivo(null, bd("35"), bd("30"))).isEqualByComparingTo("35");
    }

    @Test
    @DisplayName("margen efectivo: sin margen propio ni rubro usa el de la ferreteria")
    void margenHeredaDeLaFerreteria() {
        assertThat(PrecioCalculator.margenEfectivo(null, null, bd("30"))).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("margen efectivo: un margen propio de 0 se respeta y no hereda")
    void margenPropioCeroSeRespeta() {
        assertThat(PrecioCalculator.margenEfectivo(BigDecimal.ZERO, bd("35"), bd("30"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("margen efectivo: un margen de rubro 0 se respeta y no cae a la ferreteria")
    void margenRubroCeroSeRespeta() {
        assertThat(PrecioCalculator.margenEfectivo(null, BigDecimal.ZERO, bd("30"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("margen efectivo: sin ningun margen lanza IllegalArgumentException")
    void margenEfectivoSinNinguno() {
        assertThatThrownBy(() -> PrecioCalculator.margenEfectivo(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No hay ningún margen para calcular el precio.");
    }

    @Test
    @DisplayName("herencia completa: articulo sin rubro ni margen, ferreteria 30 -> 160.00")
    void articuloSinRubroUsaFerreteria() {
        BigDecimal margen = PrecioCalculator.margenEfectivo(null, null, bd("30"));
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), margen, AlicuotaIva.IVA_21, bd("10")), "160.00");
    }

    @Test
    @DisplayName("herencia completa: margen propio 50 gana sobre rubro 35 -> 190.00")
    void margenPropioDa190() {
        BigDecimal margen = PrecioCalculator.margenEfectivo(bd("50"), bd("35"), bd("30"));
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), margen, AlicuotaIva.IVA_21, bd("10")), "190.00");
    }

    @Test
    @DisplayName("herencia completa: margen propio 0 da 121.00 que sube a 130.00")
    void margenCeroDa130() {
        BigDecimal margen = PrecioCalculator.margenEfectivo(BigDecimal.ZERO, bd("35"), bd("30"));
        assertPrecio(PrecioCalculator.precioFinal(bd("100"), margen, AlicuotaIva.IVA_21, bd("10")), "130.00");
    }

    @Test
    @DisplayName("origen del margen: el propio manda, despues el del rubro, despues el de la ferreteria")
    void origenDelMargen() {
        assertThat(PrecioCalculator.origenMargen(bd("50"), bd("35"), bd("30"))).isEqualTo(OrigenMargen.PROPIO);
        assertThat(PrecioCalculator.origenMargen(null, bd("35"), bd("30"))).isEqualTo(OrigenMargen.RUBRO);
        assertThat(PrecioCalculator.origenMargen(null, null, bd("30"))).isEqualTo(OrigenMargen.FERRETERIA);
    }

    @Test
    @DisplayName("origen del margen: un margen propio de 0 es PROPIO (el cero se respeta)")
    void origenDelMargenCeroEsPropio() {
        assertThat(PrecioCalculator.origenMargen(BigDecimal.ZERO, bd("35"), bd("30"))).isEqualTo(OrigenMargen.PROPIO);
    }

    @Test
    @DisplayName("origen del margen: sin ningun margen lanza IllegalArgumentException, igual que margenEfectivo")
    void origenDelMargenSinNinguno() {
        assertThatThrownBy(() -> PrecioCalculator.origenMargen(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No hay ningún margen para calcular el precio.");
    }
}
