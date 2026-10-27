package com.ferreteria.catalogo.service.lectura;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Procesos de mentira en lugar de {@link ExtractorPdf}, para probar como {@link LectorPdf} vigila al que lee. */
public final class HijosDePrueba {

    private HijosDePrueba() {
    }

    /** No termina nunca. */
    public static final class NoTermina {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** Se cae con un error que nadie ataja, como un arbol de paginas muy profundo. */
    public static final class SeCae {
        public static void main(String[] args) {
            throw new StackOverflowError();
        }
    }

    /** Escribe como palabras cuantos procesadores ve y con que prioridad ({@code nice}) corre, y sale bien. */
    public static final class DicePrioridadYProcesadores {
        public static void main(String[] args) throws Exception {
            Process ps = new ProcessBuilder("ps", "-o", "nice=", "-p", String.valueOf(ProcessHandle.current().pid()))
                .start();
            String prioridad = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor();
            ExtractorPdf.escribir(List.of(
                new PalabraPdf(1, 10, 90, 700, 10, "procesadores=" + Runtime.getRuntime().availableProcessors()),
                new PalabraPdf(1, 10, 90, 680, 10, "prioridad=" + prioridad)), Path.of(args[1]));
            System.exit(ExtractorPdf.SALIDA_BIEN);
        }
    }

    /** Tarda dos segundos y sale como si el PDF estuviera danado. */
    public static final class Tarda {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(2_000);
            System.exit(ExtractorPdf.SALIDA_DANADO);
        }
    }
}
