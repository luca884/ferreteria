package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.FacturaLeida.Renglon;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hallazgo 5 de la revision de seguridad del 2026-11-05: buscar el renglon en una linea es cubico en sus numeros, y un PDF
 * armado a proposito ocupaba la CPU del servidor durante minutos.
 */
class FacturaDePdfTopesTest {

    /** Una linea por texto, cada palabra a la derecha de la otra. */
    private static List<PalabraPdf> pagina(List<String> lineas) {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < lineas.size(); i++) {
            double base = 60 + i * 14;
            double x = 30;
            for (String texto : lineas.get(i).split(" ")) {
                double ancho = texto.length() * 5;
                palabras.add(new PalabraPdf(1, x, x + ancho, base, 10, texto));
                x += ancho + 4;
            }
        }
        return palabras;
    }

    /** Un codigo y {@code cuantos} numeros que nunca cuadran (3 x 3 no es 3): se prueban todas las ternas. */
    private static String lineaDeNumeros(String codigo, int cuantos) {
        return codigo + " " + String.join(" ", Collections.nCopies(cuantos, "3"));
    }

    @Test
    void unaLineaConMilSeiscientosNumerosSeLeeAlInstanteYNoEsUnRenglon() {
        List<PalabraPdf> palabras = pagina(List.of("A1 2.0 TUERCA 50.00 100.00", lineaDeNumeros("X1", 1_600)));

        FacturaLeida factura = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> FacturaDePdf.leer(palabras));

        assertThat(factura.renglones()).extracting(Renglon::codigo).containsExactly("A1");
    }

    @Test
    void elPeorPdfQueEntraEnElTopeDePalabrasSeInterpretaEnPocosSegundos() {
        // Cada linea con el maximo de numeros que todavia se prueba como renglon, hasta llenar el tope de palabras.
        int numerosPorLinea = FacturaDePdf.MAXIMO_NUMEROS_POR_RENGLON;
        int cuantasLineas = Math.min(LectorPdf.MAXIMO_PALABRAS / (numerosPorLinea + 1), LimitesDeLista.MAXIMO_FILAS_LEIDAS);
        List<String> lineas = new ArrayList<>();
        for (int i = 0; i < cuantasLineas; i++) {
            lineas.add(lineaDeNumeros("X" + i, numerosPorLinea));
        }
        List<PalabraPdf> palabras = pagina(lineas);

        FacturaLeida factura = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> FacturaDePdf.leer(palabras));

        assertThat(factura.renglones()).isEmpty();
    }

    @Test
    void numerosDeMilesDeDigitosNoSonNumerosYSeLeenAlInstante() {
        // B1 del verificador: el tope de numeros por linea no acota lo que cuesta cada terna con numeros enormes.
        String enorme = "1".repeat(10_000) + "," + "1".repeat(10_000);
        List<String> lineas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            lineas.add("X" + i + " " + String.join(" ", Collections.nCopies(FacturaDePdf.MAXIMO_NUMEROS_POR_RENGLON, enorme)));
        }
        // En formato argentino, como los numeros enormes: el formato se elige por la mayoria del documento.
        lineas.add("A1 2,0 TUERCA 50,00 100,00");
        List<PalabraPdf> palabras = pagina(lineas);

        FacturaLeida factura = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> FacturaDePdf.leer(palabras));

        assertThat(factura.renglones()).extracting(Renglon::codigo).containsExactly("A1");
    }

    @Test
    void unNumeroDeVeinteCaracteresSeSigueLeyendo() {
        List<PalabraPdf> palabras = pagina(List.of("A1 10.000,00 TUERCA 123.456.789,01 1.234.567.890.100,00"));

        FacturaLeida factura = FacturaDePdf.leer(palabras);

        assertThat(factura.renglones()).singleElement()
            .satisfies(renglon -> assertThat(renglon.neto()).isEqualByComparingTo("1234567890100.00"));
    }

    @Test
    void unRenglonConMuchosNumerosEnLaDescripcionSeSigueLeyendo() {
        // Medidas, cantidades por caja y codigos en la descripcion: 20 numeros en total, con la terna al final.
        List<PalabraPdf> palabras = pagina(List.of(
            "TOR-8 10.0 TORNILLO 8 X 1 1/2 CAJA 100 200 300 400 500 600 700 800 900 1000 1100 1200 1300 2.50 25.00"));

        FacturaLeida factura = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> FacturaDePdf.leer(palabras));

        assertThat(factura.renglones()).singleElement().satisfies(renglon -> {
            assertThat(renglon.codigo()).isEqualTo("TOR-8");
            assertThat(renglon.cantidad()).isEqualByComparingTo("10.0");
            assertThat(renglon.costoUnitario()).isEqualByComparingTo("2.50");
            assertThat(renglon.neto()).isEqualByComparingTo("25.00");
        });
    }
}
