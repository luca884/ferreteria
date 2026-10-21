package com.ferreteria.plataforma.web;

import com.ferreteria.ferreteria.model.CondicionIva;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Razón social y condición de IVA. El CUIT no se edita: si el cuerpo trae uno, se ignora. */
public record EditarFerreteriaRequest(
        @NotBlank(message = "La razón social es obligatoria.")
        @Size(max = 150, message = "La razón social admite hasta 150 caracteres.")
        String razonSocial,

        @NotNull(message = "Elegí la condición de IVA.")
        CondicionIva condicionIva) {}
