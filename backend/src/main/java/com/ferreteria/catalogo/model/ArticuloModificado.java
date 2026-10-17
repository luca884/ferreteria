package com.ferreteria.catalogo.model;

/**
 * El articulo cambio desde que se leyo (bloqueo optimista, lote 5b): otro cambio confirmo antes. Es el texto del 409
 * del PUT del articulo, del PUT de ajustes y de la red global del manejador de errores.
 */
public final class ArticuloModificado {

    public static final String MENSAJE = "Otro cambio modificó este artículo. Recargá y volvé a intentar.";

    /** El 400 de un PUT del artículo o de ajustes que no trae la version que se leyo (decision 48, lote 6). */
    public static final String FALTA_VERSION = "Falta la versión del artículo. Recargá y volvé a intentar.";

    private ArticuloModificado() {
    }
}
