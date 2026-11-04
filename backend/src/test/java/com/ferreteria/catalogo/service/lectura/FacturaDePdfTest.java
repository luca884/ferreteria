package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.FacturaLeida.Control;
import com.ferreteria.catalogo.api.FacturaLeida.Renglon;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Facturas inventadas, con las formas que tienen las de los proveedores; ninguna es una factura real. */
class FacturaDePdfTest {

    private static final double TAMANO = 10;
    private static final double SEPARACION_ENTRE_LINEAS = 14;

    /** Las lineas de una pagina como palabras con posicion: una linea por texto, cada palabra a la derecha de la otra. */
    private static List<PalabraPdf> pagina(int numero, String... lineas) {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < lineas.length; i++) {
            double base = 60 + i * SEPARACION_ENTRE_LINEAS; // la linea base crece hacia abajo
            double x = 30;
            for (String texto : lineas[i].split(" ")) {
                double ancho = texto.length() * 5;
                palabras.add(new PalabraPdf(numero, x, x + ancho, base, TAMANO, texto));
                x += ancho + 4;
            }
        }
        return palabras;
    }

    private static List<PalabraPdf> juntar(List<PalabraPdf>... paginas) {
        List<PalabraPdf> todas = new ArrayList<>();
        for (List<PalabraPdf> pagina : paginas) {
            todas.addAll(pagina);
        }
        return todas;
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    /** Dos paginas en formato US, con encabezado repetido, transporte, bonificaciones, embalaje y percepciones. */
    private static List<PalabraPdf> facturaEnFormatoUs() {
        List<PalabraPdf> primera = pagina(1,
            "EJEMPLO S.A. C.U.I.T.: 30-71234567-1",
            "INIC. ACTIV.: 01/03/2015",
            "FFEACCHAT: URA 0013 - 00605684 FECHA: 24/09/2026",
            "Cliente C.U.I.T.: 20-12345678-6 C.U.I.T.: 20-12345678-9",
            "Código 01",
            "CODIGO CANT. DESCRIPCION DESPACHO FECHA LUGAR UNITARIO NETO",
            "9960110.5 1.0 SOLDADORA INVERSOR 200A 24/09/2026 DEP 235,971.56 235,971.56",
            "MKT100020 12.0 CABLE 2.5MM ROLLO 1,000.50 12,006.00 *",
            "Transporte : 969,250.92");
        List<PalabraPdf> segunda = pagina(2,
            "EJEMPLO S.A. C.U.I.T.: 30-71234567-1",
            "Transporte : 969,250.92",
            "R2780219 3.0 CINTA 19 MM 100.00 5.00 % 285.00",
            "A1 2.0 TUERCA 50.00 10.00% 90.00",
            "EMBALAJE Y CARGA : 4.44% 11,026.85",
            "PERCEPCION IIBB : 3.00% 5,000.00",
            "Subtotal 259,379.41");
        return juntar(primera, segunda);
    }

    @Test
    void leeElEncabezadoLosRenglonesElRecargoYElControlDeUnaFacturaEnFormatoUs() {
        FacturaLeida factura = FacturaDePdf.leer(facturaEnFormatoUs());

        assertThat(factura.encabezado().letra()).isEqualTo("A");
        assertThat(factura.encabezado().puntoVenta()).isEqualTo(13);
        assertThat(factura.encabezado().numero()).isEqualTo(605684L);
        assertThat(factura.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(factura.renglones()).extracting(Renglon::codigo)
            .containsExactly("9960110.5", "MKT100020", "R2780219", "A1");
        assertThat(factura.renglones().get(0).cantidad()).isEqualByComparingTo("1");
        assertThat(factura.renglones().get(0).descripcion()).isEqualTo("SOLDADORA INVERSOR 200A 24/09/2026 DEP");
        assertThat(factura.renglones().get(0).costoUnitario()).isEqualByComparingTo("235971.56");
        assertThat(factura.renglones().get(0).neto()).isEqualByComparingTo("235971.56");
        assertThat(factura.renglones().get(1).cantidad()).isEqualByComparingTo("12");
        assertThat(factura.renglones().get(1).costoUnitario()).isEqualByComparingTo("1000.50");
        assertThat(factura.renglones().get(1).neto()).isEqualByComparingTo("12006.00");
        assertThat(factura.sumaNetos()).isEqualByComparingTo("248352.56");
        assertThat(factura.recargos()).hasSize(1);
        assertThat(factura.recargos().get(0).nombre()).isEqualTo("EMBALAJE Y CARGA");
        assertThat(factura.recargos().get(0).porcentaje()).isEqualByComparingTo("4.44");
        assertThat(factura.recargos().get(0).importe()).isEqualByComparingTo("11026.85");
        assertThat(factura.control()).isEqualTo(Control.CUADRA);
    }

    @Test
    void unaPercepcionCuyoImporteNoEsElPorcentajeDeLosNetosNoEsRecargo() {
        FacturaLeida factura = FacturaDePdf.leer(facturaEnFormatoUs());

        assertThat(factura.recargos()).extracting(r -> r.nombre()).doesNotContain("PERCEPCION IIBB");
    }

    @Test
    void lasLineasDeTransporteNoSonRenglonesYElEncabezadoRepetidoTampoco() {
        FacturaLeida factura = FacturaDePdf.leer(facturaEnFormatoUs());

        assertThat(factura.renglones()).hasSize(4);
    }

    @Test
    void unRenglonConBonificacionDaElCostoUnitarioConLaBonificacionAplicada() {
        FacturaLeida factura = FacturaDePdf.leer(facturaEnFormatoUs());

        Renglon separado = factura.renglones().get(2);
        assertThat(separado.cantidad()).isEqualByComparingTo("3");
        assertThat(separado.descripcion()).isEqualTo("CINTA 19 MM");
        assertThat(separado.costoUnitario()).isEqualByComparingTo("95.00");
        assertThat(separado.neto()).isEqualByComparingTo("285.00");
        Renglon pegado = factura.renglones().get(3);
        assertThat(pegado.costoUnitario()).isEqualByComparingTo("45.00");
        assertThat(pegado.neto()).isEqualByComparingTo("90.00");
    }

    @Test
    void leeUnaFacturaEnFormatoArgentino() {
        FacturaLeida factura = FacturaDePdf.leer(juntar(pagina(1,
            "Factura B Código 06",
            "Nº 0002 - 00000123 Fecha 05/01/2026",
            "X1 2,0 TORNILLO 1.234,56 2.469,12",
            "X2 1,5 CAÑO 100,00 150,00",
            "Total 2.619,12")));

        assertThat(factura.encabezado().letra()).isEqualTo("B");
        assertThat(factura.encabezado().puntoVenta()).isEqualTo(2);
        assertThat(factura.encabezado().numero()).isEqualTo(123L);
        assertThat(factura.renglones()).hasSize(2);
        assertThat(factura.renglones().get(0).costoUnitario()).isEqualByComparingTo("1234.56");
        assertThat(factura.renglones().get(0).neto()).isEqualByComparingTo("2469.12");
        assertThat(factura.renglones().get(1).cantidad()).isEqualByComparingTo("1.5");
        assertThat(factura.sumaNetos()).isEqualByComparingTo("2619.12");
        assertThat(factura.control()).isEqualTo(Control.CUADRA);
    }

    @Test
    void laFechaDeUnaLineaDeInicioDeActividadSeSalteaYSiNoHayOtraEsNull() {
        FacturaLeida factura = FacturaDePdf.leer(juntar(pagina(1,
            "INIC. ACTIV.: 01/03/2015",
            "Vencimiento CAE VTO: 30/09/2026",
            "X1 1.0 TORNILLO 10.00 10.00")));

        assertThat(factura.encabezado().fecha()).isNull();
    }

    @Test
    void laLetraSaleDeUnaPalabraSueltaSiNoHayCodigoYEsNullSiNoHayNinguna() {
        FacturaLeida conPalabra = FacturaDePdf.leer(juntar(pagina(1,
            "FACTURA C", "X1 1.0 TORNILLO 10.00 10.00")));
        FacturaLeida sinLetra = FacturaDePdf.leer(juntar(pagina(1,
            "FACTURA", "X1 1.0 TORNILLO 10.00 10.00")));

        assertThat(conPalabra.encabezado().letra()).isEqualTo("C");
        assertThat(sinLetra.encabezado().letra()).isNull();
    }

    @Test
    void losCuitConDigitoVerificadorIncorrectoSeDescartan() {
        FacturaLeida factura = FacturaDePdf.leer(facturaEnFormatoUs());

        assertThat(factura.encabezado().cuits()).containsExactly("30712345671", "20123456786");
    }

    @Test
    void sinTotalImpresoQueCoincidaElControlEsSinControl() {
        FacturaLeida factura = FacturaDePdf.leer(juntar(pagina(1,
            "X1 1.0 TORNILLO 10.00 10.00",
            "Total 99.99")));

        assertThat(factura.control()).isEqualTo(Control.SIN_CONTROL);
        assertThat(factura.sumaNetos()).isEqualByComparingTo("10.00");
    }

    @Test
    void sinRenglonesReconocidosDevuelveListasVaciasSinError() {
        FacturaLeida factura = FacturaDePdf.leer(juntar(pagina(1,
            "FACTURA A Código 01", "Nº 0001 - 00000001", "Hola mundo 12.50")));

        assertThat(factura.renglones()).isEmpty();
        assertThat(factura.recargos()).isEmpty();
        assertThat(factura.sumaNetos()).isEqualByComparingTo("0");
        assertThat(factura.control()).isEqualTo(Control.SIN_CONTROL);
        assertThat(factura.encabezado().numero()).isEqualTo(1L);
    }
}
