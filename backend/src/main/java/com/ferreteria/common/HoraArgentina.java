package com.ferreteria.common;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * La hora local que se guarda (fecha de la venta, del movimiento, del historial) es siempre la de Buenos Aires,
 * sin depender de la zona de la JVM: en Railway corre en UTC y la venta de las 22:00 caeria en el dia siguiente.
 */
public final class HoraArgentina {

    public static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    private HoraArgentina() {
    }

    public static LocalDateTime ahora() {
        return LocalDateTime.now(ZONA);
    }
}
