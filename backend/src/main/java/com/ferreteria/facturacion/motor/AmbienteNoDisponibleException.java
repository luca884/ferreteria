package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.Ambiente;

/**
 * El ambiente del registro todavía no tiene con quién hablar: homologación y producción llegan con el cliente real de
 * WSFEv1 (rama 6). El registro queda como estaba y no se manda a ningún otro lado (nunca al simulador).
 */
public class AmbienteNoDisponibleException extends RuntimeException {

    public AmbienteNoDisponibleException(Ambiente ambiente) {
        super("Todavía no hay conexión con ARCA para el ambiente " + ambiente
                + ": llega con la rama 6 del plan de facturación. El comprobante queda pendiente y no se manda al simulador.");
    }
}
