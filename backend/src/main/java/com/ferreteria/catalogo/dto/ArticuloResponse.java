package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
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
        BigDecimal margenPorcentaje,
        AlicuotaIva alicuotaIva,
        BigDecimal precioFinal,
        Unidad unidad,
        BigDecimal stockActual,
        BigDecimal stockMinimo,
        String ubicacion,
        boolean activo,
        BigDecimal precioListaProveedor) {

    /** Igual que {@link #from(Articulo)} pero con el stock indicado (el vigente en la base, no el de la entidad leida). */
    public static ArticuloResponse from(Articulo articulo, BigDecimal stockActual) {
        ArticuloResponse base = from(articulo);
        return new ArticuloResponse(base.id(), base.codigoInterno(), base.codigoBarras(), base.codigoProveedor(),
                base.descripcion(), base.rubroId(), base.proveedorId(), base.costo(), base.margenPorcentaje(),
                base.alicuotaIva(), base.precioFinal(), base.unidad(), stockActual, base.stockMinimo(),
                base.ubicacion(), base.activo(), base.precioListaProveedor());
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
                articulo.getMargenPorcentaje(),
                articulo.getAlicuotaIva(),
                articulo.getPrecioFinal(),
                articulo.getUnidad(),
                articulo.getStockActual(),
                articulo.getStockMinimo(),
                articulo.getUbicacion(),
                articulo.isActivo(),
                articulo.getPrecioListaProveedor());
    }
}
