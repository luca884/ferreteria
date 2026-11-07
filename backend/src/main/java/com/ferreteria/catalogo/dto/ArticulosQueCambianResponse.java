package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.EjemploDePrecio;

import java.util.List;

/**
 * El aviso antes de cambiar el margen por defecto o el redondeo: cuantos articulos se recalcularian y unos pocos
 * ejemplos de los que cambian de precio (vacio si ninguno cambia).
 */
public record ArticulosQueCambianResponse(long cantidad, List<EjemploDePrecio> ejemplos) {
}
