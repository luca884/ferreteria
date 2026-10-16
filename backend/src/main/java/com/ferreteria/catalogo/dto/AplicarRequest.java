package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.ClaveFila;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * La parte "datos" de aplicar: lo mismo que la vista previa mas la opcion de crear los articulos
 * nuevos (con un rubro opcional), las filas elegidas y los contadores que el cliente confirma. Si al
 * recalcular el plan los contadores de lo elegido difieren, no se escribe nada (409).
 */
public record AplicarRequest(
        @NotNull(message = "Elegí el proveedor.")
        Long proveedorId,

        @NotNull(message = "Faltan los datos del mapeo de columnas.")
        @Valid
        MapeoRequest mapeo,

        boolean listaIncluyeIva,

        @DecimalMin(value = "0.00", message = "La bonificación 1 debe estar entre 0 y 100.", groups = ValidacionBonificaciones.class)
        @DecimalMax(value = "100.00", message = "La bonificación 1 debe estar entre 0 y 100.", groups = ValidacionBonificaciones.class)
        @Digits(integer = 3, fraction = 2, message = "La bonificación 1 puede tener como máximo 2 decimales.", groups = ValidacionBonificaciones.class)
        BigDecimal bonificacion1,

        @DecimalMin(value = "0.00", message = "La bonificación 2 debe estar entre 0 y 100.", groups = ValidacionBonificaciones.class)
        @DecimalMax(value = "100.00", message = "La bonificación 2 debe estar entre 0 y 100.", groups = ValidacionBonificaciones.class)
        @Digits(integer = 3, fraction = 2, message = "La bonificación 2 puede tener como máximo 2 decimales.", groups = ValidacionBonificaciones.class)
        BigDecimal bonificacion2,

        @DecimalMin(value = "0.00", message = "La bonificación 3 debe estar entre 0 y 100.", groups = ValidacionBonificaciones.class)
        @DecimalMax(value = "100.00", message = "La bonificación 3 debe estar entre 0 y 100.", groups = ValidacionBonificaciones.class)
        @Digits(integer = 3, fraction = 2, message = "La bonificación 3 puede tener como máximo 2 decimales.", groups = ValidacionBonificaciones.class)
        BigDecimal bonificacion3,

        /** Por defecto false: sin esto las filas nuevas se cuentan pero no se crean. */
        boolean crearNuevos,

        /** Rubro de todos los articulos nuevos; null deja los nuevos sin rubro (margen por defecto). */
        Long rubroId,

        @NotNull(message = "Faltan los contadores de la vista previa.")
        @Valid
        ConteosRequest conteosEsperados,

        /**
         * Las filas elegidas en la vista previa, por hoja y fila. Null equivale a todas las que se pueden aplicar (el
         * cliente de antes de elegir filas); una lista vacia no es "todas": es un 400.
         */
        @Valid
        @Size(max = 20000, message = "Se pueden elegir hasta 20.000 filas.")
        List<FilaElegida> filasElegidas) {

    /** El pedido de antes de elegir filas: sin seleccion, se aplican todas. */
    public AplicarRequest(Long proveedorId, MapeoRequest mapeo, boolean listaIncluyeIva, BigDecimal bonificacion1,
                          BigDecimal bonificacion2, BigDecimal bonificacion3, boolean crearNuevos, Long rubroId,
                          ConteosRequest conteosEsperados) {
        this(proveedorId, mapeo, listaIncluyeIva, bonificacion1, bonificacion2, bonificacion3, crearNuevos, rubroId,
                conteosEsperados, null);
    }

    public Bonificaciones toBonificaciones() {
        return new Bonificaciones(bonificacion1, bonificacion2, bonificacion3);
    }

    /** Las claves de las filas elegidas, o null si no se mando seleccion. */
    public List<ClaveFila> clavesElegidas() {
        return filasElegidas == null ? null : filasElegidas.stream().map(FilaElegida::toClave).toList();
    }
}
