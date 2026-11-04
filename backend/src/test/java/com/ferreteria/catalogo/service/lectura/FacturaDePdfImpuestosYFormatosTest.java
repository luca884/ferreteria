package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.FacturaLeida.Control;
import com.ferreteria.catalogo.api.FacturaLeida.Renglon;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regresion de CU-13 (factura en PDF): facturas inventadas con formas reales de los totales. */
class FacturaDePdfImpuestosYFormatosTest {

    private static List<PalabraPdf> pagina(String... lineas) {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < lineas.length; i++) {
            double base = 60 + i * 14;
            double x = 30;
            for (String texto : lineas[i].split(" ")) {
                double ancho = texto.length() * 5;
                palabras.add(new PalabraPdf(1, x, x + ancho, base, 10, texto));
                x += ancho + 4;
            }
        }
        return palabras;
    }

    // Factura A con todos los renglones al 21 %: la linea de IVA es el 21 % de la suma de los netos.
    @Test
    void ivaAl21DeUnaFacturaAEnFormatoArNoEsRecargo() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "Subtotal 2.619,12",
            "IVA 21% 550,02",
            "Total 3.169,14"));
        assertThat(f.recargos()).as("el IVA no es un recargo del costo").isEmpty();
    }

    @Test
    void ivaAl21ConPorcentajeSeparadoNoEsRecargo() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "Neto Gravado 2.619,12",
            "I.V.A. 21,00 % 550,02",
            "Total 3.169,14"));
        assertThat(f.recargos()).as("el IVA no es un recargo del costo").isEmpty();
    }

    @Test
    void ivaAl21EnFormatoUsNoEsRecargo() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA Código 01 0002 - 00000123 FECHA: 05/01/2026",
            "X1 2.0 TORNILLO 1,234.56 2,469.12",
            "X2 1.5 CANO 100.00 150.00",
            "SUBTOTAL 2,619.12",
            "IVA 21% 550.02",
            "TOTAL 3,169.14"));
        assertThat(f.recargos()).as("el IVA no es un recargo del costo").isEmpty();
    }

    // Percepcion de IIBB calculada sobre el neto (sin recargos, el neto gravado es la suma de los netos).
    @Test
    void percepcionSobreElNetoNoEsRecargo() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "Subtotal 2.619,12",
            "Percepción IIBB 3,00% 78,57",
            "Total 2.697,69"));
        assertThat(f.recargos()).as("una percepcion no es un recargo del costo").isEmpty();
    }

    // Embalaje + IVA: el IVA va sobre neto + embalaje y no coincide; el embalaje si es recargo.
    @Test
    void embalajeConIvaSobreElSubtotalSoloLeeElEmbalaje() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "EMBALAJE Y CARGA : 4,44% 116,29",
            "Subtotal 2.735,41",
            "IVA 21% 574,44",
            "Total 3.309,85"));
        assertThat(f.recargos()).hasSize(1);
        assertThat(f.recargos().get(0).nombre()).isEqualTo("EMBALAJE Y CARGA");
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    // Descuento general impreso en positivo: no deberia sumarse como recargo positivo.
    @Test
    void descuentoGeneralImpresoEnPositivoNoSumaAlCosto() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "Descuento 5% 130,96",
            "Subtotal 2.488,16"));
        assertThat(f.recargos()).hasSize(1);
        assertThat(f.recargos().get(0).porcentaje()).isEqualByComparingTo("-5");
        assertThat(f.recargos().get(0).importe()).isEqualByComparingTo("-130.96");
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    @Test
    void descuentoGeneralImpresoEnNegativo() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "Descuento 5% -130,96",
            "Subtotal 2.488,16"));
        assertThat(f.recargos()).hasSize(1);
        assertThat(f.recargos().get(0).porcentaje()).isEqualByComparingTo("-5");
        assertThat(f.recargos().get(0).importe()).isEqualByComparingTo("-130.96");
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    // Columna de bonificacion sin el signo %.
    @Test
    void bonificacionSinSignoPorcentaje() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 10 TORNILLO 250,00 10,00 2.250,00",
            "X2 4 TUERCA 50,00 0,00 200,00",
            "Subtotal 2.450,00"));
        // Una bonificacion sin «%» no se lee: el renglon queda afuera y el control no se puede hacer.
        assertThat(f.renglones()).extracting(Renglon::codigo).containsExactly("X2");
        assertThat(f.control()).isEqualTo(Control.SIN_CONTROL);
    }

    // Formato AR sin decimales: 1.500 y 3.000 son ambiguos.
    @Test
    void formatoArSinDecimales() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1",
            "FACTURA B Código 06 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2 TORNILLO 1.500 3.000",
            "X2 3 TUERCA 2.250 6.750",
            "Total 9.750"));
        assertThat(f.renglones()).hasSize(2);
        assertThat(f.renglones().get(0).costoUnitario()).isEqualByComparingTo("1500");
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    // Formato US sin separador de miles.
    @Test
    void formatoUsSinMiles() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA A Código 01 0002 - 00000123 FECHA: 05/01/2026",
            "X1 2.00 TORNILLO 1500.00 3000.00",
            "X2 3.00 TUERCA 2250.50 6751.50",
            "TOTAL 9751.50"));
        assertThat(f.renglones()).hasSize(2);
        assertThat(f.renglones().get(1).costoUnitario()).isEqualByComparingTo("2250.50");
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    // Inicio de actividades abreviado sin "ACTIV" antes de la fecha de emision.
    @Test
    void inicioDeActividadesAbreviado() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-1 Inicio de Act.: 01/03/2015",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Total 2.469,12"));
        assertThat(f.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    // La fecha y el inicio de actividades mezclados en la misma linea (texto superpuesto, como en L2).
    @Test
    void fechaEnLaMismaLineaQueInicioDeActividades() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA A Código 01 Nº 0002 - 00000123",
            "FECHA: 05/01/2026 INIC. ACTIV.: 01/03/2015",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Total 2.469,12"));
        assertThat(f.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    // Formato del comprobante en linea de ARCA: sin guion entre punto de venta y numero.
    @Test
    void comprobanteEnLineaDeArca() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA COD. 01",
            "Punto de Venta: 00002 Comp. Nro: 00000123",
            "Fecha de Emisión: 05/01/2026",
            "CUIT: 30712345671",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Importe Total: $ 2.469,12"));
        assertThat(f.encabezado().puntoVenta()).isEqualTo(2);
        assertThat(f.encabezado().numero()).isEqualTo(123L);
        assertThat(f.encabezado().letra()).isEqualTo("A");
        assertThat(f.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(f.encabezado().cuits()).containsExactly("30712345671");
    }

    @Test
    void comprobanteEnLineaDeArcaConEtiquetasEnMayusculasYPuntos() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA COD. 01",
            "PUNTO DE VENTA 00012 COMP. NRO. 00004567",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Total 2.469,12"));
        assertThat(f.encabezado().puntoVenta()).isEqualTo(12);
        assertThat(f.encabezado().numero()).isEqualTo(4567L);
    }

    @Test
    void fechaDespuesDeUnaEtiquetaDeInicioEnLaMismaLineaSeSaltea() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA A Código 01 Nº 0002 - 00000123",
            "INICIO DE ACTIVIDADES: 01/03/2015 FECHA: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Total 2.469,12"));
        assertThat(f.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void fechaDeVencimientoOInicioNoEsLaFechaDeEmision() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA A Código 01 Nº 0002 - 00000123",
            "Inicio: 01/03/2015 Vto. CAE: 15/01/2026",
            "Fecha de Emisión: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Total 2.469,12"));
        assertThat(f.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void ningunImpuestoEsUnRecargoSeaCualSeaSuNombre() {
        for (String impuesto : List.of("I.V.A. 21% 550,02", "PERCEPCIONES IIBB 21% 550,02",
            "Percep. 21% 550,02", "I.I.B.B. 21% 550,02", "Ingresos Brutos 21% 550,02",
            "Impuestos Internos 21% 550,02", "Impuesto interno 21% 550,02", "Ganancias 21% 550,02",
            "PERCEPCIÓN 21% 550,02", "percepción de iva 21% 550,02")) {
            FacturaLeida f = FacturaDePdf.leer(pagina(
                "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
                "X1 2,00 TORNILLO 1.234,56 2.469,12",
                "X2 1,50 CAÑO 100,00 150,00",
                impuesto,
                "Total 3.169,14"));
            assertThat(f.recargos()).as(impuesto).isEmpty();
        }
    }

    @Test
    void conSoloElIvaElControlCuadraSiElTotalImpresoEsLaSumaDeLosNetos() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "IVA 21% 550,02",
            "Subtotal 2.619,12"));
        assertThat(f.recargos()).isEmpty();
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    @Test
    void bonificacionConAcentoYAbreviadaEsRecargoNegativo() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "X2 1,50 CAÑO 100,00 150,00",
            "Bonif. 10% 261,91",
            "Subtotal 2.357,21"));
        assertThat(f.recargos()).hasSize(1);
        assertThat(f.recargos().get(0).porcentaje()).isEqualByComparingTo("-10");
        assertThat(f.recargos().get(0).importe()).isEqualByComparingTo("-261.91");
        assertThat(f.control()).isEqualTo(Control.CUADRA);
    }

    // CUIT con digito verificador incorrecto y uno valido sin guiones.
    @Test
    void cuitsInvalidosSeDescartan() {
        FacturaLeida f = FacturaDePdf.leer(pagina(
            "PROVEEDOR S.A. C.U.I.T.: 30-71234567-2 Tel 30712345671",
            "FACTURA A Código 01 Nº 0002 - 00000123 Fecha: 05/01/2026",
            "X1 2,00 TORNILLO 1.234,56 2.469,12",
            "Total 2.469,12"));
        assertThat(f.encabezado().cuits()).containsExactly("30712345671");
    }
}
