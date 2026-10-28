package com.ferreteria.facturacion.model;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * El chequeo de la CbteFch antes de enviar (S5; concepto 1, productos; manual 4.8, reglas de 10016 y 10152). Función pura.
 * La CbteFch es la fecha de Buenos Aires de la venta y nunca cambia sola: lo que no entra pasa a FUERA_DE_FECHA, sin
 * número y sin reintento.
 */
public final class VentanaCbteFch {

    /** Para productos (concepto 1), la CbteFch va de N−5 a N+5 del día del envío, con los dos extremos incluidos. */
    static final int DIAS = 5;

    private VentanaCbteFch() {
    }

    /**
     * Si la CbteFch entra: no anterior a la del último autorizado, entre N−5 y N+5 del día del envío y, si es posterior al
     * envío, del mismo mes.
     *
     * @param cbteFch la CbteFch del comprobante
     * @param hoy el día del envío (N), en Buenos Aires
     * @param ultimaAutorizada la CbteFch del último autorizado de la serie; null si no se conoce o no hay
     */
    public static boolean admite(LocalDate cbteFch, LocalDate hoy, LocalDate ultimaAutorizada) {
        if (ultimaAutorizada != null && cbteFch.isBefore(ultimaAutorizada)) {
            return false;
        }
        if (cbteFch.isBefore(hoy.minusDays(DIAS)) || cbteFch.isAfter(hoy.plusDays(DIAS))) {
            return false;
        }
        return !cbteFch.isAfter(hoy) || YearMonth.from(cbteFch).equals(YearMonth.from(hoy));
    }
}
