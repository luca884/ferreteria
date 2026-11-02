package com.ferreteria.auth.service;

/** 429 del login: el email o la IP pasaron su límite de intentos. Lleva los segundos que faltan para poder probar. */
public class DemasiadosIntentosException extends RuntimeException {

    private final long segundos;

    public DemasiadosIntentosException(long segundos) {
        super(mensaje(segundos));
        this.segundos = segundos;
    }

    public long segundos() {
        return segundos;
    }

    private static String mensaje(long segundos) {
        long minutos = Math.max(1, (segundos + 59) / 60);
        return "Demasiados intentos. Probá de nuevo en " + minutos + (minutos == 1 ? " minuto." : " minutos.");
    }
}
