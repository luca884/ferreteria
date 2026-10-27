package com.ferreteria.catalogo.service.lectura;

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

    /** Tarda dos segundos y sale como si el PDF estuviera danado. */
    public static final class Tarda {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(2_000);
            System.exit(ExtractorPdf.SALIDA_DANADO);
        }
    }
}
