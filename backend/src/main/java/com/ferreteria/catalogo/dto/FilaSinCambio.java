package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.PrecioListaPlaneado;

/**
 * Una fila de la lista que no cambia nada: en la vista previa solo hace falta saber cual es para poder elegirla, no
 * sus precios.
 */
public record FilaSinCambio(Long id, int hoja, int fila, String codigo, String descripcion) {

    public static FilaSinCambio de(PrecioListaPlaneado planeado) {
        return new FilaSinCambio(planeado.articuloId(), planeado.hoja(), planeado.fila(), planeado.codigoProveedor(),
                planeado.descripcion());
    }
}
