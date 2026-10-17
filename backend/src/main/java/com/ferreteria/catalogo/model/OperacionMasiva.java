package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * La operacion de una actualizacion masiva: el modo y su valor (porcentaje de costo o margen). El
 * valor se guarda a escala 2 para que 10 y 10.00 sean la misma operacion; la validacion ya lo limito
 * a 2 decimales, asi que un valor con mas decimales es un error de programacion.
 */
public record OperacionMasiva(ModoMasivo modo, BigDecimal valor) {

    public OperacionMasiva {
        Objects.requireNonNull(modo, "modo");
        Objects.requireNonNull(valor, "valor");
        if (modo == ModoMasivo.COSTO_MONTO) {
            validarMonto(valor);
        }
        valor = valor.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** El monto no puede ser cero (no cambiaria nada) ni tener mas de 2 decimales significativos. */
    private static void validarMonto(BigDecimal monto) {
        if (monto.signum() == 0) {
            throw new ValorMasivoInvalidoException("El monto no puede ser cero.");
        }
        if (monto.stripTrailingZeros().scale() > 2) {
            throw new ValorMasivoInvalidoException("El valor puede tener como máximo 2 decimales.");
        }
    }
}
