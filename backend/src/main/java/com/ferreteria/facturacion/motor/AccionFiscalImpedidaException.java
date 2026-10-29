package com.ferreteria.facturacion.motor;

import com.ferreteria.common.exception.ConflictException;
import java.util.Objects;

/**
 * Una acción del Dueño sobre la factura de una venta que no se puede hacer (rama 7, CU-16 punto 7; C2 y C3 de
 * cu-07-contingencias): «Reintentar» o «Dejar sin factura». No cambió nada. {@link #impedimento()} dice por qué, para que
 * quien la expone la traduzca a su código (409 por defecto, con el mensaje para el usuario).
 */
public class AccionFiscalImpedidaException extends ConflictException {

    /** Por qué no se pudo. */
    public enum Impedimento {
        /** La venta no tiene un registro vigente RECHAZADO o FUERA_DE_FECHA. */
        SIN_FACTURA_RECHAZADA,
        /** La fecha de la venta ya no entra en la ventana de ARCA y el Dueño no confirmó la de hoy. */
        FECHA_NUEVA_REQUERIDA,
        /** La ferretería no factura hoy (deshabilitada, sin credencial vigente, bloqueada por ARCA o sin datos de CU-01). */
        FACTURACION_FRENADA
    }

    static final String SIN_FACTURA_RECHAZADA = "Esta venta no tiene una factura rechazada para reintentar.";
    static final String FECHA_NUEVA_REQUERIDA =
            "La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.";

    private final Impedimento impedimento;

    private AccionFiscalImpedidaException(Impedimento impedimento, String mensaje) {
        super(mensaje);
        this.impedimento = Objects.requireNonNull(impedimento);
    }

    static AccionFiscalImpedidaException sinFacturaRechazada() {
        return new AccionFiscalImpedidaException(Impedimento.SIN_FACTURA_RECHAZADA, SIN_FACTURA_RECHAZADA);
    }

    static AccionFiscalImpedidaException fechaNuevaRequerida() {
        return new AccionFiscalImpedidaException(Impedimento.FECHA_NUEVA_REQUERIDA, FECHA_NUEVA_REQUERIDA);
    }

    /** @param motivo el de la regla de alta (el mismo texto con que se niega una venta facturable) */
    static AccionFiscalImpedidaException facturacionFrenada(String motivo) {
        return new AccionFiscalImpedidaException(Impedimento.FACTURACION_FRENADA, motivo);
    }

    public Impedimento impedimento() {
        return impedimento;
    }
}
