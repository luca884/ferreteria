package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;

/** Lo que el frontend necesita para mostrar la sesion: quien sos y en que ferreteria estas. */
public record SesionResponse(Long userId, String nombre, String email, Rol rol,
                             Long ferreteriaId, String ferreteriaNombre) {
}
