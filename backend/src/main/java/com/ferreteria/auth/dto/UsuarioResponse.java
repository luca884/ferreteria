package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;

/** Un usuario de la ferretería. Nunca lleva el hash de la contraseña. */
public record UsuarioResponse(Long id, String nombre, String email, Rol rol, boolean activo) {

    public static UsuarioResponse de(Usuario u) {
        return new UsuarioResponse(u.getId(), u.getNombre(), u.getEmail(), u.getRol(), u.isActivo());
    }
}
