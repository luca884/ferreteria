package com.ferreteria.facturacion.documento;

import java.util.Map;

/**
 * La explicación en criollo de los rechazos de ARCA que más aparecen (cu-07-contingencias C1; CU-16 punto 7), para la
 * bandeja de Ventas del Dueño. El código y el texto de ARCA se muestran tal cual; esto solo dice qué quiere decir y qué
 * hacer. Son los códigos excluyentes del manual 4.8 que más aparecen y los que da el simulador; los demás (y las
 * observaciones de una factura autorizada) no tienen explicación: null.
 */
public final class ExplicacionesArca {

    private static final String SOPORTE = " Es un problema del sistema: avisale a soporte.";

    private static final Map<Integer, String> EXPLICACIONES = Map.ofEntries(
            Map.entry(10007, "ARCA no acepta el tipo de comprobante." + SOPORTE),
            Map.entry(10013, "La factura A necesita el CUIT del cliente: cargalo en Clientes y reintentá."),
            Map.entry(10015, "ARCA no acepta cómo está identificado el cliente: revisá su DNI o su CUIT en Clientes (tiene "
                    + "que existir en el padrón de ARCA) y reintentá. Desde el tope de ARCA, la venta a Consumidor Final "
                    + "necesita un cliente identificado."),
            Map.entry(10016, "El número o la fecha de la factura no son los que espera ARCA. Si la fecha de la venta ya no "
                    + "entra, reintentá con la fecha de hoy."),
            Map.entry(10023, "La suma del IVA no coincide con el IVA de la factura." + SOPORTE),
            Map.entry(10040, "El comprobante asociado no corresponde a este tipo de comprobante." + SOPORTE),
            Map.entry(10048, "El total de la factura no coincide con la suma de sus importes." + SOPORTE),
            Map.entry(10051, "El IVA de una alícuota no coincide con su base imponible." + SOPORTE),
            Map.entry(10061, "La suma de las bases imponibles no coincide con el neto gravado." + SOPORTE),
            Map.entry(10071, "La factura C no lleva IVA discriminado." + SOPORTE),
            Map.entry(10152, "La fecha de la factura es posterior a hoy y de otro mes: reintentá con la fecha de hoy."),
            // Inferido: el manual remite a un micrositio de ARCA para el umbral de la factura de crédito electrónica.
            Map.entry(10192, "Por el monto y por cómo están categorizados la ferretería y el cliente, ARCA pide una Factura "
                    + "de Crédito Electrónica MiPyME, que el sistema todavía no emite: consultalo con el contador."),
            Map.entry(10197, "La nota de crédito o de débito necesita el comprobante que ajusta." + SOPORTE),
            Map.entry(10242, "ARCA no reconoce la condición frente al IVA del cliente: revisala en Clientes y reintentá."),
            Map.entry(10243, "La condición frente al IVA del cliente no va con esta letra de factura: revisala en Clientes y "
                    + "reintentá."),
            Map.entry(10246, "Falta la condición frente al IVA del cliente: completala en Clientes y reintentá."));

    private ExplicacionesArca() {
    }

    /** La explicación del código, o null si no es uno de los conocidos. */
    public static String de(int codigo) {
        return EXPLICACIONES.get(codigo);
    }
}
