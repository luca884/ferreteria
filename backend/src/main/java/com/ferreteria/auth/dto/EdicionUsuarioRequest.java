package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.service.DatosEdicion;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Edición de un usuario de la ferretería: nombre, email, rol y tope de descuento. La contraseña tiene su propio endpoint. */
public record EdicionUsuarioRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre admite hasta 100 caracteres.")
        String nombre,

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no es válido.")
        @Size(max = 150, message = "El email admite hasta 150 caracteres.")
        String email,

        @NotNull(message = "Elegí el rol.")
        Rol rol,

        /** CU-07: vacío = sin tope. Para un Dueño se descarta. */
        @DecimalMin(value = "0", message = TopeDescuento.MENSAJE)
        @DecimalMax(value = "99.99", message = TopeDescuento.MENSAJE)
        @Digits(integer = 2, fraction = 2, message = TopeDescuento.MENSAJE)
        BigDecimal topeDescuento,

        /** Opcional (mejoras-ferreteros-bloque-1, S5); ausente o en blanco lo borra. */
        @Size(max = 30, message = "El teléfono admite hasta 30 caracteres.")
        String telefono) {

    /** El email llega con los espacios que el usuario haya escrito: se recorta antes de validar. */
    public EdicionUsuarioRequest{
        email = email == null ? null : email.trim();
    }

    public DatosEdicion aDatos() {
        return new DatosEdicion(nombre, email, rol, topeDescuento, telefono);
    }
}
