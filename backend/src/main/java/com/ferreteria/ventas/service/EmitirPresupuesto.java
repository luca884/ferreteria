package com.ferreteria.ventas.service;

import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide guardar como presupuesto (PP-003), ya sin detalles HTTP: la clave de idempotencia, los
 * renglones tal como los vio el cajero (con el precio mostrado y su descuento), el total que se mostro, el descuento
 * global, los dias de validez, el nombre del cliente y las observaciones. No lleva forma de pago. La ferreteria y el
 * usuario salen del JWT. Un descuento ausente es 0 %, una validez ausente son 15 dias y un texto en blanco es nada.
 */
public record EmitirPresupuesto(String clave, List<Renglon> renglones, BigDecimal totalMostrado,
        BigDecimal descuentoPorcentaje, Integer diasValidez, String clienteNombre, String observaciones) {

    public static final int DIAS_VALIDEZ_POR_DEFECTO = 15;
    public static final int DIAS_VALIDEZ_MAXIMOS = 90;

    public EmitirPresupuesto {
        descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
        diasValidez = diasValidez == null ? DIAS_VALIDEZ_POR_DEFECTO : diasValidez;
        clienteNombre = enBlancoEsNada(clienteNombre);
        observaciones = enBlancoEsNada(observaciones);
    }

    private static String enBlancoEsNada(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
