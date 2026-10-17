package com.ferreteria.catalogo.model;

/** El valor de una operacion masiva no es valido para su modo. Excepcion de dominio: el servicio la traduce a 400. */
public class ValorMasivoInvalidoException extends IllegalArgumentException {

    public ValorMasivoInvalidoException(String message) {
        super(message);
    }
}
