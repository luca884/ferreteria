package com.ferreteria.facturacion.simulador;

import static com.ferreteria.facturacion.wsfe.PedidoDePrueba.alicuota;
import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.DesgloseFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.RenglonFiscal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * S7: el desglose por alícuota a nivel comprobante ({@code DesgloseFiscal}) pasa 10051 en el simulador; sumar el neto y el
 * IVA renglón por renglón, como el ticket de hoy ({@code RenglonVenta}: neto = total x 100 / 121 HALF_UP, así cada $ 100 al
 * 21 % da 82,64 + 17,36), falla con dos y con tres renglones.
 */
class SimuladorWsfeDesgloseTest extends SimuladorWsfeTestBase {

    private static final BigDecimal CIEN = new BigDecimal("100");

    @ParameterizedTest(name = "{0} renglones de $ 100 al 21 %")
    @CsvSource({"2, 165.29, 34.71", "3, 247.93, 52.07"})
    @DisplayName("desglosados por comprobante pasan 10051 y se autorizan")
    void elDesglosePorComprobantePasa(int renglones, String baseImp, String importe) {
        DesgloseFiscal desglose = DesgloseFiscal.de(Letra.B,
                Collections.nCopies(renglones, new RenglonFiscal(Alicuota.IVA_21, CIEN)));
        assertThat(desglose.alicuotas()).singleElement().satisfies(a -> {
            assertThat(a.baseImp()).isEqualByComparingTo(baseImp);
            assertThat(a.importe()).isEqualByComparingTo(importe);
        });

        aprobado(solicitar(facturaB().desglose(desglose)));
    }

    @ParameterizedTest(name = "{0} renglones de $ 100 al 21 %")
    @CsvSource({"2, 165.28, 34.72", "3, 247.92, 52.08"})
    @DisplayName("sumados renglón por renglón (82,64 + 17,36 cada uno) fallan con 10051")
    void sumadosPorRenglonFallan(int renglones, String netoEsperado, String ivaEsperado) {
        BigDecimal netoPorRenglon = CIEN.multiply(CIEN).divide(new BigDecimal("121"), 2, RoundingMode.HALF_UP);
        BigDecimal neto = netoPorRenglon.multiply(BigDecimal.valueOf(renglones));
        BigDecimal iva = CIEN.subtract(netoPorRenglon).multiply(BigDecimal.valueOf(renglones));
        BigDecimal total = CIEN.multiply(BigDecimal.valueOf(renglones));
        assertThat(neto).isEqualByComparingTo(netoEsperado);
        assertThat(iva).isEqualByComparingTo(ivaEsperado);

        rechazadoCon(solicitar(facturaB().importes(total.toPlainString(), neto.toPlainString(), iva.toPlainString())
                .alicuotas(alicuota(5, neto.toPlainString(), iva.toPlainString()))), 10051);
        assertThat(ultimo(6)).isZero();
    }
}
