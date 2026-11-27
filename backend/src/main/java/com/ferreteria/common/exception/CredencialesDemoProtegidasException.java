package com.ferreteria.common.exception;

/** 403 con un mensaje específico al intentar cambiar las credenciales de una cuenta demo. */
public class CredencialesDemoProtegidasException extends RuntimeException {

    public CredencialesDemoProtegidasException() {
        super("Es una cuenta de demostración: no se puede cambiar la contraseña ni el email.");
    }
}
