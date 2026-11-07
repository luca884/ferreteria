package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Un articulo modificado por una actualizacion masiva, antes y despues. Los margenes son los
 * efectivos (propio, o del rubro, o por defecto).
 *
 * @param margenHeredado true si, despues de la operacion, el articulo no tiene margen propio y usa el
 *                      del rubro o el de la ferreteria
 * @param advertencia texto de la variacion fuerte, o null
 * @param moneda      moneda del articulo: los costos van en ella y los precios siempre en pesos
 * @param ivaPorcentaje IVA del articulo (21.00 es 21 %): el costo va sin IVA y el precio de venta lo incluye
 * @param rubroAnteriorId rubro del articulo hoy; null si no tiene
 * @param rubroNuevoId  rubro despues de la operacion: el destino con {@code CAMBIAR_RUBRO}; en los otros modos, el mismo
 *                      que {@code rubroAnteriorId}
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
        boolean margenHeredado,
        String advertencia,
        Moneda moneda,
        BigDecimal ivaPorcentaje,
        Long rubroAnteriorId,
        Long rubroNuevoId) {
}
