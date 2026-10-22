package com.ferreteria.compras.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Fachada de la compra (CU-13). Deliberadamente SIN transaccion, como {@code RemitoService}: cada intento abre la suya en
 * {@link RegistroCompra}, asi un choque revierte todo (stock y costos incluidos) y se puede reintentar. Reintenta hasta
 * {@value #MAX_INTENTOS} veces ante un deadlock, un timeout de lock o un articulo que otro cambio mientras tanto (bloqueo
 * optimista); agotados, lanza {@link SistemaOcupadoException} (503 «reintentá»). Ante una clave unica repetida decide si
 * gano otra con la misma clave (devuelve esa compra) o si el comprobante ya estaba cargado (409).
 */
@Service
public class CompraService {

    /** Intentos ante un choque transitorio. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroCompra registroCompra;

    public CompraService(RegistroCompra registroCompra) {
        this.registroCompra = registroCompra;
    }

    public PlanCompra vistaPrevia(RegistrarCompra pedido) {
        return registroCompra.planear(pedido);
    }

    public CompraRegistrada registrar(RegistrarCompra pedido, Long usuarioId) {
        int intento = 1;
        while (true) {
            try {
                return registroCompra.registrar(pedido, usuarioId);
            } catch (ConcurrencyFailureException e) {
                if (intento >= MAX_INTENTOS) {
                    throw new SistemaOcupadoException(e);
                }
                intento++;
            } catch (DataIntegrityViolationException e) {
                // Con un codigo de MySQL que no es un duplicado (un valor fuera de rango) no hubo carrera: se relanza.
                if (ErrorDeBase.codigo(e).isPresent() && !ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                    throw e;
                }
                // Otra transaccion gano la carrera: con la misma clave, la compra ya esta; si no, el comprobante.
                return registroCompra.repetida(pedido).orElseGet(() -> {
                    registroCompra.comprobanteLibre(pedido);
                    throw e;
                });
            }
        }
    }
}
