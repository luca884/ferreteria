package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProveedorRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        String nombre,

        @Pattern(regexp = "\\d{2}-\\d{8}-\\d", message = "El CUIT debe tener el formato 30-12345678-9")
        String cuit,

        /** null significa activo en el alta y "conservar el estado actual" en la edicion. */
        Boolean activo) {
}
