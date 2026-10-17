package com.ferreteria.auth.security;

import com.ferreteria.auth.model.Rol;

/** Principal del SecurityContext, armado a partir del JWT. */
public record AuthUser(Long userId, String email, Rol rol, Long ferreteriaId) {

    /** Solo el Dueno ve costo, margen y la lista del proveedor; cualquier otro rol recibe la vista sin esos datos. */
    public boolean veCostos() {
        return rol == Rol.DUENO;
    }
}
