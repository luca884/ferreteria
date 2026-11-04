package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import java.math.BigDecimal;

/** Un usuario de la ferretería. Nunca lleva el hash de la contraseña. */
public record UsuarioResponse(Long id, String nombre, String email, Rol rol, boolean activo, BigDecimal topeDescuento,
                              String telefono) {

    public static UsuarioResponse de(Usuario u) {
        return new UsuarioResponse(u.getId(), u.getNombre(), u.getEmail(), u.getRol(), u.isActivo(),
                u.getTopeDescuento(), u.getTelefono());
    }
}
