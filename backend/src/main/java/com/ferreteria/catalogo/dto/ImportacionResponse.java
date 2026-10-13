package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.EstadoImportacion;
import com.ferreteria.catalogo.model.ImportacionLista;

import java.time.LocalDateTime;

/** La importacion aplicada. {@code conteos.nuevos} son los articulos efectivamente creados. */
public record ImportacionResponse(
        Long id,
        Long proveedorId,
        String archivo,
        LocalDateTime fecha,
        ConteosImportacion conteos,
        EstadoImportacion estado) {

    public static ImportacionResponse from(ImportacionLista importacion) {
        return new ImportacionResponse(
                importacion.getId(),
                importacion.getProveedorId(),
                importacion.getArchivo(),
                importacion.getFecha(),
                new ConteosImportacion(
                        importacion.getSuben(), importacion.getBajan(), importacion.getNuevos(),
                        importacion.getSinCambio(), importacion.getNoEstan(), importacion.getConError()),
                importacion.getEstado());
    }
}
