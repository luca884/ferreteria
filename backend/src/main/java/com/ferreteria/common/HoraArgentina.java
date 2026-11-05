package com.ferreteria.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * La hora local que se guarda (fecha de la venta, del movimiento, del historial) es siempre la de Buenos Aires,
 * sin depender de la zona de la JVM: en Railway corre en UTC y la venta de las 22:00 caeria en el dia siguiente.
 */
public final class HoraArgentina {

    public static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    private static final Clock RELOJ = enMicrosegundos(Clock.system(ZONA));

    private HoraArgentina() {
    }

    public static LocalDateTime ahora() {
        return LocalDateTime.now(RELOJ);
    }

    /**
     * El reloj recortado a microsegundos, la precision que guarda MySQL ({@code DATETIME(6)}). En Linux el reloj de Java
     * da nanosegundos y MySQL los redondea al guardar: la respuesta de una cobranza o una anulacion no coincidiria con la
     * repetida ni con el GET (CU-12 punto 3). Recorta, no redondea: a MySQL no le queda nada que redondear.
     * No usa {@code Clock.tick(base, 1 µs)}: en JDK 17 su {@code millis()} divide por cero.
     */
    public static Clock enMicrosegundos(Clock base) {
        return new RelojEnMicrosegundos(base);
    }

    private static final class RelojEnMicrosegundos extends Clock {

        private final Clock base;

        private RelojEnMicrosegundos(Clock base) {
            this.base = Objects.requireNonNull(base);
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return zona.equals(base.getZone()) ? this : new RelojEnMicrosegundos(base.withZone(zona));
        }

        @Override
        public Instant instant() {
            return base.instant().truncatedTo(ChronoUnit.MICROS);
        }

        /** Recortar los microsegundos no cambia los milisegundos. */
        @Override
        public long millis() {
            return base.millis();
        }

        @Override
        public boolean equals(Object otro) {
            return otro instanceof RelojEnMicrosegundos reloj && base.equals(reloj.base);
        }

        @Override
        public int hashCode() {
            return base.hashCode() + 1;
        }

        @Override
        public String toString() {
            return "RelojEnMicrosegundos[" + base + "]";
        }
    }

    public static LocalDate hoy() {
        return LocalDate.now(ZONA);
    }
}
