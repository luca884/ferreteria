package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Fachada de emitir un presupuesto. Deliberadamente SIN transaccion, como {@link VentaService}: cada intento abre la
 * suya en {@link RegistroPresupuesto}, asi un deadlock o un choque de la clave unica revierte todo y se puede
 * reintentar o resolver con una transaccion nueva. Reintenta hasta {@value #MAX_INTENTOS} veces ante un deadlock o timeout
 * de lock; agotados, lanza {@link SistemaOcupadoException} (la API responde 503 «reintenta», no 500).
 */
@Service
public class PresupuestoService {

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroPresupuesto registroPresupuesto;

    public PresupuestoService(RegistroPresupuesto registroPresupuesto) {
        this.registroPresupuesto = registroPresupuesto;
    }

    public PresupuestoEmitido emitir(EmitirPresupuesto pedido, Long usuarioId) {
        int intento = 1;
        while (true) {
            try {
                return registroPresupuesto.emitir(pedido, usuarioId);
            } catch (PessimisticLockingFailureException e) {
                if (intento >= MAX_INTENTOS) {
                    // Contención transitoria agotada: 503 «reintentá» (la clave hace seguro el reintento), no un 500.
                    throw new SistemaOcupadoException(e);
                }
                intento++;
            } catch (DataIntegrityViolationException e) {
                // Con un codigo de MySQL que no es un duplicado (un valor fuera de rango) no hubo carrera: se relanza.
                if (ErrorDeBase.codigo(e).isPresent() && !ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                    throw e;
                }
                // Otra transaccion con la misma clave gano la carrera: el presupuesto ya esta emitido.
                return registroPresupuesto.repetido(pedido).orElseThrow(() -> e);
            }
        }
    }
}
