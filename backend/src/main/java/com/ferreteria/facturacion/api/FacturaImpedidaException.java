package com.ferreteria.facturacion.api;

import java.util.Objects;

/**
 * «Reintentar» o «Dejar sin factura» (cu-07-contingencias C2 y C3; CU-16 punto 7) no se pudo hacer y no cambió nada.
 * {@link #getMessage()} es el texto para el Dueño, tal cual; {@link #impedimento()} es el código con que quien lo expone
 * responde (409). Solo tipos del JDK, como todo el puerto.
 */
public class FacturaImpedidaException extends RuntimeException {

    /** Por qué no se pudo. */
    public enum Impedimento {
        /** La venta no tiene un registro vigente RECHAZADO o FUERA_DE_FECHA. */
        SIN_FACTURA_RECHAZADA,
        /** La fecha de la venta ya no entra en la ventana de ARCA y el Dueño no confirmó la de hoy. */
        FECHA_NUEVA_REQUERIDA,
        /** La ferretería no factura hoy (deshabilitada, sin credencial vigente, bloqueada por ARCA o sin datos de CU-01). */
        FACTURACION_FRENADA
    }

    private final Impedimento impedimento;

    public FacturaImpedidaException(Impedimento impedimento, String mensaje) {
        super(mensaje);
        this.impedimento = Objects.requireNonNull(impedimento);
    }

    public Impedimento impedimento() {
        return impedimento;
    }
}
