package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Bonificaciones;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * La parte "datos" de la vista previa. Las bonificaciones son opcionales (vacia cuenta como 0) y
 * rigen solo para esta solicitud: no se guardan en el proveedor. El rubro es el que se elegiria al aplicar para los
 * articulos nuevos que no traen rubro propio en la fila: la vista previa calcula su precio con el margen de ese rubro,
 * igual que aplicar. Null (o sin mandar) significa sin rubro.
 */
public record VistaPreviaRequest(
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

        /** Rubro de respaldo de los articulos nuevos; null los deja sin rubro (margen por defecto). */
        Long rubroId) {

    /** El pedido de antes de poder elegir el rubro: los nuevos sin rubro propio se calculan sin rubro. */
    public VistaPreviaRequest(Long proveedorId, MapeoRequest mapeo, boolean listaIncluyeIva, BigDecimal bonificacion1,
                              BigDecimal bonificacion2, BigDecimal bonificacion3) {
        this(proveedorId, mapeo, listaIncluyeIva, bonificacion1, bonificacion2, bonificacion3, null);
    }

    public Bonificaciones toBonificaciones() {
        return new Bonificaciones(bonificacion1, bonificacion2, bonificacion3);
    }
}
