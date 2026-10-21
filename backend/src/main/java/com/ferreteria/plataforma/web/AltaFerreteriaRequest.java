package com.ferreteria.plataforma.web;

import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.plataforma.service.DatosAlta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de una ferretería con su primer Dueño. El CUIT se valida en el servicio (formato, prefijo y verificador). */
public record AltaFerreteriaRequest(
        @NotBlank(message = "La razón social es obligatoria.")
        @Size(max = 150, message = "La razón social admite hasta 150 caracteres.")
        String razonSocial,

        @NotBlank(message = "El CUIT es obligatorio.")
        String cuit,

        @NotNull(message = "Elegí la condición de IVA.")
        CondicionIva condicionIva,

        @NotNull(message = "Faltan los datos del Dueño.")
        @Valid
        DuenoRequest dueno) {

    public DatosAlta aDatos() {
        return new DatosAlta(razonSocial, cuit, condicionIva,
                new DatosAlta.Dueno(dueno.nombre(), dueno.email(), dueno.password()));
    }
}
