package com.ferreteria.clientes.model;

/**
 * Formato único de la referencia que el libro copia de la venta o del recibo que lo originó (CC-001, CC-037): «Venta N.º n»,
 * «Recibo N.º n» y «Anulación Recibo N.º n». El texto se guarda en el movimiento (patrón comprobante.cliente_nombre) para que el extracto lo muestre sin
 * que clientes lea ventas. Es pública (y no package-private como sugería el design) porque la escribe el servicio de cuenta
 * corriente, que vive en {@code clientes.service}.
 */
public final class Referencias {

    private Referencias() {}

    public static String venta(Long numero) {
        return "Venta N.º " + positivo(numero);
    }

    public static String recibo(Long numero) {
        return "Recibo N.º " + positivo(numero);
    }

    /** El texto del contra-asiento de un cobro anulado (CC-062): «Anulación Recibo N.º n». */
    public static String anulacion(Long numero) {
        return "Anulación Recibo N.º " + positivo(numero);
    }

    private static long positivo(Long numero) {
        if (numero == null || numero <= 0) {
            throw new IllegalArgumentException("El número de la referencia tiene que ser positivo.");
        }
        return numero;
    }
}
