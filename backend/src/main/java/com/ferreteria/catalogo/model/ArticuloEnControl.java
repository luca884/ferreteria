package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Una fila de un control de datos (GDS-18, G81): lo que el Dueño necesita para ver por qué el articulo cayó en el
 * control y corregirlo. Rubro y proveedor son null si el articulo no tiene.
 */
public record ArticuloEnControl(Long articuloId, String codigoInterno, String descripcion, String rubro,
                                String proveedor, BigDecimal costo, String moneda, String alicuotaIva,
                                BigDecimal precioFinal, boolean precioManual) {
}
