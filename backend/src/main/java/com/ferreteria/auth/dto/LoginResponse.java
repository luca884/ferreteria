package com.ferreteria.auth.dto;

/**
 * La respuesta del login y del código de la app. Un Dueño o un Vendedor reciben {@code {token, sesion}} con
 * {@code segundoPaso} y {@code tokenTotp} en null, como siempre (totp-super-admin, S8). Un SUPER_ADMIN con la contraseña
 * bien recibe {@code token} y {@code sesion} en null, el {@code segundoPaso} que le toca y el {@code tokenTotp} para
 * {@code /api/auth/totp/...}; con el código bueno, {@code {token, sesion}} (S1).
 */
public record LoginResponse(String token, SesionResponse sesion, SegundoPaso segundoPaso, String tokenTotp) {

    /** La sesión: token y datos del usuario. */
    public static LoginResponse sesion(String token, SesionResponse sesion) {
        return new LoginResponse(token, sesion, null, null);
    }

    /** El super admin pasó la contraseña: falta el código de la app. */
    public static LoginResponse segundoPaso(SegundoPaso segundoPaso, String tokenTotp) {
        return new LoginResponse(null, null, segundoPaso, tokenTotp);
    }
}
