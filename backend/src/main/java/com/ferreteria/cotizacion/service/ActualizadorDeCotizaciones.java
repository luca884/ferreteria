package com.ferreteria.cotizacion.service;

import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Orquestador de las fuentes: prueba la principal y, si falla o viene vacia, el respaldo. Si las dos fallan lo avisa
 * en el log y no toca nada (se sigue usando la ultima cotizacion guardada). El fallback es por fuente entera; el upsert
 * por (moneda, casa) solo toca lo que llego, asi un tipo que ninguna fuente trae (por ejemplo el MEP con DolarApi
 * caida) conserva su ultimo valor y nunca se cambia solo a otro tipo. Una fuente que trae un valor que no entra en la
 * columna DECIMAL(15,4) cuenta como fallida: se la descarta entera y se prueba el respaldo, en vez de cortar el ciclo
 * con el error de la base (auditoria H09).
 *
 * <p>Es global: no conoce ferreterias ni {@code TenantContext}. Las fuentes se consultan fuera de la transaccion, para
 * no retener una conexion a la base mientras se espera a la red; el guardado, en cambio, es una sola transaccion.
 */
@Service
public class ActualizadorDeCotizaciones {

    private static final Logger log = LoggerFactory.getLogger(ActualizadorDeCotizaciones.class);

    /** Enteros que entran en compra y venta: DECIMAL(15,4) deja 11 antes de la coma. */
    private static final int ENTEROS_MAXIMOS = 11;
    private static final int DECIMALES = 4;

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
            Optional<CotizacionLeida> invalida = leidas.stream().filter(l -> !entra(l.compra()) || !entra(l.venta()))
                    .findFirst();
            if (invalida.isPresent()) {
                log.warn("La fuente {} trajo un valor que no entra en la base ({} compra {} venta {}): se descarta",
                        fuente.nombre(), invalida.get().casa(), invalida.get().compra(), invalida.get().venta());
                continue;
            }
            guardar(fuente.nombre(), leidas);
            return Optional.of(fuente.nombre());
        }
        log.error("Ninguna fuente de cotizaciones respondio: se sigue usando la ultima guardada");
        return Optional.empty();
    }

    /** Un valor faltante entra (hay tipos sin compra o sin venta); los decimales de mas los redondea la base. */
    private static boolean entra(BigDecimal valor) {
        if (valor == null) {
            return true;
        }
        BigDecimal redondeado = valor.setScale(DECIMALES, RoundingMode.HALF_UP);
        return redondeado.precision() - redondeado.scale() <= ENTEROS_MAXIMOS;
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
