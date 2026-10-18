package com.ferreteria.clientes.web;

import jakarta.validation.constraints.NotNull;

/** Desactivar o reactivar un cliente (CL-003). */
public record CambiarActivoRequest(@NotNull(message = "Falta indicar si el cliente está activo.") Boolean activo) {}
