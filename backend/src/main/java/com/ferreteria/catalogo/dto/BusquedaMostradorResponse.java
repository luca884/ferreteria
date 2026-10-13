package com.ferreteria.catalogo.dto;

import java.util.List;

/**
 * Resultado de buscar en el mostrador. items: 1 en EXACTO, hasta 20 en LISTA y vacio en los otros dos.
 * hayMas solo puede ser true en LISTA: habia mas de 20 coincidencias (no se cuentan).
 */
public record BusquedaMostradorResponse(TipoResultadoMostrador tipo, List<ArticuloMostradorResponse> items, boolean hayMas) {
}
