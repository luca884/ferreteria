package com.ferreteria.plataforma.web;

import jakarta.validation.constraints.NotNull;

/** Poner ({@code true}) o sacar ({@code false}) la marca «de prueba» de una ferretería. */
public record CambiarDePruebaRequest(
        @NotNull(message = "Falta indicar si la ferretería es de prueba.") Boolean dePrueba) {}
