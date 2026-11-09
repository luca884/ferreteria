package com.ferreteria.catalogo.model;

/** Unidad de venta del articulo. Lista fija: cualquier otro valor se rechaza. */
public enum Unidad {
    UNIDAD(""),
    METRO("m"),
    KILO("kg"),
    LITRO("l");

    private final String abreviatura;

    Unidad(String abreviatura) {
        this.abreviatura = abreviatura;
    }

    /**
     * Lo que va despues de un importe para decir por que unidad es («m», «kg», «l»); por unidad, vacio. Los mismos
     * textos que el front.
     */
    public String abreviatura() {
        return abreviatura;
    }
}
