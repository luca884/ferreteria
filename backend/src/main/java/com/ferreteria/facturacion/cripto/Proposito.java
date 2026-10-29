package com.ferreteria.facturacion.cripto;

/**
 * Para qué es un dato cifrado: va en el AAD (ver {@link CifradorDeClaves}), así la clave privada de una credencial no se
 * descifra como si fuera la de la CA del simulador, ni al revés.
 */
public enum Proposito {
    /** La clave privada de la credencial de ARCA de una ferretería (la del pedido de certificado). */
    CLAVE_DE_CREDENCIAL,
    /** La clave privada de la CA del simulador de una ferretería (S5 y S15). */
    CLAVE_DE_CA_SIMULADOR
}
