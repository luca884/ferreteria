package com.ferreteria.clientes.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fachada de ajustar una cuenta corriente (GDS-20). Deliberadamente SIN transacción (molde de {@code CobranzaService}): cada
 * intento abre la suya en {@link RegistroAjuste}, así un deadlock o un choque de la clave única revierte todo y se puede
 * reintentar o resolver con una transacción NUEVA (con REPEATABLE READ, releer dentro de la que falló no vería el commit de la
 * otra).
 *
 * <p>Antes de registrar hace un pre-chequeo de la clave ({@link RegistroAjuste#repetida}) en una transacción propia, de solo
 * lectura y sin locks: si el ajuste ya existe lo devuelve aunque el cliente se haya desactivado, y como es OTRA transacción no
 * contamina la foto de la que registra. Después reintenta hasta {@value #MAX_INTENTOS} veces ante un deadlock o timeout de lock
 * (seguro por la idempotencia de la clave); agotados, lanza {@link SistemaOcupadoException} (503 «reintentá»). Si pierde la
 * carrera de la clave única (el caso de la misma clave con OTRO cliente, que no comparte lock) busca el ganador con una
 * transacción nueva. Misma clave y mismo cliente: la segunda espera el lock del cliente y el paso «repetida» de
 * {@link RegistroAjuste#registrar} le devuelve el original.
 *
 * <p>No puede llamarse dentro de una transacción ajena (R-004): el pre-chequeo se uniría a ella y fijaría la foto antes del
 * lock del cliente.
 */
@Service
public class AjusteCuentaService {

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroAjuste registroAjuste;

    public AjusteCuentaService(RegistroAjuste registroAjuste) {
        this.registroAjuste = registroAjuste;
    }

    public AjusteRegistrado ajustar(AjustePedido pedido, Long usuarioId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "AjusteCuentaService no puede llamarse dentro de una transaccion: cada intento abre la suya.");
        }
        Optional<AjusteRegistrado> yaRegistrado = registroAjuste.repetida(pedido);
        if (yaRegistrado.isPresent()) {
            return yaRegistrado.get();
        }
        int intento = 1;
        while (true) {
            try {
                return registroAjuste.registrar(pedido, usuarioId);
            } catch (PessimisticLockingFailureException e) {
                if (intento >= MAX_INTENTOS) {
                    // Contención transitoria agotada: 503 «reintentá» (la clave hace seguro el reintento), no un 500.
                    throw new SistemaOcupadoException(e);
                }
                intento++;
            } catch (DataIntegrityViolationException e) {
                // Con un código de MySQL que no es un duplicado (un valor fuera de rango) no hubo carrera: se relanza.
                if (ErrorDeBase.codigo(e).isPresent() && !ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                    throw e;
                }
                // Otra transacción con la misma clave ganó la carrera: el ajuste ya está confirmado (o es otro contenido: 409).
                return registroAjuste.repetida(pedido).orElseThrow(() -> e);
            }
        }
    }
}
