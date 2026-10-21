package com.ferreteria.compras.model;

/** Tipo del comprobante del proveedor (CU-13). Se guarda por nombre; la base tiene un CHECK con estos valores. */
public enum TipoCompra {
    FA("Factura A"),
    FB("Factura B"),
    FC("Factura C"),
    REM("Remito");

    private final String etiqueta;

    TipoCompra(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /** "Factura A 00003-00001234": punto de venta a 5 digitos y numero a 8, como los imprime ARCA. */
    public String comprobante(int puntoVenta, long numero) {
        return String.format("%s %05d-%08d", etiqueta, puntoVenta, numero);
    }
}
