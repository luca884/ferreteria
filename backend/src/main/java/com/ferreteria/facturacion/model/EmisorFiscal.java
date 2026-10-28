package com.ferreteria.facturacion.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * La ferretería como emisora, copiada en el registro fiscal al vender (los datos de CU-01 paso 1 que la factura imprime).
 *
 * @param cuit la CUIT como la guarda la ferretería, con guiones (30-00000001-5)
 * @param razonSocial la razón social
 * @param condicionIva la condición frente al IVA (decide la letra)
 * @param domicilioComercial el domicilio comercial
 * @param ingresosBrutos el número de Ingresos Brutos
 * @param inicioActividades el inicio de actividades
 */
public record EmisorFiscal(String cuit, String razonSocial, CondicionIvaEmisor condicionIva, String domicilioComercial,
        String ingresosBrutos, LocalDate inicioActividades) {

    public EmisorFiscal {
        Objects.requireNonNull(cuit, "Falta la CUIT del emisor.");
        Objects.requireNonNull(razonSocial, "Falta la razón social del emisor.");
        Objects.requireNonNull(condicionIva, "Falta la condición frente al IVA del emisor.");
        Objects.requireNonNull(domicilioComercial, "Falta el domicilio comercial del emisor.");
        Objects.requireNonNull(ingresosBrutos, "Falta el número de Ingresos Brutos del emisor.");
        Objects.requireNonNull(inicioActividades, "Falta el inicio de actividades del emisor.");
    }

    /** La CUIT como la pide WSFEv1 ({@code Auth.Cuit}): los 11 dígitos, sin guiones. */
    public long cuitNumerica() {
        return Long.parseLong(cuit.replace("-", ""));
    }
}
