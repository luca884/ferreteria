package com.ferreteria.auth.dto;

/**
 * El código de la app del super admin: el tokenTotp que dio el login y los 6 números que muestra Google Authenticator
 * (totp-super-admin, S1). Se validan en {@code SegundoFactorService}, con los mensajes del contrato.
 */
public record VerificarTotpRequest(String tokenTotp, String codigo) {
}
