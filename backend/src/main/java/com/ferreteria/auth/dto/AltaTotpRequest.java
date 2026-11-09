package com.ferreteria.auth.dto;

/** El alta del código de la app: el tokenTotp que dio el login (totp-super-admin, S2). */
public record AltaTotpRequest(String tokenTotp) {
}
