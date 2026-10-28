package com.ferreteria.plataforma.web;

import jakarta.validation.constraints.NotNull;

/** Prender ({@code true}) o apagar ({@code false}) la facturación de una ferretería. */
public record CambiarFacturacionRequest(
        @NotNull(message = "Falta indicar si la facturación está habilitada.") Boolean habilitada) {}
