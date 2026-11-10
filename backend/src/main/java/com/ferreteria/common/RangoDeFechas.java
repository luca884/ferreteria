package com.ferreteria.common;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.time.LocalDate;
import java.util.Map;

/**
 * El rango «Desde / Hasta» de los listados y reportes: un solo texto para «Hasta» anterior a «Desde», en la clave
 * {@code hasta}, que es el campo que la pantalla enfoca (bugs-relevamiento-celular, S4 y S12).
 */
public final class RangoDeFechas {

    public static final String HASTA_ANTERIOR_A_DESDE = "La fecha «Hasta» no puede ser anterior a «Desde».";

    private RangoDeFechas() {
    }

    /** 400 si vienen las dos fechas y «hasta» es anterior a «desde». Con una sola o ninguna no hay nada que validar. */
    public static void validar(LocalDate desde, LocalDate hasta) {
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new DatosInvalidosException(Map.of("hasta", HASTA_ANTERIOR_A_DESDE));
        }
    }
}
