package com.ferreteria.catalogo.service.lectura;

import static com.ferreteria.catalogo.service.lectura.LectorPdf.TOPES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.catalogo.service.lectura.LectorPdf.Topes;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LectorPdfTest {

    private static final String MENSAJE_GRANDE =
        "La lista es demasiado grande para leerla. Dividila en archivos más chicos.";
    private static final String ARCHIVO_DANADO = "No se pudo leer el archivo. Verificá que no esté dañado.";

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
    void unPdfConUnaImagenGrandeSeLeeDentroDeLaMemoriaDelProceso() {
        // 3000 x 3000 pixeles son 27 MB sin comprimir: para leer el texto, la imagen no se descomprime.
        LecturaLista lectura = lector.leer(ListasDePrueba.pdfConImagen(LISTA, 3000));

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tornillo 3/8 x 1", "$ 1.532,44");
    }

    @Test
    void unPdfConMasPalabrasQueElTopeEsDemasiadoGrande() {
        LectorPdf conTopeChico = new LectorPdf(new Topes(8, TOPES.memoria(), TOPES.tiempo(), TOPES.espera()),
            ExtractorPdf.class);

        assertThatThrownBy(() -> conTopeChico.leer(ListasDePrueba.pdf(LISTA)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_GRANDE);
    }

    @Test
    void unPdfQueNoEntraEnLaMemoriaDelProcesoQueLoLeeEsDemasiadoGrande() {
        // 500.000 letras en una pagina: PDFBox guarda cada una (unos 100 MB) antes de entregar las palabras.
        LectorPdf conPocaMemoria = new LectorPdf(new Topes(TOPES.palabras(), "32m", TOPES.tiempo(), TOPES.espera()),
            ExtractorPdf.class);

        assertThatThrownBy(() -> conPocaMemoria.leer(ListasDePrueba.pdfConPaginaLlena(500, 1000)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_GRANDE);
        assertThat(procesosHijosVivos()).isZero();
    }

    @Test
    void unProcesoQueNoTerminaATiempoSeMataYLaListaEsDemasiadoGrande() {
        Topes unSegundo = new Topes(TOPES.palabras(), TOPES.memoria(), Duration.ofSeconds(1), TOPES.espera());
        LectorPdf conPocoTiempo = new LectorPdf(unSegundo, HijosDePrueba.NoTermina.class);
        long desde = System.nanoTime();

        assertThatThrownBy(() -> conPocoTiempo.leer(ListasDePrueba.pdf(LISTA)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_GRANDE);
        assertThat(Duration.ofNanos(System.nanoTime() - desde)).isLessThan(Duration.ofSeconds(10));
        assertThat(procesosHijosVivos()).isZero();
    }

    @Test
    void unProcesoQueSeCaeSinAvisarEsUnArchivoDanado() {
        LectorPdf queSeCae = new LectorPdf(TOPES, HijosDePrueba.SeCae.class);

        assertThatThrownBy(() -> queSeCae.leer(ListasDePrueba.pdf(LISTA)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(ARCHIVO_DANADO);
    }

    @Test
    void seLeeUnPdfPorVezYElQueNoConsigueTurnoATiempoAvisa() throws Exception {
        Topes esperaCorta = new Topes(TOPES.palabras(), TOPES.memoria(), TOPES.tiempo(), Duration.ofMillis(300));
        LectorPdf lento = new LectorPdf(esperaCorta, HijosDePrueba.Tarda.class);
        ExecutorService otro = Executors.newSingleThreadExecutor();
        try {
            Future<?> primero = otro.submit(() -> lento.leer(ListasDePrueba.pdf(LISTA)));
            esperarUnProcesoHijo();

            assertThatThrownBy(() -> lento.leer(ListasDePrueba.pdf(LISTA)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Ya se está leyendo otra lista en PDF. Probá de nuevo en un momento.");

            // El primero sigue su curso y, al terminar (mal o bien), libera el turno.
            assertThatThrownBy(primero::get).hasRootCauseMessage(ARCHIVO_DANADO);
            assertThatThrownBy(() -> lento.leer(ListasDePrueba.pdf(LISTA))).hasMessage(ARCHIVO_DANADO);
        } finally {
            otro.shutdownNow();
        }
    }

    private static long procesosHijosVivos() {
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
    }

    private static void esperarUnProcesoHijo() throws InterruptedException {
        long hasta = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (procesosHijosVivos() == 0) {
            assertThat(System.nanoTime()).as("arranco el proceso que lee el PDF").isLessThan(hasta);
            Thread.sleep(20);
        }
    }
}
