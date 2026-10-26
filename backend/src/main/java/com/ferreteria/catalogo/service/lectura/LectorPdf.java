package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * Lee un PDF con texto (PDFBox): saca las palabras con su posicion y {@link TablaDePdf} arma las filas y las columnas.
 * Un PDF es una sola hoja, la 0, con todas las paginas seguidas. Un PDF escaneado no tiene texto y no se puede leer.
 *
 * <p>Como el Excel con POI, tiene topes para no agotar la memoria del servidor compartido: antes de leer, lo que se
 * inflan los streams que hacen falta para el texto (contenido de las paginas, formularios, fuentes; no las imagenes,
 * que no se descomprimen) y, mientras lee, la cantidad de palabras.
 */
@Component
public class LectorPdf implements LectorDeLista {

    static final int MAXIMO_PALABRAS = 500_000;
    static final long MAXIMO_INFLADO_BYTES = 64L * 1024 * 1024;

    private static final String HOJA = "PDF";
    private static final String ARCHIVO_DANADO = "No se pudo leer el archivo. Verificá que no esté dañado.";
    private static final String SIN_TEXTO =
        "El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no sea escaneado.";
    /** Filtros de imagen: el texto nunca los necesita. */
    private static final Set<COSName> FILTROS_DE_IMAGEN = Set.of(COSName.DCT_DECODE, COSName.JPX_DECODE,
        COSName.JBIG2_DECODE, COSName.CCITTFAX_DECODE);

    private final int maximoPalabras;
    private final long maximoInflado;

    public LectorPdf() {
        this(MAXIMO_PALABRAS, MAXIMO_INFLADO_BYTES);
    }

    /** Con otros topes, para probarlos sin armar archivos enormes. */
    LectorPdf(int maximoPalabras, long maximoInflado) {
        this.maximoPalabras = maximoPalabras;
        this.maximoInflado = maximoInflado;
    }

    @Override
    public LecturaLista leer(byte[] contenido) {
        List<PalabraPdf> palabras;
        try (PDDocument documento = Loader.loadPDF(contenido)) {
            revisarInflado(documento.getDocument());
            Extractor extractor = new Extractor(maximoPalabras);
            extractor.getText(documento);
            palabras = extractor.palabras;
        } catch (BadRequestException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw new BadRequestException("El archivo está protegido con contraseña.");
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException(ARCHIVO_DANADO);
        }
        if (palabras.isEmpty()) {
            throw new BadRequestException(SIN_TEXTO);
        }
        List<FilaLeida> filas = TablaDePdf.armar(palabras);
        return new LecturaLista(List.of(HOJA), filas);
    }

    /**
     * Infla, sin guardar lo que sale, cada stream que no es una imagen, y corta apenas la suma pasa el tope. Primero
     * los objetos sueltos (entre ellos los object streams) y despues los que viven adentro de un object stream: asi
     * ninguno se infla sin tope al buscar un objeto suyo.
     */
    private void revisarInflado(COSDocument documento) throws IOException {
        Map<COSObjectKey, Long> xref = documento.getXrefTable();
        List<COSObjectKey> claves = new ArrayList<>(xref.keySet());
        claves.sort(Comparator.comparing(clave -> xref.get(clave) == null || xref.get(clave) < 0));
        long disponible = maximoInflado;
        for (COSObjectKey clave : claves) {
            COSBase objeto = documento.getObjectFromPool(clave).getObject();
            if (objeto instanceof COSStream stream && !esImagen(stream)) {
                disponible -= inflado(stream, disponible);
            }
        }
    }

    private static boolean esImagen(COSStream stream) {
        return COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))
            || filtros(stream).stream().anyMatch(FILTROS_DE_IMAGEN::contains);
    }

    private static List<COSName> filtros(COSStream stream) {
        COSBase filtros = stream.getFilters();
        if (filtros instanceof COSName nombre) {
            return List.of(nombre);
        }
        List<COSName> nombres = new ArrayList<>();
        if (filtros instanceof COSArray arreglo) {
            for (COSBase filtro : arreglo) {
                if (filtro instanceof COSName nombre) {
                    nombres.add(nombre);
                }
            }
        }
        return nombres;
    }

    /** Lo que se infla el stream, aplicando sus filtros en orden; si pasa lo disponible, la lista es muy grande. */
    private static long inflado(COSStream stream, long disponible) throws IOException {
        List<COSName> filtros = filtros(stream);
        byte[] datos = null;
        long tamano = 0;
        for (int i = 0; i < filtros.size(); i++) {
            boolean ultimo = i == filtros.size() - 1;
            ConTope salida = new ConTope(disponible, ultimo ? OutputStream.nullOutputStream() : new ByteArrayOutputStream());
            try (InputStream entrada = datos == null ? stream.createRawInputStream() : new ByteArrayInputStream(datos)) {
                FilterFactory.INSTANCE.getFilter(filtros.get(i)).decode(entrada, salida, stream, i);
            } catch (IOException e) {
                // Un filtro que no se puede aplicar no infla nada aca; si el texto lo necesita, la lectura avisa.
                return tamano;
            }
            tamano = salida.escritos;
            datos = ultimo ? null : ((ByteArrayOutputStream) salida.destino).toByteArray();
        }
        return filtros.isEmpty() ? 0 : tamano;
    }

    /** Cuenta lo que pasa y corta con «demasiado grande» apenas supera el tope. */
    private static final class ConTope extends OutputStream {
        private final long tope;
        private final OutputStream destino;
        private long escritos;

        private ConTope(long tope, OutputStream destino) {
            this.tope = tope;
            this.destino = destino;
        }

        @Override
        public void write(int b) throws IOException {
            sumar(1);
            destino.write(b);
        }

        @Override
        public void write(byte[] b, int desde, int cantidad) throws IOException {
            sumar(cantidad);
            destino.write(b, desde, cantidad);
        }

        private void sumar(long cantidad) {
            escritos += cantidad;
            if (escritos > tope) {
                throw LimitesDeLista.demasiadoGrande();
            }
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
                throw LimitesDeLista.demasiadoGrande();
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
