package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Fachada de emitir un remito. Deliberadamente SIN transaccion, como {@link VentaService}: cada intento abre la suya en
 * {@link RegistroRemito}, asi un deadlock o un choque de la clave unica revierte todo (stock incluido) y se puede
 * reintentar o resolver con una transaccion nueva.
 */
@Service
public class RemitoService {

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroRemito registroRemito;

    public RemitoService(RegistroRemito registroRemito) {
        this.registroRemito = registroRemito;
    }

    public RemitoEmitido emitir(EmitirRemito pedido, Long usuarioId) {
        int intento = 1;
        while (true) {
            try {
                return registroRemito.emitir(pedido, usuarioId);
            } catch (PessimisticLockingFailureException e) {
                if (intento >= MAX_INTENTOS) {
                    throw e;
                }
                intento++;
            } catch (DataIntegrityViolationException e) {
                // Con un codigo de MySQL que no es un duplicado (un valor fuera de rango) no hubo carrera: se relanza.
                if (ErrorDeBase.codigo(e).isPresent() && !ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                    throw e;
                }
                // Otra transaccion con la misma clave gano la carrera: el remito ya esta emitido.
                return registroRemito.repetido(pedido).orElseThrow(() -> e);
            }
        }
    }
}
