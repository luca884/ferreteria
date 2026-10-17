package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.Unidad;

import java.math.BigDecimal;

/** Un unico DTO para Dueno y Vendedor: ambos ven costo y margen. */
public record ArticuloResponse(
        Long id,
        String codigoInterno,
        String codigoBarras,
        String codigoProveedor,
        String descripcion,
        Long rubroId,
        Long proveedorId,
        BigDecimal costo,
        /** Moneda del costo; el precio final siempre esta en pesos. */
        Moneda moneda,
        BigDecimal margenPorcentaje,
        AlicuotaIva alicuotaIva,
        BigDecimal precioFinal,
        Unidad unidad,
        BigDecimal stockActual,
        BigDecimal stockMinimo,
        String ubicacion,
        boolean activo,
        BigDecimal precioListaProveedor,
        /** Descuentos o recargos propios en % (negativo es descuento); null es "sin ajuste". Solo lectura. */
        BigDecimal ajuste1,
        BigDecimal ajuste2,
        BigDecimal ajuste3,
        /** Base con la que se calculo el costo en la ultima importacion; null si no hay base guardada. */
        BigDecimal bonificacion1,
        BigDecimal bonificacion2,
        BigDecimal bonificacion3,
        Boolean listaIncluyeIva,
        /** Version del articulo (bloqueo optimista): la que hay que mandar en el proximo guardado. */
        Long version) {

    /** Igual que {@link #from(Articulo)} pero con el stock indicado (el vigente en la base, no el de la entidad leida). */
    public static ArticuloResponse from(Articulo articulo, BigDecimal stockActual) {
        ArticuloResponse base = from(articulo);
        return new ArticuloResponse(base.id(), base.codigoInterno(), base.codigoBarras(), base.codigoProveedor(),
                base.descripcion(), base.rubroId(), base.proveedorId(), base.costo(), base.moneda(), base.margenPorcentaje(),
                base.alicuotaIva(), base.precioFinal(), base.unidad(), stockActual, base.stockMinimo(),
                base.ubicacion(), base.activo(), base.precioListaProveedor(), base.ajuste1(), base.ajuste2(),
                base.ajuste3(), base.bonificacion1(), base.bonificacion2(), base.bonificacion3(),
                base.listaIncluyeIva(), base.version());
    }

    public static ArticuloResponse from(Articulo articulo) {
        return new ArticuloResponse(
                articulo.getId(),
                articulo.getCodigoInterno(),
                articulo.getCodigoBarras(),
                articulo.getCodigoProveedor(),
                articulo.getDescripcion(),
                articulo.getRubroId(),
                articulo.getProveedorId(),
                articulo.getCosto(),
                articulo.getMoneda(),
                articulo.getMargenPorcentaje(),
                articulo.getAlicuotaIva(),
                articulo.getPrecioFinal(),
                articulo.getUnidad(),
                articulo.getStockActual(),
                articulo.getStockMinimo(),
                articulo.getUbicacion(),
                articulo.isActivo(),
                articulo.getPrecioListaProveedor(),
                articulo.getAjuste1(),
                articulo.getAjuste2(),
                articulo.getAjuste3(),
                articulo.getBonificacion1(),
                articulo.getBonificacion2(),
                articulo.getBonificacion3(),
                articulo.getListaIncluyeIva(),
                articulo.getVersion());
    }
}
