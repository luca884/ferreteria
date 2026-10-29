package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.Ambiente;

/**
 * El ambiente del registro no tiene con quién hablar: {@link WsfePorAmbiente} no tiene un adaptador para él. En el cableado
 * real los tres ambientes tienen uno (rama 6); pasa con un cableado parcial, como el de algunos tests. El registro queda como
 * estaba y no se manda a ningún otro lado (nunca al simulador).
 */
public class AmbienteNoDisponibleException extends RuntimeException {

    public AmbienteNoDisponibleException(Ambiente ambiente) {
        super("No hay conexión con ARCA para el ambiente " + ambiente
                + ": el comprobante queda pendiente y no se manda a ningún otro lado.");
    }
}
