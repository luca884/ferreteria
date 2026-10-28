package com.ferreteria.ventas.service;

import com.ferreteria.facturacion.api.FacturacionParaVenta.DocumentoFiscal;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.Pago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de cobrar: la venta guardada con sus renglones y sus pagos (uno por medio, GDS-07), los articulos que quedaron con stock
 * negativo (R-016) y si es la venta original de una clave repetida (R-011: el endpoint responde 200 y no 201).
 * {@code conRegistroFiscal} es si ESTA venta (la nueva, no la repetida) dio de alta su registro fiscal en su transaccion (CU-07
 * paso 3): la fachada pide su CAE despues del commit. {@code fiscal} es el documento fiscal con el que se responde, con su
 * estado de la misma lectura (CU-07 paso 5, cu-07-factura-mostrador C4), o null si la venta no tiene registro fiscal o
 * todavia no se consulto. {@code remitoNumero} es el numero del remito del que sale la venta (para «Remito N.º» de la hoja),
 * o null si no sale de uno.
 */
public record VentaRegistrada(Comprobante comprobante, List<ItemComprobante> items, List<Pago> pagos,
        List<ArticuloSinStock> sinStock, boolean repetida, boolean conRegistroFiscal, DocumentoFiscal fiscal,
        Long remitoNumero) {

    /** Una venta sin registro fiscal propio, estado fiscal ni remito (la de los tests de la fachada). */
    public VentaRegistrada(Comprobante comprobante, List<ItemComprobante> items, List<Pago> pagos,
            List<ArticuloSinStock> sinStock, boolean repetida) {
        this(comprobante, items, pagos, sinStock, repetida, false, null, null);
    }

    /** La misma venta con su documento fiscal. */
    public VentaRegistrada conFiscal(DocumentoFiscal documento) {
        return new VentaRegistrada(comprobante, items, pagos, sinStock, repetida, conRegistroFiscal, documento,
                remitoNumero);
    }

    /** Un articulo cuyo stock resultante quedo menor que 0. */
    public record ArticuloSinStock(Long articuloId, String descripcion, BigDecimal stockActual) {}
}
