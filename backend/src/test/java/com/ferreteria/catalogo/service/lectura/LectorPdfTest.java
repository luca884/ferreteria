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
import com.ferreteria.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LectorPdfTest {

    private static final String MENSAJE_GRANDE =
        "La lista es demasiado grande para leerla. Dividila en archivos más chicos.";
    private static final String ARCHIVO_DANADO = "No se pudo leer el archivo. Verificá que no esté dañado.";
    private static final String OCUPADO = "Ya se está leyendo otra lista en PDF. Probá de nuevo en un momento.";

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
                .hasMessage(OCUPADO);

            // El primero sigue su curso y, al terminar (mal o bien), libera el turno.
            assertThatThrownBy(primero::get).hasRootCauseMessage(ARCHIVO_DANADO);
            assertThatThrownBy(() -> lento.leer(ListasDePrueba.pdf(LISTA))).hasMessage(ARCHIVO_DANADO);
        } finally {
            otro.shutdownNow();
        }
    }

    @Test
    void elProcesoQueLeeCorreConLaPrioridadMasBajaYUnSoloProcesador() {
        LectorPdf lector = new LectorPdf(TOPES, HijosDePrueba.DicePrioridadYProcesadores.class);

        LecturaLista lectura = lector.leer(ListasDePrueba.pdf(LISTA));

        List<String> dichos = lectura.filas().stream().flatMap(fila -> textos(fila).stream())
            .filter(texto -> !texto.isBlank()).toList();
        assertThat(dichos).hasSize(2).contains("procesadores=1");
        // 19 por debajo de la del que la lanza: si los tests ya corren con menos prioridad, el sistema la topa en 20.
        String prioridad = dichos.stream().filter(texto -> texto.startsWith("prioridad=")).findFirst().orElseThrow();
        assertThat(Integer.parseInt(prioridad.substring("prioridad=".length()))).isGreaterThanOrEqualTo(19);
    }

    @Test
    void sinNiceElProcesoQueLeeArrancaIgualYLee() {
        LectorPdf sinNice = new LectorPdf(TOPES, ExtractorPdf.class, Optional.empty());

        LecturaLista lectura = sinNice.leer(ListasDePrueba.pdf(LISTA));

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tornillo 3/8 x 1", "$ 1.532,44");
    }

    @Test
    void cadaFerreteriaLeeUnPdfPorVezYLasOtrasEsperanSuTurno() throws Exception {
        LectorPdf lento = new LectorPdf(TOPES, HijosDePrueba.Tarda.class);
        byte[] pdf = ListasDePrueba.pdf(LISTA);
        ExecutorService pedidos = Executors.newFixedThreadPool(3);
        try {
            Future<String> deA = pedidos.submit(() -> leerComo(1L, lento, pdf));
            esperarUnProcesoHijo();
            Future<String> deB = pedidos.submit(() -> leerComo(2L, lento, pdf));
            Future<String> otroDeB = pedidos.submit(() -> leerComo(2L, lento, pdf));
            long desde = System.nanoTime();

            assertThat(leerComo(1L, lento, pdf)).as("A ya está leyendo: avisa sin esperar turno").isEqualTo(OCUPADO);
            assertThat(Duration.ofNanos(System.nanoTime() - desde)).isLessThan(Duration.ofSeconds(1));

            // El de A sigue su curso; B lee uno solo de los suyos, y al terminar A vuelve a poder leer.
            assertThat(deA.get()).isEqualTo(ARCHIVO_DANADO);
            assertThat(List.of(deB.get(), otroDeB.get())).containsExactlyInAnyOrder(ARCHIVO_DANADO, OCUPADO);
            assertThat(leerComo(1L, lento, pdf)).isEqualTo(ARCHIVO_DANADO);
        } finally {
            pedidos.shutdownNow();
        }
    }

    @Test
    void laFerreteriaQueNoConsigueTurnoATiempoQuedaLibreParaVolverAProbar() throws Exception {
        Topes esperaCorta = new Topes(TOPES.palabras(), TOPES.memoria(), TOPES.tiempo(), Duration.ofMillis(300));
        LectorPdf lento = new LectorPdf(esperaCorta, HijosDePrueba.Tarda.class);
        byte[] pdf = ListasDePrueba.pdf(LISTA);
        ExecutorService otro = Executors.newSingleThreadExecutor();
        try {
            Future<String> deA = otro.submit(() -> leerComo(1L, lento, pdf));
            esperarUnProcesoHijo();

            assertThat(leerComo(2L, lento, pdf)).isEqualTo(OCUPADO);

            assertThat(deA.get()).isEqualTo(ARCHIVO_DANADO);
            assertThat(leerComo(2L, lento, pdf)).isEqualTo(ARCHIVO_DANADO);
        } finally {
            otro.shutdownNow();
        }
    }

    @Test
    void interrumpirLaEsperaDelTurnoDejaLibreALaFerreteria() throws Exception {
        LectorPdf lento = new LectorPdf(TOPES, HijosDePrueba.Tarda.class);
        byte[] pdf = ListasDePrueba.pdf(LISTA);
        ExecutorService deA = Executors.newSingleThreadExecutor();
        ExecutorService deB = Executors.newSingleThreadExecutor();
        try {
            Future<String> leyendo = deA.submit(() -> leerComo(1L, lento, pdf));
            esperarUnProcesoHijo();
            Future<String> esperando = deB.submit(() -> leerComo(2L, lento, pdf));
            Thread.sleep(200);

            esperando.cancel(true);
            deB.shutdown();
            assertThat(deB.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(leerComo(2L, lento, pdf)).as("B espera su turno y lee").isEqualTo(ARCHIVO_DANADO);
            assertThat(leyendo.get()).isEqualTo(ARCHIVO_DANADO);
        } finally {
            deA.shutdownNow();
            deB.shutdownNow();
        }
    }

    @Test
    void interrumpirLaLecturaMataAlProcesoBorraLosTemporalesYDejaLibreALaFerreteria() throws Exception {
        long temporalesAntes = temporales();
        Topes dosSegundos = new Topes(TOPES.palabras(), TOPES.memoria(), Duration.ofSeconds(2), TOPES.espera());
        LectorPdf eterno = new LectorPdf(dosSegundos, HijosDePrueba.NoTermina.class);
        byte[] pdf = ListasDePrueba.pdf(LISTA);
        ExecutorService otro = Executors.newSingleThreadExecutor();
        try {
            Future<String> deA = otro.submit(() -> leerComo(1L, eterno, pdf));
            esperarUnProcesoHijo();

            deA.cancel(true);
            otro.shutdown();
            assertThat(otro.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            esperarSinProcesosHijos();
            assertThat(temporales()).isEqualTo(temporalesAntes);
            assertThat(leerComo(1L, eterno, pdf)).isEqualTo(MENSAJE_GRANDE);
        } finally {
            otro.shutdownNow();
        }
    }

    @Test
    void unProcesoQueNoArrancaDejaLibreALaFerreteria() {
        LectorPdf queNoArranca = new LectorPdf(TOPES, ExtractorPdf.class, Optional.of(Path.of("/no/existe/nice")));
        byte[] pdf = ListasDePrueba.pdf(LISTA);

        assertThatThrownBy(() -> leerComo(1L, queNoArranca, pdf)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> leerComo(1L, queNoArranca, pdf)).as("no avisa que está ocupada")
            .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void unPedidoSinFerreteriaEsperaSuTurnoAunqueLeaUnaFerreteria() throws Exception {
        LectorPdf lento = new LectorPdf(TOPES, HijosDePrueba.Tarda.class);
        byte[] pdf = ListasDePrueba.pdf(LISTA);
        ExecutorService otro = Executors.newSingleThreadExecutor();
        try {
            Future<String> deA = otro.submit(() -> leerComo(1L, lento, pdf));
            esperarUnProcesoHijo();

            assertThatThrownBy(() -> lento.leer(pdf)).hasMessage(ARCHIVO_DANADO);
            assertThat(deA.get()).isEqualTo(ARCHIVO_DANADO);
        } finally {
            otro.shutdownNow();
        }
    }

    @Test
    void entreFerreteriasSeLeeEnOrdenDeLlegada() throws Exception {
        LectorPdf lento = new LectorPdf(TOPES, HijosDePrueba.Tarda.class);
        byte[] pdf = ListasDePrueba.pdf(LISTA);
        List<Long> orden = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pedidos = Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> lecturas = new ArrayList<>();
            for (long ferreteria = 1; ferreteria <= 3; ferreteria++) {
                long esta = ferreteria;
                lecturas.add(pedidos.submit(() -> {
                    String aviso = leerComo(esta, lento, pdf);
                    orden.add(esta);
                    return aviso;
                }));
                Thread.sleep(150);
            }

            for (Future<String> lectura : lecturas) {
                assertThat(lectura.get()).isEqualTo(ARCHIVO_DANADO);
            }
            assertThat(orden).containsExactly(1L, 2L, 3L);
        } finally {
            pedidos.shutdownNow();
        }
    }

    /** Lee como la ferreteria del pedido y devuelve el aviso con el que termino (el hijo de prueba sale como danado). */
    private static String leerComo(long ferreteria, LectorPdf lector, byte[] pdf) {
        TenantContext.setCurrentFerreteriaId(ferreteria);
        try {
            lector.leer(pdf);
            return "leyó";
        } catch (BadRequestException e) {
            return e.getMessage();
        } finally {
            TenantContext.clear();
        }
    }

    private static long procesosHijosVivos() {
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
    }

    private static void esperarSinProcesosHijos() throws InterruptedException {
        long hasta = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (procesosHijosVivos() > 0) {
            assertThat(System.nanoTime()).as("murio el proceso que lee el PDF").isLessThan(hasta);
            Thread.sleep(20);
        }
    }

    /** Los archivos temporales que deja {@link LectorPdf} mientras lee. */
    private static long temporales() throws IOException {
        try (Stream<Path> archivos = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return archivos.filter(archivo -> archivo.getFileName().toString().startsWith("lista-")).count();
        }
    }

    private static void esperarUnProcesoHijo() throws InterruptedException {
        long hasta = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (procesosHijosVivos() == 0) {
            assertThat(System.nanoTime()).as("arranco el proceso que lee el PDF").isLessThan(hasta);
            Thread.sleep(20);
        }
    }
}
