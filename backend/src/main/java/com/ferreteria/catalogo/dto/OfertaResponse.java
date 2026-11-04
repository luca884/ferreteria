package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.TipoOferta;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * La oferta guardada del articulo (ofertas, S3), vigente o vencida. {@code vigente} y {@code precioOferta} son los de
 * ese dia: una oferta vencida, o que ya no baja el precio normal, vuelve con {@code vigente} false y sin precio.
 */
public record OfertaResponse(TipoOferta tipo, BigDecimal valor, LocalDate hasta, boolean vigente,
        BigDecimal precioOferta) {

    /** La oferta del articulo ese dia; null si no tiene. */
    public static OfertaResponse de(Articulo articulo, LocalDate dia) {
        return articulo.getOferta()
                .map(o -> {
                    BigDecimal precio = articulo.precioDeOfertaEl(dia).orElse(null);
                    return new OfertaResponse(o.tipo(), o.valor(), o.hasta(), precio != null, precio);
                })
                .orElse(null);
    }
}
