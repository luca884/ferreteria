package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProveedorRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres.")
        String nombre,

        /** Con o sin guiones; el servicio valida el digito verificador y lo guarda como 30-12345678-1. */
        String cuit,

        /** null significa activo en el alta y "conservar el estado actual" en la edicion. */
        Boolean activo) {
}
