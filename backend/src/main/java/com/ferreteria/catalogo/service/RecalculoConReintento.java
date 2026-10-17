package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * El recalculo por cotizacion con reintento acotado (lote 5b, R-228). Si un articulo cambia mientras se recalcula, el
 * intento entero se revierte (el reclamo del dia, los precios y el historial van juntos) y se vuelve a hacer, hasta
 * {@value #INTENTOS} veces en total: recalcular es idempotente (sin cambio de precio no hay historial) y el nuevo intento
 * relee la edicion ya confirmada. Despues del ultimo choque la excepcion sale: «Recalcular ahora» la responde como 409 (la
 * red global del manejador de errores) y el job la registra y reintenta al minuto siguiente.
 *
 * <p>NO es transaccional, a proposito: cada intento tiene que abrir y cerrar la suya, que es la del
 * {@link RecalculoPorCotizacionService}. Cualquier otra excepcion (por ejemplo el 409 de sin cotizacion) sale en el
 * primer intento.
 */
@Service
public class RecalculoConReintento {

    /** Intentos completos en total, el primero incluido. */
    static final int INTENTOS = 3;

    private final RecalculoPorCotizacionService recalculoPorCotizacionService;

    public RecalculoConReintento(RecalculoPorCotizacionService recalculoPorCotizacionService) {
        this.recalculoPorCotizacionService = recalculoPorCotizacionService;
    }

    /** «Recalcular ahora» (ver {@link RecalculoPorCotizacionService#recalcularAhora}). */
    public RecalculoCotizacionResponse recalcularAhora(Long usuarioId) {
        return conReintento(() -> recalculoPorCotizacionService.recalcularAhora(usuarioId));
    }

    /** El recalculo del dia (ver {@link RecalculoPorCotizacionService#recalcularDelDia}). */
    public Optional<RecalculoCotizacionResponse> recalcularDelDia(LocalDateTime ahoraBuenosAires) {
        return conReintento(() -> recalculoPorCotizacionService.recalcularDelDia(ahoraBuenosAires));
    }

    private static <T> T conReintento(Supplier<T> intento) {
        for (int numero = 1; ; numero++) {
            try {
                return intento.get();
            } catch (OptimisticLockingFailureException e) {
                if (numero >= INTENTOS) {
                    throw e;
                }
            }
        }
    }
}
