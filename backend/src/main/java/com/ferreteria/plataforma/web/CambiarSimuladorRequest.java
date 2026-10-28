package com.ferreteria.plataforma.web;

import jakarta.validation.constraints.NotNull;

/** Asignar ({@code true}, con el punto de venta 1) o quitar ({@code false}) el simulador de ARCA. */
public record CambiarSimuladorRequest(
        @NotNull(message = "Falta indicar si la ferretería usa el simulador.") Boolean simulador) {}
