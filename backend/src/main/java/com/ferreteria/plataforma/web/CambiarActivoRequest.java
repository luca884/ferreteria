package com.ferreteria.plataforma.web;

import jakarta.validation.constraints.NotNull;

/** Suspender ({@code false}) o reactivar ({@code true}) una ferretería. */
public record CambiarActivoRequest(@NotNull(message = "Falta indicar si la ferretería está activa.") Boolean activo) {}
