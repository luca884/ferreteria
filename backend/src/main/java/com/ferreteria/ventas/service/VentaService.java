package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Fachada de cobrar (C-009). Deliberadamente SIN transaccion: cada intento abre la suya en
 * {@link RegistroVenta}, asi un deadlock o un choque de la clave unica revierte todo y se puede reintentar o
 * resolver con una transaccion nueva (con REPEATABLE READ, releer dentro de la que fallo no veria el commit
 * de la otra). Si una venta a cuenta corriente es rechazada por el limite, mira si la clave ya estaba registrada (el doble envio).
 */
@Service
public class VentaService {

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroVenta registroVenta;

    public VentaService(RegistroVenta registroVenta) {
        this.registroVenta = registroVenta;
    }

    /** Cobra como un usuario que no puede autorizar excesos de cuenta corriente (un Vendedor). */
    public VentaRegistrada cobrar(ConfirmarVenta pedido, Long usuarioId) {
        return cobrar(pedido, usuarioId, false);
    }

    /** {@code usuarioEsDueno} sale del rol del JWT: solo el Dueño puede autorizar que una venta pase el limite del cliente (CC-005). */
    public VentaRegistrada cobrar(ConfirmarVenta pedido, Long usuarioId, boolean usuarioEsDueno) {
        int intento = 1;
        while (true) {
            try {
                return registroVenta.registrar(pedido, usuarioId, usuarioEsDueno);
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
            } catch (VentaRechazadaException e) {
                // CC-009: el doble envio de una venta a cuenta con el limite justo: la segunda copia espera el lock del cliente, ve
                // la deuda de la primera y se pasaria del limite. Con una transaccion nueva (snapshot fresco) se mira si la clave ya
                // esta registrada: si lo esta, es la venta original (200); si no, es un rechazo de verdad.
                if (e.motivo() != Motivo.LIMITE_CUENTA_CORRIENTE) {
                    throw e;
                }
                return registroVenta.repetida(pedido).orElseThrow(() -> e);
            }
        }
    }
}
