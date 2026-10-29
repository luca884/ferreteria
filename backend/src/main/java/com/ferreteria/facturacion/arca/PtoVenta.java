package com.ferreteria.facturacion.arca;

import java.time.LocalDate;

/**
 * Un punto de venta de la CUIT, como lo devuelve {@code FEParamGetPtosVenta} ({@code PtoVenta} del WSDL).
 *
 * @param nro {@code Nro}, Int
 * @param emisionTipo {@code EmisionTipo}: «CAE» o «CAEA» (el motor pide CAE); null si no vino
 * @param bloqueado {@code Bloqueado} «S»; con «N» o sin el campo, false
 * @param fchBaja {@code FchBaja} yyyymmdd; null si no está dado de baja (ARCA manda «NULL», inferido)
 */
public record PtoVenta(int nro, String emisionTipo, boolean bloqueado, LocalDate fchBaja) {
}
