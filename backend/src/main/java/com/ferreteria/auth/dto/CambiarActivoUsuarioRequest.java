package com.ferreteria.auth.dto;

import jakarta.validation.constraints.NotNull;

/** Desactivar o reactivar un usuario (CU-02). */
public record CambiarActivoUsuarioRequest(@NotNull(message = "Falta indicar si el usuario está activo.") Boolean activo) {}
