package com.ferreteria.catalogo.model;

/** Un articulo del alcance que no se puede actualizar; no se escribe. */
public record ErrorMasivo(Long articuloId, String codigo, String descripcion, String motivo) {
}
