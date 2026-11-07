package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Una fila de un control de datos (GDS-18, G81): lo que el Dueño necesita para ver por qué el articulo cayó en el
 * control y corregirlo. Rubro y proveedor son null si el articulo no tiene.
 *
 * @param costoReal el costo con sus recargos (CU-13), el que usa el margen negativo; null si el articulo no tiene
 *                  recargos
 */
public record ArticuloEnControl(Long articuloId, String codigoInterno, String descripcion, String rubro,
                                String proveedor, BigDecimal costo, String moneda, String alicuotaIva,
                                BigDecimal precioFinal, boolean precioManual, BigDecimal costoReal) {

    /** La misma fila con el costo real de sus recargos; sin recargos queda en null. */
    public ArticuloEnControl conRecargos(RecargosDeCosto recargos) {
        BigDecimal real = recargos.estaVacia() ? null : recargos.aplicar(costo);
        return new ArticuloEnControl(articuloId, codigoInterno, descripcion, rubro, proveedor, costo, moneda,
                alicuotaIva, precioFinal, precioManual, real);
    }
}
