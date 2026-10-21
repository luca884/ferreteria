package com.ferreteria.auth.service;

/**
 * El email y la contraseña son correctos, pero la ferretería del usuario está suspendida. Es un 401 como cualquier otra
 * falla de login (el handler de {@link CredencialesInvalidasException} la atiende) con un mensaje que lo explica. Solo se
 * lanza después de comprobar la contraseña y que el usuario esté activo: así no revela el estado a quien no la sabe.
 */
public class FerreteriaSuspendidaException extends CredencialesInvalidasException {

    public static final String MENSAJE = "Tu ferretería está suspendida. Contactá al proveedor.";

    public FerreteriaSuspendidaException() {
        super(MENSAJE);
    }
}
