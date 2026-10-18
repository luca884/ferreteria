package com.ferreteria.clientes.model;

/** El documento no pasa la validación. El mensaje está listo para mostrárselo al usuario. */
public class DocumentoInvalidoException extends RuntimeException {

    public DocumentoInvalidoException(String message) {
        super(message);
    }
}
