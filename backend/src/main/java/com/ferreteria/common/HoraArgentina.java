package com.ferreteria.common;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * La hora local que se guarda (fecha de la venta, del movimiento, del historial) es siempre la de Buenos Aires,
 * sin depender de la zona de la JVM: en Railway corre en UTC y la venta de las 22:00 caeria en el dia siguiente.
 */
public final class HoraArgentina {

    public static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    private static final Duration MICROSEGUNDO = Duration.ofNanos(1_000);

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
     */
    public static Clock enMicrosegundos(Clock base) {
        return Clock.tick(base, MICROSEGUNDO);
    }

    public static LocalDate hoy() {
        return LocalDate.now(ZONA);
    }
}
