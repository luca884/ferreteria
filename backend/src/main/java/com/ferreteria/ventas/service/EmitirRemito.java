package com.ferreteria.ventas.service;

import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide emitir como remito (RM-004), ya sin detalles HTTP: la clave de idempotencia, los renglones
 * tal como los vio el cajero (con el precio mostrado y su descuento), el total que se mostro, el descuento global, el
 * cliente elegido (id) o su nombre de texto libre, y las observaciones. No lleva forma de pago. La ferreteria y el usuario salen del JWT. Un
 * descuento ausente es 0 % y un texto en blanco es nada.
 */
public record EmitirRemito(String clave, List<Renglon> renglones, BigDecimal totalMostrado,
        BigDecimal descuentoPorcentaje, Long clienteId, String clienteNombre, String observaciones) {

    /** Sin cliente elegido de la tabla: vale el texto libre (CL-004). */
    public EmitirRemito(String clave, List<Renglon> renglones, BigDecimal totalMostrado,
            BigDecimal descuentoPorcentaje, String clienteNombre, String observaciones) {
        this(clave, renglones, totalMostrado, descuentoPorcentaje, null, clienteNombre, observaciones);
    }

    public EmitirRemito {
        descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
        clienteNombre = enBlancoEsNada(clienteNombre);
        observaciones = enBlancoEsNada(observaciones);
    }

    private static String enBlancoEsNada(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
