package com.ferreteria.auth.dto;

/** Lo que le falta al super admin después de la contraseña (totp-super-admin, S1 y S2). */
public enum SegundoPaso {
    /** El código de la app no está activado: la pantalla muestra el QR ({@code /api/auth/totp/alta}) y pide el primer código. */
    ALTA,
    /** El código de la app está activado: la pantalla pide el código. */
    CODIGO
}
