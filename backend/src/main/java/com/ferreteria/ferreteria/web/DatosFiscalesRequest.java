package com.ferreteria.ferreteria.web;

import com.ferreteria.ferreteria.model.CondicionIva;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Los datos fiscales que guarda el Dueño (CU-01 paso 1). El CUIT no se edita: si el cuerpo trae uno, se ignora. Que el
 * inicio de actividades no sea futuro lo valida el servicio, con la fecha de Buenos Aires.
 */
public record DatosFiscalesRequest(
        @NotBlank(message = "La razón social es obligatoria.")
        @Size(max = 150, message = "La razón social admite hasta 150 caracteres.")
        String razonSocial,

        @NotNull(message = "Elegí la condición de IVA.")
        CondicionIva condicionIva,

        @Size(max = 200, message = "El domicilio admite hasta 200 caracteres.")
        String domicilioComercial,

        LocalDate inicioActividades,

        @Size(max = 50, message = "Ingresos Brutos admite hasta 50 caracteres.")
        String ingresosBrutos) {}
