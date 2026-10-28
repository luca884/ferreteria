package com.ferreteria.ventas.service;

import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.Pago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de cobrar: la venta guardada con sus renglones y sus pagos (uno por medio, GDS-07), los articulos que quedaron con stock
 * negativo (R-016) y si es la venta original de una clave repetida (R-011: el endpoint responde 200 y no 201).
 * {@code conRegistroFiscal} es si ESTA venta (la nueva, no la repetida) dio de alta su registro fiscal en su transaccion (CU-07
 * paso 3): la fachada pide su CAE despues del commit. {@code fiscal} es el estado fiscal con el que se responde (CU-07 paso
 * 5), o null si la venta no tiene registro fiscal o todavia no se consulto.
 */
public record VentaRegistrada(Comprobante comprobante, List<ItemComprobante> items, List<Pago> pagos,
        List<ArticuloSinStock> sinStock, boolean repetida, boolean conRegistroFiscal, EstadoFiscalVenta fiscal) {

    /** Una venta sin registro fiscal propio ni estado fiscal (la consulta, la repetida antes de mirar su estado). */
    public VentaRegistrada(Comprobante comprobante, List<ItemComprobante> items, List<Pago> pagos,
            List<ArticuloSinStock> sinStock, boolean repetida) {
        this(comprobante, items, pagos, sinStock, repetida, false, null);
    }

    /** La misma venta con su estado fiscal. */
    public VentaRegistrada conFiscal(EstadoFiscalVenta estado) {
        return new VentaRegistrada(comprobante, items, pagos, sinStock, repetida, conRegistroFiscal, estado);
    }

    /** Un articulo cuyo stock resultante quedo menor que 0. */
    public record ArticuloSinStock(Long articuloId, String descripcion, BigDecimal stockActual) {}
}
