package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.ventas.service.RegistroAnulacionDeVenta.NotaRegistrada;
import com.ferreteria.ventas.service.VentaGestion.VentaConsultada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fachada de anular una venta (CU-08; cu-08-nota-de-credito T4). Deliberadamente SIN transacción (molde de
 * {@link AnulacionService}): cada intento abre la suya en {@link RegistroAnulacionDeVenta}, así un deadlock, un registro
 * fiscal que cambió con un «Reintentar» ({@code CannotAcquireLockException}) o un choque del UNIQUE de la nota revierte todo
 * y se reintenta o se resuelve con una transacción NUEVA (con REPEATABLE READ, releer dentro de la que falló no vería el
 * commit de la otra).
 *
 * <p>Primero resuelve la venta y su cliente en una transacción propia de solo lectura (404 y el 409 de la nota de
 * crédito), y recién ahí {@link RegistroAnulacionDeVenta#registrar} valida todo lo demás bajo los locks. Reintenta hasta
 * {@value #MAX_INTENTOS} veces ante un deadlock o timeout de lock; agotados, 503 «reintentá». Si se viola el UNIQUE de la
 * nota (otra anulación de la misma venta se coló), lo traduce a 409 VENTA_YA_ANULADA con quién y cuándo.
 *
 * <p>Después del commit, sin transacción (la llamada a ARCA no va con una conexión tomada), la NC pide su CAE con la espera
 * corta del cobro ({@link FacturacionParaVenta#autorizar}): lo que no llega queda pendiente para el job. Nada de eso deshace
 * la anulación, que ya está hecha: una falla queda en el log. Responde la nota como la ve el detalle de Ventas.
 */
@Service
public class AnulacionDeVenta {

    private static final Logger log = LoggerFactory.getLogger(AnulacionDeVenta.class);

    /** Intentos ante un deadlock o timeout de lock. Seguro: la segunda vuelta ve la nota y da 409. */
    static final int MAX_INTENTOS = 3;

    private final RegistroAnulacionDeVenta registro;
    private final FacturacionParaVenta facturacion;
    private final VentaGestion gestion;

    public AnulacionDeVenta(RegistroAnulacionDeVenta registro, FacturacionParaVenta facturacion, VentaGestion gestion) {
        this.registro = registro;
        this.facturacion = facturacion;
        this.gestion = gestion;
    }

    /**
     * Anula la venta con una nota de crédito (si tiene su factura AUTORIZADO) o con la anulación interna (con
     * {@code motivo}), y devuelve la nota con su estado fiscal después de la espera del CAE.
     */
    public VentaConsultada anular(Long ventaId, String motivo, long usuarioId) {
        // Una transacción externa haría que la pre-resolución se uniera a ella y fijara la foto antes del lock de la venta.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "AnulacionDeVenta no puede llamarse dentro de una transacción: cada intento abre la suya.");
        }
        Long clienteId = registro.clienteDeLaVenta(ventaId);
        NotaRegistrada nota = registrar(ventaId, clienteId, motivo, usuarioId);
        if (nota.conCae()) {
            try {
                facturacion.autorizar(nota.notaId());
            } catch (RuntimeException e) {
                log.error("No se pudo pedir el CAE de la nota de crédito {}: queda pendiente.", nota.notaId(), e);
            }
        }
        return gestion.detalle(nota.notaId());
    }

    private NotaRegistrada registrar(Long ventaId, Long clienteId, String motivo, long usuarioId) {
        int intento = 1;
        while (true) {
            try {
                return registro.registrar(ventaId, clienteId, motivo, usuarioId);
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
                // Otra anulación de la misma venta ganó: en una transacción nueva se ve su nota y sale el 409.
                registro.rechazoSiAnulada(ventaId);
                throw e;
            }
        }
    }
}
