package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProveedorRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres.")
        String nombre,

        /** Con o sin guiones; el servicio valida el digito verificador y lo guarda como 30-12345678-1. */
        String cuit,

        /** null significa activo en el alta y "conservar el estado actual" en la edicion. */
        Boolean activo,

        /** La persona de contacto (mejoras-ferreteros-bloque-1, S4). Opcional, como el telefono, WhatsApp y correo. */
        @Size(max = 100, message = "El contacto admite hasta 100 caracteres.")
        String contacto,

        @Size(max = 30, message = "El teléfono admite hasta 30 caracteres.")
        String telefono,

        @Size(max = 30, message = "El WhatsApp admite hasta 30 caracteres.")
        String whatsapp,

        @Email(message = "El correo no es válido.")
        @Size(max = 120, message = "El correo admite hasta 120 caracteres.")
        String email) {

    /** Sin datos de contacto. */
    public ProveedorRequest(String nombre, String cuit, Boolean activo) {
        this(nombre, cuit, activo, null, null, null, null);
    }
}
