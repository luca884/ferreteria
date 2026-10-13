package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ArticuloVigente;

/** Un articulo activo del proveedor que no figura en la lista: solo se informa, nunca se toca. */
public record ArticuloAusente(Long id, String codigoInterno, String codigoProveedor, String descripcion) {

    public static ArticuloAusente de(ArticuloVigente articulo) {
        return new ArticuloAusente(
                articulo.id(), articulo.codigoInterno(), articulo.codigoProveedor(), articulo.descripcion());
    }
}
