package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * La operacion de una actualizacion masiva: el modo y su valor (porcentaje de costo o margen). El
 * valor se guarda a escala 2 para que 10 y 10.00 sean la misma operacion; la validacion ya lo limito
 * a 2 decimales, asi que un valor con mas decimales es un error de programacion.
 *
 * <p>{@code CAMBIAR_RUBRO} es la excepcion: no lleva valor (null) y lleva el rubro destino ({@link #cambiarRubro}). Los
 * demas modos no llevan destino. La validacion del pedido ya lo controla, asi que lo contrario es un error de
 * programacion.
 */
public record OperacionMasiva(ModoMasivo modo, BigDecimal valor, Long rubroDestinoId) {

    public OperacionMasiva {
        Objects.requireNonNull(modo, "modo");
        if (modo == ModoMasivo.CAMBIAR_RUBRO) {
            Objects.requireNonNull(rubroDestinoId, "rubroDestinoId");
            if (valor != null) {
                throw new IllegalArgumentException("Cambiar el rubro no lleva valor.");
            }
        } else {
            Objects.requireNonNull(valor, "valor");
            if (rubroDestinoId != null) {
                throw new IllegalArgumentException("Solo cambiar el rubro lleva un rubro destino.");
            }
            if (modo == ModoMasivo.COSTO_MONTO || modo == ModoMasivo.PRECIO_MONTO) {
                validarMonto(valor);
            }
            valor = valor.setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** Una operacion con valor (todos los modos salvo {@code CAMBIAR_RUBRO}). */
    public OperacionMasiva(ModoMasivo modo, BigDecimal valor) {
        this(modo, valor, null);
    }

    /** Pasar los articulos al rubro {@code rubroDestinoId}, que ya existe y es de la ferreteria. */
    public static OperacionMasiva cambiarRubro(Long rubroDestinoId) {
        return new OperacionMasiva(ModoMasivo.CAMBIAR_RUBRO, null, rubroDestinoId);
    }

    /** El rubro que tendria un articulo despues de la operacion: el destino al cambiar el rubro, si no el de hoy. */
    public Long rubroDespues(Long rubroActual) {
        return modo == ModoMasivo.CAMBIAR_RUBRO ? rubroDestinoId : rubroActual;
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
