package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LectorPdfTest {

    private static final String MENSAJE_GRANDE =
        "La lista es demasiado grande para leerla. Dividila en archivos más chicos.";

    private final LectorPdf lector = new LectorPdf();

    private static final List<List<String>> LISTA = List.of(
        List.of("Código", "Descripción", "Precio"),
        List.of("A-1", "Tornillo 3/8 x 1", "$ 1.532,44"),
        List.of("A-2", "Tuerca", "US$ 4,00"));

    private static List<String> textos(FilaLeida fila) {
        return fila.celdas().stream().map(Celda::texto).toList();
    }

    @Test
    void leeLasFilasYLasColumnasDeUnPdfConTextoEnUnaSolaHoja() {
        LecturaLista lectura = lector.leer(ListasDePrueba.pdf(LISTA));

        assertThat(lectura.hojas()).containsExactly("PDF");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsOnly(0);
        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 2, 3);
        assertThat(textos(lectura.filas().get(0))).containsExactly("Código", "Descripción", "Precio");
        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tornillo 3/8 x 1", "$ 1.532,44");
        assertThat(textos(lectura.filas().get(2))).containsExactly("A-2", "Tuerca", "US$ 4,00");
    }

    @Test
    void lasPaginasSiguenEnLaMismaHojaConLaMismaNumeracion() {
        List<List<String>> filas = new ArrayList<>();
        for (int i = 1; i <= ListasDePrueba.RENGLONES_POR_PAGINA_PDF + 5; i++) {
            filas.add(List.of("A-" + i, "Articulo " + i, i + ",00"));
        }

        LecturaLista lectura = lector.leer(ListasDePrueba.pdf(filas));

        assertThat(lectura.filas()).hasSize(filas.size());
        FilaLeida ultima = lectura.filas().get(filas.size() - 1);
        assertThat(ultima.numero()).isEqualTo(filas.size());
        int n = filas.size();
        assertThat(textos(ultima)).containsExactly("A-" + n, "Articulo " + n, n + ",00");
    }

    @Test
    void unPdfSinTextoAvisaQueNoSePuedeLeer() {
        assertThatThrownBy(() -> lector.leer(ListasDePrueba.pdfSinTexto()))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no "
                + "sea escaneado.");
    }

    @Test
    void unPdfConContrasenaAvisaQueEstaProtegido() {
        assertThatThrownBy(() -> lector.leer(ListasDePrueba.pdfProtegido(LISTA, "secreta")))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El archivo está protegido con contraseña.");
    }

    @Test
    void unPdfDanadoNoSePuedeLeer() {
        byte[] danado = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> lector.leer(danado))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("No se pudo leer el archivo. Verificá que no esté dañado.");
    }

    @Test
    void unPdfQueSeInflaMasQueElTopeEsDemasiadoGrande() {
        LectorPdf conTopeChico = new LectorPdf(LectorPdf.MAXIMO_PALABRAS, 1_000_000);

        assertThatThrownBy(() -> conTopeChico.leer(ListasDePrueba.pdfInflado(LISTA, 2_000_000)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_GRANDE);
    }

    @Test
    void unPdfQueSeInflaHastaElTopeSeLee() {
        LectorPdf conTopeChico = new LectorPdf(LectorPdf.MAXIMO_PALABRAS, 1_000_000);

        LecturaLista lectura = conTopeChico.leer(ListasDePrueba.pdfInflado(LISTA, 500_000));

        assertThat(lectura.filas()).hasSize(3);
    }

    @Test
    void lasImagenesNoCuentanParaElTopeDeInflado() {
        // Una imagen de 1000 x 1000 se infla a 3 MB, pero para leer el texto no se descomprime.
        LectorPdf conTopeChico = new LectorPdf(LectorPdf.MAXIMO_PALABRAS, 1_000_000);

        LecturaLista lectura = conTopeChico.leer(ListasDePrueba.pdfConImagen(LISTA, 1000));

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tornillo 3/8 x 1", "$ 1.532,44");
    }

    @Test
    void unPdfConMasPalabrasQueElTopeEsDemasiadoGrande() {
        LectorPdf conTopeChico = new LectorPdf(8, LectorPdf.MAXIMO_INFLADO_BYTES);

        assertThatThrownBy(() -> conTopeChico.leer(ListasDePrueba.pdf(LISTA)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_GRANDE);
    }
}
