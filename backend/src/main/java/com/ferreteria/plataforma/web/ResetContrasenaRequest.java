package com.ferreteria.plataforma.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** La contraseña nueva que el super admin le pone al Dueño de una ferretería. */
public record ResetContrasenaRequest(
        @NotNull(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        String password) {}
