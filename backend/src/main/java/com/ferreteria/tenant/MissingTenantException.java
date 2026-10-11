package com.ferreteria.tenant;

/** Se lanza cuando se necesita la ferreteria del request y no hay ninguna resuelta. */
public class MissingTenantException extends RuntimeException {

    public MissingTenantException() {
        super("No hay una ferreteria resuelta en el contexto actual");
    }
}
