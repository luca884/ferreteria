package com.ferreteria.facturacion.motor;

import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * La configuración del job de reintento (cu-07-contingencias C5, {@code app.facturacion.reintento} en el yml), validada al
 * arrancar: con una que no cumple, la aplicación no arranca.
 * <ul>
 *   <li>{@code cada}: entre el fin de una pasada y la siguiente;</li>
 *   <li>{@code tope-pasada}: lo más que dura una pasada (se mira entre una serie y otra y entre un registro y el siguiente);
 *       tiene que alcanzar para una llamada a WSFEv1 ({@code app.facturacion.motor.presupuesto-llamada});</li>
 *   <li>{@code backoff-inicial} y {@code backoff-tope}: el backoff de una serie arranca en el inicial, se duplica en cada
 *       pasada seguida que termina en un error transitorio y no pasa del tope, que no puede ser menor que el inicial.</li>
 * </ul>
 * El backoff vive en la serie ({@code serie_fiscal}, V36), así vale entre las dos JVM de un deploy.
 */
@Component
public class PoliticaDeReintento {

    private static final String PREFIJO = "app.facturacion.reintento.";

    private final Duration cada;
    private final Duration topePasada;
    private final Duration backoffInicial;
    private final Duration backoffTope;

    public PoliticaDeReintento(@Value("${app.facturacion.reintento.cada}") Duration cada,
            @Value("${app.facturacion.reintento.tope-pasada}") Duration topePasada,
            @Value("${app.facturacion.reintento.backoff-inicial}") Duration backoffInicial,
            @Value("${app.facturacion.reintento.backoff-tope}") Duration backoffTope,
            @Value("${app.facturacion.motor.presupuesto-llamada}") Duration presupuestoLlamada) {
        positiva("cada", cada);
        positiva("tope-pasada", topePasada);
        positiva("backoff-inicial", backoffInicial);
        positiva("backoff-tope", backoffTope);
        Objects.requireNonNull(presupuestoLlamada, "Falta app.facturacion.motor.presupuesto-llamada.");
        if (topePasada.compareTo(presupuestoLlamada) < 0) {
            throw new IllegalStateException("Una pasada del job de reintento (" + PREFIJO + "tope-pasada = " + topePasada
                    + ") tiene que alcanzar para una llamada a WSFEv1 (app.facturacion.motor.presupuesto-llamada = "
                    + presupuestoLlamada + ").");
        }
        if (backoffTope.compareTo(backoffInicial) < 0) {
            throw new IllegalStateException("El tope del backoff (" + PREFIJO + "backoff-tope = " + backoffTope
                    + ") no puede ser menor que el backoff inicial (" + PREFIJO + "backoff-inicial = " + backoffInicial
                    + ").");
        }
        this.cada = cada;
        this.topePasada = topePasada;
        this.backoffInicial = backoffInicial;
        this.backoffTope = backoffTope;
    }

    private static void positiva(String propiedad, Duration valor) {
        if (valor == null || valor.isZero() || valor.isNegative()) {
            throw new IllegalStateException("Falta " + PREFIJO + propiedad + " o no es positiva (" + valor + ").");
        }
    }

    /** Entre el fin de una pasada del job y la siguiente. */
    public Duration cada() {
        return cada;
    }

    /** Lo más que dura una pasada del job. */
    public Duration topePasada() {
        return topePasada;
    }

    /**
     * Cuánto espera la serie después de {@code fallasSeguidas} pasadas seguidas con un error transitorio: el inicial,
     * duplicado en cada una a partir de la segunda, hasta el tope.
     *
     * @param fallasSeguidas 1 o más
     */
    public Duration espera(int fallasSeguidas) {
        if (fallasSeguidas < 1) {
            throw new IllegalArgumentException("El backoff es después de al menos una pasada con error.");
        }
        Duration espera = backoffInicial;
        for (int i = 1; i < fallasSeguidas && espera.compareTo(backoffTope) < 0; i++) {
            espera = espera.multipliedBy(2);
        }
        return espera.compareTo(backoffTope) > 0 ? backoffTope : espera;
    }
}
