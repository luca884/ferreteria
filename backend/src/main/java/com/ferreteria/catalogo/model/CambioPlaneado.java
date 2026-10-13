package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Articulo existente cuyo costo sube o baja. {@code precioNuevo} es el precio final calculado con
 * el contexto del articulo (para mostrar); {@code variacionFuerte} marca la advertencia de +-50 %.
 */
public record CambioPlaneado(
        int fila,
        Long articuloId,
        String codigoProveedor,
        String descripcion,
        BigDecimal precioLista,
        BigDecimal costoAnterior,
        BigDecimal costoNuevo,
        BigDecimal precioAnterior,
        BigDecimal precioNuevo,
        boolean activo,
        boolean variacionFuerte) {}
