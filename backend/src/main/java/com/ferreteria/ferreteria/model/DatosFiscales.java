package com.ferreteria.ferreteria.model;

import java.time.LocalDate;

/**
 * Los datos del emisor que la factura imprime (CU-01 paso 1, RG 1415): los edita el Dueño, salvo el CUIT, que se carga en
 * el alta y no cambia nunca. Domicilio, inicio de actividades e Ingresos Brutos son null hasta que el Dueño los carga.
 */
public record DatosFiscales(
        String razonSocial,
        String cuit,
        CondicionIva condicionIva,
        String domicilioComercial,
        LocalDate inicioActividades,
        String ingresosBrutos) {

    public static DatosFiscales de(Ferreteria ferreteria) {
        return new DatosFiscales(ferreteria.getRazonSocial(), ferreteria.getCuit(), ferreteria.getCondicionIva(),
                ferreteria.getDomicilioComercial(), ferreteria.getInicioActividades(), ferreteria.getIngresosBrutos());
    }
}
