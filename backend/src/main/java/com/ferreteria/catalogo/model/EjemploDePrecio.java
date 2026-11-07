package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Un articulo cuyo precio cambiaria con la configuracion nueva (margen por defecto o redondeo), para mostrarle al Dueño
 * una muestra antes de confirmar. No cambia nada.
 */
public record EjemploDePrecio(String codigo, String descripcion, BigDecimal precioActual, BigDecimal precioNuevo) {
}
