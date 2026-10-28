package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.DatosInvalidosException;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El numero de factura que escribe el Dueño para buscar una venta (cu-07-factura-mostrador S15): «12», «1-12» o
 * «00001-00000012», con el punto de venta de hasta 5 digitos y el numero de hasta 8. Sin punto de venta busca en todos.
 *
 * @param puntoVenta el punto de venta, o null si no se escribio
 * @param numero el numero del comprobante
 */
public record NumeroFiscal(Integer puntoVenta, long numero) {

    static final String CAMPO = "numeroFiscal";
    static final String MENSAJE = "Escribí el número de la factura como 12 o 00001-00000012.";

    private static final Pattern FORMATO = Pattern.compile("(?:(\\d{1,5})-)?(\\d{1,8})");

    /** Vacio si no se escribio nada; con otro formato, 400 en {@code numeroFiscal}. */
    public static Optional<NumeroFiscal> de(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        Matcher partes = FORMATO.matcher(texto.strip());
        if (!partes.matches()) {
            throw new DatosInvalidosException(Map.of(CAMPO, MENSAJE));
        }
        Integer puntoVenta = partes.group(1) == null ? null : Integer.valueOf(partes.group(1));
        return Optional.of(new NumeroFiscal(puntoVenta, Long.parseLong(partes.group(2))));
    }
}
