package com.ferreteria.catalogo.model;

/** El archivo subido. El nombre pierde la ruta, es "lista" si queda vacio y se corta a 255 code points. */
public record ArchivoLista(String nombre, byte[] contenido) {

    private static final String NOMBRE_POR_DEFECTO = "lista";
    private static final int MAXIMO = 255;

    public ArchivoLista {
        nombre = normalizar(nombre);
    }

    private static String normalizar(String original) {
        if (original == null) {
            return NOMBRE_POR_DEFECTO;
        }
        String sinRuta = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).strip();
        if (sinRuta.isEmpty()) {
            return NOMBRE_POR_DEFECTO;
        }
        if (sinRuta.codePointCount(0, sinRuta.length()) <= MAXIMO) {
            return sinRuta;
        }
        return sinRuta.substring(0, sinRuta.offsetByCodePoints(0, MAXIMO));
    }
}
