package com.ferreteria.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * La contraseña nueva que el Dueño le pone a un usuario de su ferretería.
 *
 * <p>El campo se llama {@code password}, así que un 400 de este endpoint responde en {@code errors.password}. El otro
 * endpoint que cambia una contraseña propia ({@code PUT /api/auth/password}, {@link CambiarContrasenaRequest}) responde
 * en {@code errors.passwordNueva} porque su campo se llama así. Las dos claves son correctas: salen del DTO de cada
 * request. No se unifican, porque cambiar una rompería a quien ya la consume.
 */
public record ResetContrasenaRequest(
        @NotNull(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        String password) {}
