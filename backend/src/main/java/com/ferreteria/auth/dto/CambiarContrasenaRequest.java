package com.ferreteria.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cambio de la contraseña propia: pide la actual y la nueva. */
public record CambiarContrasenaRequest(
        @NotBlank(message = "Escribí tu contraseña actual.")
        String passwordActual,

        @NotNull(message = "Escribí la contraseña nueva.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        String passwordNueva) {}
