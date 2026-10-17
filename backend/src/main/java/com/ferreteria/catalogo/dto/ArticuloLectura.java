package com.ferreteria.catalogo.dto;

/**
 * Lo que devuelve la lectura de un articulo segun el rol: {@link ArticuloResponse} para el Dueno (con costo y margen)
 * y {@link ArticuloVendedorResponse} para el Vendedor (sin nada de costo ni margen).
 */
public sealed interface ArticuloLectura permits ArticuloResponse, ArticuloVendedorResponse {
}
