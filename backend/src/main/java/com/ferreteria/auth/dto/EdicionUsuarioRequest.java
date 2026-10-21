package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.service.DatosEdicion;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Edición de un usuario de la ferretería: nombre, email y rol. La contraseña tiene su propio endpoint. */
public record EdicionUsuarioRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre admite hasta 100 caracteres.")
        String nombre,

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no es válido.")
        @Size(max = 150, message = "El email admite hasta 150 caracteres.")
        String email,

        @NotNull(message = "Elegí el rol.")
        Rol rol) {

    /** El email llega con los espacios que el usuario haya escrito: se recorta antes de validar. */
    public EdicionUsuarioRequest{
        email = email == null ? null : email.trim();
    }

    public DatosEdicion aDatos() {
        return new DatosEdicion(nombre, email, rol);
    }
}
