package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AjustePrecio;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenMargen;
import com.ferreteria.catalogo.model.PasoDePrecio;

import java.math.BigDecimal;
import java.util.List;

/**
 * El desglose del precio de un articulo, de solo lectura, para Dueno y Vendedor. Las bonificaciones son las GUARDADAS
 * en el articulo (las de su ultima importacion), no las del proveedor de hoy. El precio final es siempre el guardado.
 *
 * <p>Sin lista ({@code tieneLista} false) el precio de lista, la base, los ajustes, {@code costoDeLista} y
 * {@code coincide} van en null, no hay pasos de lista y {@code motivoSinLista} explica por que. La fecha del ultimo
 * recalculo no va aca: sale de {@code GET /api/cotizacion}.
 */
public record DesglosePrecioResponse(
        Long articuloId,
        boolean tieneLista,
        /** Null cuando tiene lista; si no, el texto unico de "este articulo no tiene lista de proveedor". */
        String motivoSinLista,
        Moneda moneda,
        BigDecimal precioLista,
        Boolean listaIncluyeIva,
        AlicuotaIva alicuotaIva,
        /** Descuentos del proveedor guardados en el articulo; positivo descuenta. */
        BigDecimal bonificacion1,
        BigDecimal bonificacion2,
        BigDecimal bonificacion3,
        /** Descuentos o recargos del articulo; negativo descuenta. */
        BigDecimal ajuste1,
        BigDecimal ajuste2,
        BigDecimal ajuste3,
        List<PasoDePrecio> pasos,
        /** El costo que sale de la lista con la base guardada y los ajustes actuales; null sin lista. */
        BigDecimal costoDeLista,
        BigDecimal costoGuardado,
        /** Si el costo guardado es igual al de la lista; null sin lista. */
        Boolean coincide,
        BigDecimal margen,
        OrigenMargen origenMargen,
        /** Pesos por dolar; null si el articulo esta en pesos o no hay ninguna cotizacion. */
        BigDecimal cotizacion,
        /** True si el articulo esta en dolares y la cotizacion esta apagada (se usa la ultima). */
        boolean cotizacionCongelada,
        BigDecimal multiplo,
        BigDecimal precioFinal,
        /** CU-14: el precio final lo escribio el Dueño; los pasos no llegan a el. */
        boolean precioManual,
        /** CU-14: ajustes sobre el precio final, entre el redondeo y el precio final (sin efecto con precio manual). */
        List<AjustePrecio> ajustesPrecio) {

    public DesglosePrecioResponse {
        pasos = List.copyOf(pasos);
        ajustesPrecio = ajustesPrecio == null ? List.of() : List.copyOf(ajustesPrecio);
    }
}
