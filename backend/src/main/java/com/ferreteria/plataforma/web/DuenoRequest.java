package com.ferreteria.plataforma.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** El primer Dueño de la ferretería nueva. Sus claves de error salen como {@code dueno.nombre}, {@code dueno.email}... */
public record DuenoRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre admite hasta 100 caracteres.")
        String nombre,

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no es válido.")
        @Size(max = 150, message = "El email admite hasta 150 caracteres.")
        String email,

        @NotNull(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        String password) {

    /** El email llega con los espacios que el usuario haya escrito: se recorta antes de validar. */
    public DuenoRequest {
        email = email == null ? null : email.trim();
    }
}
