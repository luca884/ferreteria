package com.ferreteria.clientes.service;

import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import java.time.LocalDate;

/**
 * Lo que pide el extracto de una cuenta (CC-073): rango de fechas inclusivo, UN tipo de movimiento, página y tamaño, o la
 * impresión completa de una sola vez (D12). Todo es opcional ({@code null}): sin {@code desde} o {@code hasta} el rango queda
 * abierto de ese lado, sin {@code tipo} entran todos, sin {@code page}/{@code size} la página 0 de {@value #TAMANO_POR_DEFECTO}
 * líneas. {@code page} y {@code size} se ACOTAN en el servicio (no se rechazan, S-3); la validación del rango también es del
 * servicio, no de este registro: quien llama puede construirlo con cualquier valor.
 */
public record FiltroExtracto(LocalDate desde, LocalDate hasta, TipoMovimientoCuenta tipo, Integer page, Integer size,
        boolean impresion) {

    /** Líneas por página si no se pide un tamaño. */
    public static final int TAMANO_POR_DEFECTO = 20;

    /** El máximo de líneas por página: un tamaño mayor se acota a este. */
    public static final int TAMANO_MAXIMO = 100;

    /** Las líneas que entran en una impresión: con más se corta y se avisa con {@code truncado} (D12). */
    public static final int TOPE_IMPRESION = 2000;
}
