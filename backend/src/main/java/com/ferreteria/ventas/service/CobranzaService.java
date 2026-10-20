package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Fachada de cobrar una cuenta corriente (CC-038, CC-049). Deliberadamente SIN transacción (molde de {@link VentaService}):
 * cada intento abre la suya en {@link RegistroCobranza}, así un deadlock o un choque de la clave única revierte todo y se puede
 * reintentar o resolver con una transacción NUEVA (con REPEATABLE READ, releer dentro de la que falló no vería el commit de
 * la otra).
 *
 * <p>Antes de registrar hace un pre-chequeo de la clave ({@link RegistroCobranza#repetida}) en una transacción propia, de solo
 * lectura y sin locks: si el cobro ya existe lo devuelve aunque la caja haya cerrado o el cliente se haya desactivado (misma
 * semántica que ventas), y como es OTRA transacción no contamina la foto de la que registra. Después reintenta hasta
 * {@value #MAX_INTENTOS} veces ante un deadlock o timeout de lock (seguro por la idempotencia de la clave); agotados, lanza
 * {@link SistemaOcupadoException} (la API responde 503 «reintentá», no 500). Si pierde la carrera
 * de la clave única (otra transacción con la misma clave ganó: el caso de la misma clave con OTRO cliente, que no comparte lock)
 * busca el cobro ganador con una transacción nueva. Misma clave y mismo cliente: la segunda espera el lock del cliente y el
 * paso «repetida» de {@link RegistroCobranza#registrar} le devuelve el recibo original, sin necesitar este fallback.
 */
@Service
public class CobranzaService {

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroCobranza registroCobranza;

    public CobranzaService(RegistroCobranza registroCobranza) {
        this.registroCobranza = registroCobranza;
    }

    /** El recibo con su imputación, para verlo o reimprimirlo. Lo lee {@link RegistroCobranza} en su transacción de solo lectura. */
    public CobroRegistrado recibo(Long id) {
        return registroCobranza.recibo(id);
    }

    public CobroRegistrado cobrar(CobranzaPedido pedido, Long usuarioId) {
        Optional<CobroRegistrado> yaRegistrado = registroCobranza.repetida(pedido);
        if (yaRegistrado.isPresent()) {
            return yaRegistrado.get();
        }
        int intento = 1;
        while (true) {
            try {
                return registroCobranza.registrar(pedido, usuarioId);
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
                // Otra transacción con la misma clave ganó la carrera: el cobro ya está confirmado (o es otro contenido: 422).
                return registroCobranza.repetida(pedido).orElseThrow(() -> e);
            }
        }
    }
}
