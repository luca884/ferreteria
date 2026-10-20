package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fachada de anular el cobro de una cuenta corriente (CC-057, CC-060, CC-061, CC-086, CC-092). Deliberadamente SIN transacción
 * (molde de {@link CobranzaService}): cada intento abre la suya en {@link RegistroAnulacion}, así un deadlock o un choque del
 * UNIQUE revierte todo y se puede reintentar o resolver con una transacción NUEVA (con REPEATABLE READ, releer dentro de la que
 * falló no vería el commit de la otra).
 *
 * <p>Orden de validación (CC-057): primero el cuerpo (400: el motivo; gana al 404), después el recibo (404, resolviendo su
 * cliente en una transacción propia de solo lectura y sin locks), y recién ahí {@link RegistroAnulacion#registrar} valida caja,
 * cliente y estado bajo los locks. Reintenta hasta {@value #MAX_INTENTOS} veces ante un deadlock o timeout de lock; agotados,
 * lanza {@link SistemaOcupadoException} (503 «reintentá» con {@code Retry-After}, no 500). Si se viola el UNIQUE de la anulación
 * (otra transacción anuló el mismo recibo y esta no lo vio; no debería pasar por el lock del cliente) lo traduce a 409
 * RECIBO_YA_ANULADO con quién y cuándo, en una transacción nueva.
 *
 * <p>No puede llamarse dentro de una transacción ajena (CC-091, W5 del verify de C3): el chequeo del inicio de {@link #anular}
 * lanza {@link IllegalStateException}, igual que {@code RecalculoConReintento}. Una transacción externa haría que la
 * pre-resolución se uniera a ella y fijara la foto antes del lock del cliente.
 */
@Service
public class AnulacionService {

    /** Intentos ante un deadlock o timeout de lock. Seguro: anular es idempotente por estado (el reintento recibe 409). */
    static final int MAX_INTENTOS = 3;

    private final RegistroAnulacion registroAnulacion;

    public AnulacionService(RegistroAnulacion registroAnulacion) {
        this.registroAnulacion = registroAnulacion;
    }

    public CobroRegistrado anular(Long reciboId, String motivo, Long usuarioId) {
        // CC-091: la pre-resolución recibo → cliente solo no fija la foto REPEATABLE READ si corre en su transacción PROPIA. Dentro
        // de una externa (un controlador o un test con @Transactional) se uniría a ella, la foto nacería ANTES del lock del
        // cliente y una anulación concurrente del mismo recibo daría 500 en vez de 409. Mismo criterio que RecalculoConReintento.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "AnulacionService no puede llamarse dentro de una transaccion: cada intento abre la suya.");
        }
        String recortado = RegistroAnulacion.validarMotivo(motivo);
        Long clienteId = registroAnulacion.clienteDelRecibo(reciboId);
        int intento = 1;
        while (true) {
            try {
                return registroAnulacion.registrar(reciboId, clienteId, recortado, usuarioId);
            } catch (PessimisticLockingFailureException e) {
                if (intento >= MAX_INTENTOS) {
                    // Contención transitoria agotada: 503 «reintentá», no un 500. Nada quedó guardado (rollback).
                    throw new SistemaOcupadoException(e);
                }
                intento++;
            } catch (DataIntegrityViolationException e) {
                // Con un código de MySQL que no es un duplicado (un valor fuera de rango) no hubo carrera: se relanza.
                if (ErrorDeBase.codigo(e).isPresent() && !ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                    throw e;
                }
                // Otra transacción anuló el mismo recibo: en una transacción nueva se ve anulado y sale el 409; si no, se relanza.
                registroAnulacion.rechazoSiAnulado(reciboId);
                throw e;
            }
        }
    }
}
