package com.ferreteria.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cambio de la contraseña propia: pide la actual y la nueva.
 *
 * <p>El campo se llama {@code passwordNueva}, así que un 400 de este endpoint responde en {@code errors.passwordNueva}.
 * El reseteo que un Dueño le hace a un usuario ({@link ResetContrasenaRequest}) responde en {@code errors.password}
 * porque su campo se llama así. Cada clave sale de su DTO: no hay una «correcta» única y no se unifican.
 */
public record CambiarContrasenaRequest(
        @NotBlank(message = "Escribí tu contraseña actual.")
        String passwordActual,

        @NotNull(message = "Escribí la contraseña nueva.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        String passwordNueva) {}
