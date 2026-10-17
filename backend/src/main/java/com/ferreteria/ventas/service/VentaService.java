package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Fachada de cobrar (C-009). Deliberadamente SIN transaccion: cada intento abre la suya en
 * {@link RegistroVenta}, asi un deadlock o un choque de la clave unica revierte todo y se puede reintentar o
 * resolver con una transaccion nueva (con REPEATABLE READ, releer dentro de la que fallo no veria el commit
 * de la otra).
 */
@Service
public class VentaService {

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroVenta registroVenta;

    public VentaService(RegistroVenta registroVenta) {
        this.registroVenta = registroVenta;
    }

    public VentaRegistrada cobrar(ConfirmarVenta pedido, Long usuarioId) {
        int intento = 1;
        while (true) {
            try {
                return registroVenta.registrar(pedido, usuarioId);
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
                // Otra transaccion con la misma clave gano la carrera: la venta ya esta confirmada.
                return registroVenta.repetida(pedido).orElseThrow(() -> e);
            }
        }
    }
}
