package com.ferreteria.facturacion.model;

/**
 * Lo que el cerrojo del ambiente necesita saber de los registros fiscales de una ferretería: si tiene alguno abierto (en un
 * estado que no sea AUTORIZADO, RECHAZADO, FUERA_DE_FECHA ni SIN_FACTURA, de cualquier ambiente) y si tiene alguno de
 * homologación o producción (en cualquier estado).
 */
public record RegistrosFiscales(boolean hayAbiertos, boolean hayDeHomologacionOProduccion) {

    public static final RegistrosFiscales NINGUNO = new RegistrosFiscales(false, false);
}
