package com.ferreteria.catalogo.service.lectura;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * El proceso aparte que lee un PDF para {@link LectorPdf}: saca las palabras con su posicion (PDFBox) y las escribe en
 * un archivo. Corre en su propia JVM, con memoria y tiempo propios, porque un PDF armado a proposito puede agotar la
 * memoria o la CPU de quien lo lee. Dice como le fue con el codigo de salida.
 *
 * <p>Argumentos: el PDF, el archivo de las palabras, el tope de palabras y los segundos que tiene para leer.
 */
public final class ExtractorPdf {

    static final int SALIDA_BIEN = 0;
    /** El que la JVM usa al quedarse sin memoria con {@code -XX:+ExitOnOutOfMemoryError}. */
    static final int SALIDA_SIN_MEMORIA = 3;
    static final int SALIDA_CON_CONTRASENA = 10;
    static final int SALIDA_DANADO = 11;
    static final int SALIDA_DEMASIADO_GRANDE = 12;

    private ExtractorPdf() {
    }

    public static void main(String[] args) {
        Path pdf = Path.of(args[0]);
        Path palabras = Path.of(args[1]);
        int maximo = Integer.parseInt(args[2]);
        // Si el que lo lanzo no lo mata a tiempo (porque se cayo), se termina solo.
        cortarA(Long.parseLong(args[3]) * 2);
        System.exit(extraer(pdf, palabras, maximo));
    }

    private static int extraer(Path pdf, Path destino, int maximo) {
        try (PDDocument documento = Loader.loadPDF(pdf.toFile())) {
            Extractor extractor = new Extractor(maximo);
            extractor.getText(documento);
            escribir(extractor.palabras, destino);
            return SALIDA_BIEN;
        } catch (DemasiadasPalabras e) {
            return SALIDA_DEMASIADO_GRANDE;
        } catch (InvalidPasswordException e) {
            return SALIDA_CON_CONTRASENA;
        } catch (IOException | RuntimeException e) {
            return SALIDA_DANADO;
        }
    }

    private static void cortarA(long segundos) {
        Thread reloj = new Thread(() -> {
            try {
                Thread.sleep(segundos * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(SALIDA_DEMASIADO_GRANDE);
        });
        reloj.setDaemon(true);
        reloj.start();
    }

    private static void escribir(List<PalabraPdf> palabras, Path destino) throws IOException {
        try (DataOutputStream salida = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(destino)))) {
            salida.writeInt(palabras.size());
            for (PalabraPdf palabra : palabras) {
                salida.writeInt(palabra.pagina());
                salida.writeDouble(palabra.izquierda());
                salida.writeDouble(palabra.derecha());
                salida.writeDouble(palabra.base());
                salida.writeDouble(palabra.tamano());
                byte[] texto = palabra.texto().getBytes(StandardCharsets.UTF_8);
                salida.writeInt(texto.length);
                salida.write(texto);
            }
        }
    }

    /** Las palabras que escribio {@link #escribir}; mas de {@code maximo} es un archivo que no escribio este proceso. */
    static List<PalabraPdf> leer(Path origen, int maximo) throws IOException {
        try (DataInputStream entrada = new DataInputStream(new BufferedInputStream(Files.newInputStream(origen)))) {
            int cantidad = entrada.readInt();
            if (cantidad < 0 || cantidad > maximo) {
                throw new IOException("Cantidad de palabras invalida: " + cantidad);
            }
            List<PalabraPdf> palabras = new ArrayList<>(cantidad);
            for (int i = 0; i < cantidad; i++) {
                int pagina = entrada.readInt();
                double izquierda = entrada.readDouble();
                double derecha = entrada.readDouble();
                double base = entrada.readDouble();
                double tamano = entrada.readDouble();
                byte[] texto = new byte[entrada.readInt()];
                entrada.readFully(texto);
                palabras.add(new PalabraPdf(pagina, izquierda, derecha, base, tamano,
                    new String(texto, StandardCharsets.UTF_8)));
            }
            return palabras;
        }
    }

    private static final class DemasiadasPalabras extends RuntimeException {
        private DemasiadasPalabras() {
            super(null, null, false, false);
        }
    }

    /**
     * Junta las palabras de cada pagina con su posicion. PDFBox entrega el texto de a palabras ya ordenado por posicion;
     * una de ellas puede traer espacios adentro, y se parte en esos espacios.
     */
    private static final class Extractor extends PDFTextStripper {
        private final List<PalabraPdf> palabras = new ArrayList<>();
        private final int maximo;

        private Extractor(int maximo) {
            this.maximo = maximo;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String texto, List<TextPosition> posiciones) {
            List<TextPosition> palabra = new ArrayList<>();
            for (TextPosition posicion : posiciones) {
                if (posicion.getUnicode().isBlank()) {
                    agregar(palabra);
                    palabra = new ArrayList<>();
                } else {
                    palabra.add(posicion);
                }
            }
            agregar(palabra);
        }

        private void agregar(List<TextPosition> glifos) {
            if (glifos.isEmpty()) {
                return;
            }
            if (palabras.size() >= maximo) {
                throw new DemasiadasPalabras();
            }
            TextPosition primero = glifos.get(0);
            TextPosition ultimo = glifos.get(glifos.size() - 1);
            StringBuilder texto = new StringBuilder();
            glifos.forEach(glifo -> texto.append(glifo.getUnicode()));
            palabras.add(new PalabraPdf(getCurrentPageNo(), primero.getXDirAdj(),
                ultimo.getXDirAdj() + ultimo.getWidthDirAdj(), primero.getYDirAdj(), primero.getFontSizeInPt(),
                texto.toString()));
        }
    }
}
