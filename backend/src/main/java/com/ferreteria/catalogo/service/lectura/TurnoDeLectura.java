package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.tenant.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Una lectura a la vez en todo el servidor: leer una lista arma toda la lista en memoria, y el servidor es compartido
 * por todas las ferreterias. El turno es justo (el que espera primero lee primero) y la espera tiene tope; una
 * ferreteria que ya tiene una lista leyendose o esperando turno no espera otra: asi los pedidos que esperan no pasan
 * de uno por ferreteria y ninguna le saca el turno a las demas. Sin ferreteria (tests, medicion) solo hay turno.
 */
final class TurnoDeLectura {

    static final String OCUPADO = "Ya se está leyendo otra lista. Probá de nuevo en un momento.";

    private final Duration espera;
    private final String ocupado;
    private final Semaphore turno = new Semaphore(1, true);
    private final Set<Long> ferreteriasEnTurno = ConcurrentHashMap.newKeySet();

    TurnoDeLectura(Duration espera) {
        this(espera, OCUPADO);
    }

    /** Con su propio aviso de ocupado (el PDF tiene su turno aparte). */
    TurnoDeLectura(Duration espera, String ocupado) {
        this.espera = espera;
        this.ocupado = ocupado;
    }

    <T> T conTurno(Supplier<T> lectura) {
        Optional<Long> ferreteria = TenantContext.currentFerreteriaId();
        if (ferreteria.isPresent() && !ferreteriasEnTurno.add(ferreteria.get())) {
            throw new BadRequestException(ocupado);
        }
        try {
            esperarTurno();
            try {
                return lectura.get();
            } finally {
                turno.release();
            }
        } finally {
            ferreteria.ifPresent(ferreteriasEnTurno::remove);
        }
    }

    private void esperarTurno() {
        try {
            if (!turno.tryAcquire(espera.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BadRequestException(ocupado);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la espera para leer la lista", e);
        }
    }
}
