package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.service.DatosUsuario;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Alta de un usuario de la ferretería (CU-02). La ferretería sale del JWT, no del cuerpo. */
public record UsuarioRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre admite hasta 100 caracteres.")
        String nombre,

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no es válido.")
        @Size(max = 150, message = "El email admite hasta 150 caracteres.")
        String email,

        @NotNull(message = "Elegí el rol.")
        Rol rol,

        @NotNull(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        String password,

        /** CU-07: vacío = sin tope. Para un Dueño se descarta. */
        @DecimalMin(value = "0", message = TopeDescuento.MENSAJE)
        @DecimalMax(value = "99.99", message = TopeDescuento.MENSAJE)
        @Digits(integer = 2, fraction = 2, message = TopeDescuento.MENSAJE)
        BigDecimal topeDescuento,

        /** Opcional (mejoras-ferreteros-bloque-1, S5). */
        @Size(max = 30, message = "El teléfono admite hasta 30 caracteres.")
        String telefono) {

    /** El email llega con los espacios que el usuario haya escrito: se recorta antes de validar. */
    public UsuarioRequest{
        email = email == null ? null : email.trim();
    }

    public DatosUsuario aDatos() {
        return new DatosUsuario(nombre, email, rol, password, topeDescuento, telefono);
    }
}
