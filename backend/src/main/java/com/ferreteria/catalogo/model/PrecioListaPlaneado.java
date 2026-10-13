package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/** Fila "sin cambio": el costo queda igual y solo se registra el precio de lista del proveedor. */
public record PrecioListaPlaneado(Long articuloId, BigDecimal precioLista) {}
