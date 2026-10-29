package com.ferreteria.facturacion.motor;

/**
 * Cómo se anula una venta del lado fiscal (CU-08 puntos 2 y 3, D12; C1 de cu-08-nota-de-credito), según el registro vigente
 * de su factura: {@link PasosFiscales#tomarParaAnular} lo decide con la fila tomada.
 */
public enum ViaFiscalDeAnulacion {
    /** La factura está AUTORIZADO: se anula con una nota de crédito total, con su propio CAE. */
    NOTA_DE_CREDITO,
    /**
     * La venta no tiene CAE (sin registro, o PENDIENTE, RECHAZADO, FUERA_DE_FECHA o SIN_FACTURA): se anula sin documento
     * fiscal, y lo que tenga queda SIN_FACTURA ({@link PasosFiscales#cerrarSinCae}).
     */
    ANULACION_INTERNA
}
