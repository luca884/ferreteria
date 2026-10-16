package com.ferreteria.cotizacion.service;

import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Orquestador de las fuentes: prueba la principal y, si falla o viene vacia, el respaldo. Si las dos fallan lo avisa
 * en el log y no toca nada (se sigue usando la ultima cotizacion guardada). El fallback es por fuente entera; el upsert
 * por (moneda, casa) solo toca lo que llego, asi un tipo que ninguna fuente trae (por ejemplo el MEP con DolarApi
 * caida) conserva su ultimo valor y nunca se cambia solo a otro tipo.
 *
 * <p>Es global: no conoce ferreterias ni {@code TenantContext}. Las fuentes se consultan fuera de la transaccion, para
 * no retener una conexion a la base mientras se espera a la red; el guardado, en cambio, es una sola transaccion.
 */
@Service
public class ActualizadorDeCotizaciones {

    private static final Logger log = LoggerFactory.getLogger(ActualizadorDeCotizaciones.class);

    private final List<FuenteDeCotizaciones> fuentes;
    private final CotizacionRepository repositorio;
    private final TransactionOperations transaccion;
    private final Clock reloj;

    /** {@code fuentes} en orden de preferencia: la principal primero. */
    public ActualizadorDeCotizaciones(List<FuenteDeCotizaciones> fuentes, CotizacionRepository repositorio,
                                      TransactionOperations transaccion, Clock reloj) {
        this.fuentes = List.copyOf(fuentes);
        this.repositorio = repositorio;
        this.transaccion = transaccion;
        this.reloj = reloj;
    }

    /** Devuelve el nombre de la fuente que respondio, o vacio si ninguna lo hizo y no se guardo nada. */
    public Optional<String> actualizar() {
        for (FuenteDeCotizaciones fuente : fuentes) {
            List<CotizacionLeida> leidas;
            try {
                leidas = fuente.leer();
            } catch (RuntimeException e) {
                log.warn("La fuente {} fallo: {}", fuente.nombre(), e.getMessage());
                continue;
            }
            if (leidas.isEmpty()) {
                log.warn("La fuente {} no devolvio ninguna cotizacion", fuente.nombre());
                continue;
            }
            guardar(fuente.nombre(), leidas);
            return Optional.of(fuente.nombre());
        }
        log.error("Ninguna fuente de cotizaciones respondio: se sigue usando la ultima guardada");
        return Optional.empty();
    }

    private void guardar(String fuente, List<CotizacionLeida> leidas) {
        Instant obtenidaEn = Instant.now(reloj);
        transaccion.executeWithoutResult(estado -> leidas.forEach(leida -> {
            Instant actualizadaEn = leida.actualizadaEn() != null ? leida.actualizadaEn() : obtenidaEn;
            Cotizacion cotizacion = repositorio.findByMonedaAndCasa(leida.moneda(), leida.casa()).orElse(null);
            if (cotizacion == null) {
                repositorio.save(new Cotizacion(leida.moneda(), leida.casa(), leida.nombre(), leida.compra(),
                        leida.venta(), fuente, actualizadaEn, obtenidaEn));
            } else {
                cotizacion.actualizar(leida.nombre(), leida.compra(), leida.venta(), fuente, actualizadaEn, obtenidaEn);
                repositorio.save(cotizacion);
            }
        }));
    }
}
