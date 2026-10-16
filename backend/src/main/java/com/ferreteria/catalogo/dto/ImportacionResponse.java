package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.EstadoImportacion;
import com.ferreteria.catalogo.model.ImportacionLista;

import java.time.LocalDateTime;

/**
 * Una importacion aplicada: lo que responde aplicar y cada elemento del listado. {@code conteos.nuevos} son los
 * articulos efectivamente creados. {@code usuarioId} es el id de quien la aplico: {@code catalogo} no lee {@code auth},
 * asi que el nombre del usuario lo resuelve la pantalla.
 */
public record ImportacionResponse(
        Long id,
        Long proveedorId,
        String archivo,
        LocalDateTime fecha,
        ConteosImportacion conteos,
        EstadoImportacion estado,
        String proveedorNombre,
        Long usuarioId) {

    public static ImportacionResponse from(ImportacionLista importacion, String proveedorNombre) {
        return new ImportacionResponse(
                importacion.getId(),
                importacion.getProveedorId(),
                importacion.getArchivo(),
                importacion.getFecha(),
                new ConteosImportacion(
                        importacion.getSuben(), importacion.getBajan(), importacion.getNuevos(),
                        importacion.getSinCambio(), importacion.getNoEstan(), importacion.getConError(),
                        importacion.getCambianMoneda(), importacion.getCorrigen(), importacion.getSalteadas()),
                importacion.getEstado(),
                proveedorNombre,
                importacion.getUsuarioId());
    }

    public static ImportacionResponse from(ImportacionLista importacion) {
        return from(importacion, null);
    }
}
