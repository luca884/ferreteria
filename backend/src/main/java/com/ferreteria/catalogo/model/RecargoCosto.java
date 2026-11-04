package com.ferreteria.catalogo.model;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Un recargo con nombre sobre el costo de un articulo (CU-13, S4), por ejemplo «Embalaje y carga 4,44 %». Positivo o
 * negativo; se aplica sobre el costo antes del margen y el IVA ({@link RecargosDeCosto#aplicar}). Se valida aca, con un
 * 400 en {@code recargosCosto}: el nombre de 1 a 40 caracteres (recortado), el porcentaje de -99,99 a 99,99, distinto de
 * 0 y con hasta 2 decimales (no se redondea sin avisar). El porcentaje queda a escala 2 para que el JSON que vuelve de la
 * base se compare por igualdad.
 */
public record RecargoCosto(String nombre, BigDecimal porcentaje) {

    public static final int NOMBRE_MAXIMO = 40;
    public static final BigDecimal PORCENTAJE_MINIMO = new BigDecimal("-99.99");
    public static final BigDecimal PORCENTAJE_MAXIMO = new BigDecimal("99.99");

    public RecargoCosto {
        nombre = nombre == null ? "" : nombre.strip();
        if (nombre.isEmpty()) {
            throw invalido("El nombre del recargo es obligatorio.");
        }
        if (nombre.length() > NOMBRE_MAXIMO) {
            throw invalido("El nombre del recargo tiene hasta 40 caracteres.");
        }
        String rango = "El porcentaje del recargo debe estar entre -99,99 y 99,99, y no puede ser 0.";
        if (porcentaje == null) {
            throw invalido(rango);
        }
        if (porcentaje.stripTrailingZeros().scale() > 2) {
            throw invalido("El porcentaje del recargo tiene hasta 2 decimales.");
        }
        porcentaje = porcentaje.setScale(2, RoundingMode.UNNECESSARY);
        if (porcentaje.signum() == 0
                || porcentaje.compareTo(PORCENTAJE_MINIMO) < 0 || porcentaje.compareTo(PORCENTAJE_MAXIMO) > 0) {
            throw invalido(rango);
        }
    }

    private static DatosInvalidosException invalido(String mensaje) {
        return new DatosInvalidosException(Map.of("recargosCosto", mensaje));
    }
}
