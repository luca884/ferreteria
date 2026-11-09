package com.ferreteria.auth.dto;

import java.util.List;

/**
 * El alta del código de la app (totp-super-admin, S2): {@code clave}, el secreto en Base32 sin relleno, para cargarlo a mano;
 * {@code uri}, el {@code otpauth://} que lee Google Authenticator; y {@code qr}, la matriz del QR de la uri en filas de
 * '0' y '1', como el {@code qrModulos} de la factura. Sale solo mientras el alta está pendiente.
 */
public record AltaTotpResponse(String clave, String uri, List<String> qr) {
}
