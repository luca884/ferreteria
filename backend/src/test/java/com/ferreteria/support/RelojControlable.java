package com.ferreteria.support;

import com.ferreteria.common.HoraArgentina;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Un {@code Clock} que el test fija y adelanta, para los tests de integración que necesitan la hora dentro del contexto de
 * Spring (los unitarios siguen con {@code Clock.fixed} por constructor). Se registra como bean {@code @Primary} con
 * {@link RelojControlableConfig}: todo bean que inyecta {@code Clock} en ese contexto lee esta hora. Seguro entre hilos:
 * los tests de concurrencia lo leen desde varios.
 */
public final class RelojControlable extends Clock {

    private final AtomicReference<Instant> ahora;
    private final ZoneId zona;

    public RelojControlable(Instant inicio) {
        this(new AtomicReference<>(Objects.requireNonNull(inicio)), HoraArgentina.ZONA);
    }

    private RelojControlable(AtomicReference<Instant> ahora, ZoneId zona) {
        this.ahora = ahora;
        this.zona = zona;
    }

    /** Fija la hora. */
    public void fijar(Instant instante) {
        ahora.set(Objects.requireNonNull(instante));
    }

    /** Adelanta (o atrasa, con una duración negativa) la hora. */
    public void avanzar(Duration duracion) {
        ahora.updateAndGet(i -> i.plus(duracion));
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    /** La misma hora vista desde otra zona (comparte el instante: fijarlo en una lo cambia en las dos). */
    @Override
    public Clock withZone(ZoneId otraZona) {
        return new RelojControlable(ahora, otraZona);
    }

    @Override
    public Instant instant() {
        return ahora.get();
    }
}
