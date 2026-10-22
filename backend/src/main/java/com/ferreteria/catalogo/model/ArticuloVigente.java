package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Foto de un articulo del proveedor al momento de planificar: el planificador no toca entidades.
 * {@code costo} esta en la {@code moneda} del articulo; {@code precioFinal} siempre en pesos. {@code alicuotaIva} y
 * {@code ajustes} son los que tiene hoy: la lista puede corregirlos. {@code precioManual} y {@code ajustesPrecio} son
 * los de CU-14: con ellos {@link #precioCon} da lo mismo que {@link Articulo#precioConCosto}.
 */
public record ArticuloVigente(
        Long id,
        String codigoInterno,
        String codigoProveedor,
        String descripcion,
        BigDecimal costo,
        BigDecimal precioFinal,
        BigDecimal margenPorcentaje,
        Long rubroId,
        AlicuotaIva alicuotaIva,
        boolean activo,
        Moneda moneda,
        Ajustes ajustes,
        boolean precioManual,
        AjustesDePrecio ajustesPrecio) {

    public ArticuloVigente {
        ajustes = ajustes == null ? Ajustes.NINGUNO : ajustes;
        ajustesPrecio = ajustesPrecio == null ? AjustesDePrecio.NINGUNO : ajustesPrecio;
    }

    /** Articulo sin precio manual ni ajustes sobre el precio final (CU-14). */
    public ArticuloVigente(Long id, String codigoInterno, String codigoProveedor, String descripcion,
                           BigDecimal costo, BigDecimal precioFinal, BigDecimal margenPorcentaje, Long rubroId,
                           AlicuotaIva alicuotaIva, boolean activo, Moneda moneda, Ajustes ajustes) {
        this(id, codigoInterno, codigoProveedor, descripcion, costo, precioFinal, margenPorcentaje, rubroId,
            alicuotaIva, activo, moneda, ajustes, false, AjustesDePrecio.NINGUNO);
    }

    /**
     * El precio final con ese costo, margen, alicuota y cotizacion, con la regla de {@link Articulo}: con precio manual
     * es el precio de hoy; si no, el calculado mas los ajustes sobre el precio final. Puede dar 0 o menos si los
     * ajustes lo llevan ahi: el planificador lo marca como error.
     */
    public BigDecimal precioCon(BigDecimal costoNuevo, BigDecimal margen, AlicuotaIva alicuota, BigDecimal multiplo,
                                BigDecimal cotizacion) {
        if (precioManual) {
            return precioFinal;
        }
        return PrecioCalculator.precioFinal(costoNuevo, margen, alicuota, multiplo, cotizacion, ajustesPrecio);
    }

    /** Articulo en una moneda, sin descuentos ni recargos propios. */
    public ArticuloVigente(Long id, String codigoInterno, String codigoProveedor, String descripcion,
                           BigDecimal costo, BigDecimal precioFinal, BigDecimal margenPorcentaje, Long rubroId,
                           AlicuotaIva alicuotaIva, boolean activo, Moneda moneda) {
        this(id, codigoInterno, codigoProveedor, descripcion, costo, precioFinal, margenPorcentaje, rubroId,
            alicuotaIva, activo, moneda, Ajustes.NINGUNO, false, AjustesDePrecio.NINGUNO);
    }

    /** Articulo en pesos, sin descuentos ni recargos propios. */
    public ArticuloVigente(Long id, String codigoInterno, String codigoProveedor, String descripcion,
                           BigDecimal costo, BigDecimal precioFinal, BigDecimal margenPorcentaje, Long rubroId,
                           AlicuotaIva alicuotaIva, boolean activo) {
        this(id, codigoInterno, codigoProveedor, descripcion, costo, precioFinal, margenPorcentaje, rubroId,
            alicuotaIva, activo, Moneda.ARS, Ajustes.NINGUNO, false, AjustesDePrecio.NINGUNO);
    }

    public static ArticuloVigente de(Articulo articulo) {
        return new ArticuloVigente(
            articulo.getId(),
            articulo.getCodigoInterno(),
            articulo.getCodigoProveedor(),
            articulo.getDescripcion(),
            articulo.getCosto(),
            articulo.getPrecioFinal(),
            articulo.getMargenPorcentaje(),
            articulo.getRubroId(),
            articulo.getAlicuotaIva(),
            articulo.isActivo(),
            articulo.getMoneda(),
            articulo.getAjustes(),
            articulo.isPrecioManual(),
            articulo.getAjustesPrecio());
    }
}
