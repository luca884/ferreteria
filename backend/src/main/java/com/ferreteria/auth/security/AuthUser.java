package com.ferreteria.auth.security;

import com.ferreteria.auth.model.Rol;

/** Principal del SecurityContext, armado a partir del JWT. */
public record AuthUser(Long userId, String email, Rol rol, Long ferreteriaId) {
}
