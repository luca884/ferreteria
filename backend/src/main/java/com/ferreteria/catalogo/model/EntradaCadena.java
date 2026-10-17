package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Todo lo que necesita {@link CadenaDePrecio} para armar el desglose de un articulo. Las bonificaciones son las
 * GUARDADAS en el articulo. El precio y el costo van tal como estan guardados.
 *
 * @param tieneLista     false si el articulo no tiene lista de proveedor: no hay pasos de lista y {@code precioLista},
 *                       {@code bonificaciones} y {@code ajustes} no se usan
 * @param cotizacion     pesos por dolar; solo cuenta si la moneda es USD, y null si no hay ninguna
 * @param cotizacionCongelada true si la cotizacion esta apagada y {@code cotizacion} es la ultima guardada
 * @param multiplo       multiplo de redondeo del precio; null o 0 es sin multiplo (no hay paso de redondeo)
 * @param margenPropio   null si el articulo no tiene margen propio
 * @param margenRubro    null si no tiene rubro o el rubro no tiene margen
 */
public record EntradaCadena(
        boolean tieneLista,
        BigDecimal precioLista,
        boolean listaIncluyeIva,
        AlicuotaIva alicuota,
        Bonificaciones bonificaciones,
        Ajustes ajustes,
        BigDecimal costoGuardado,
        Moneda moneda,
        BigDecimal cotizacion,
        boolean cotizacionCongelada,
        BigDecimal margenPropio,
        BigDecimal margenRubro,
        BigDecimal margenDefecto,
        BigDecimal multiplo,
        BigDecimal precioGuardado) {
}
