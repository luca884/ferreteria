package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Un articulo modificado por una actualizacion masiva, antes y despues. Los margenes son los
 * efectivos (propio, o del rubro, o por defecto).
 *
 * @param advertencia texto de la variacion fuerte, o null
 */
public record CambioMasivo(
        Long articuloId,
        String codigo,
        String descripcion,
        BigDecimal costoAnterior,
        BigDecimal costoNuevo,
        BigDecimal margenAnterior,
        BigDecimal margenNuevo,
        BigDecimal precioAnterior,
        BigDecimal precioNuevo,
        String advertencia) {
}
