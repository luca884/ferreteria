package com.ferreteria.facturacion.model;

/**
 * El documento con que se identifica a quien recibe el comprobante, con el {@code DocTipo} de WSFEv1. Es propio de
 * {@code facturacion}: quien pide la factura traduce el del cliente a este. La venta sin cliente va
 * {@link #SIN_IDENTIFICAR}, con número 0.
 */
public enum TipoDocumentoReceptor {
    CUIT(80),
    CUIL(86),
    DNI(96),
    SIN_IDENTIFICAR(99);

    private final int docTipo;

    TipoDocumentoReceptor(int docTipo) {
        this.docTipo = docTipo;
    }

    /** El {@code DocTipo} de WSFEv1. */
    public int docTipo() {
        return docTipo;
    }
}
