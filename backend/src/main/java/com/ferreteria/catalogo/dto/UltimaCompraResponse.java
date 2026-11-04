package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.api.UltimasCompras.UltimaCompra;

import java.time.LocalDate;

/** La ultima compra de un articulo en su ficha (cu-13-factura-pdf, S5): lleva al detalle de la compra. */
public record UltimaCompraResponse(Long compraId, String comprobante, LocalDate fecha) {

    public static UltimaCompraResponse de(UltimaCompra compra) {
        return new UltimaCompraResponse(compra.compraId(), compra.comprobante(), compra.fecha());
    }
}
