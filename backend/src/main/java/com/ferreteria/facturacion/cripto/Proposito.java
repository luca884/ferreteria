package com.ferreteria.facturacion.cripto;

/**
 * Para qué es un dato cifrado: va en el AAD (ver {@link CifradorDeClaves}), así la clave privada de una credencial no se
 * descifra como si fuera la de la CA del simulador, ni un ticket de acceso como una clave, ni al revés.
 */
public enum Proposito {
    /** La clave privada de la credencial de ARCA de una ferretería (la del pedido de certificado). */
    CLAVE_DE_CREDENCIAL,
    /** La clave privada de la CA del simulador de una ferretería (S5 y S15). */
    CLAVE_DE_CA_SIMULADOR,
    /**
     * El token y el sign del TA de WSAA de una ferretería (rama 6, S2), con la huella de la credencial que lo pidió: un TA no
     * se descifra con la huella de otra credencial.
     */
    TICKET_DE_ACCESO
}
