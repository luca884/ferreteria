package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * La oferta de un articulo (ofertas, S1): una capa aparte del precio normal, que se sigue recalculando como siempre.
 * Vale hasta el dia {@code hasta} inclusive (hora argentina) o, si es null, hasta que el Dueño la saca. Las reglas de
 * negocio las valida {@link Articulo#fijarOferta}; el constructor es una guarda del dominio.
 */
public record Oferta(TipoOferta tipo, BigDecimal valor, LocalDate hasta) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public Oferta {
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo de la oferta es obligatorio.");
        }
        if (valor == null) {
            throw new IllegalArgumentException("El valor de la oferta es obligatorio.");
        }
    }

    public boolean vigenteEl(LocalDate dia) {
        return hasta == null || !dia.isAfter(hasta);
    }

    /**
     * El precio que deja la oferta sobre ese precio normal, al centavo hacia arriba como los ajustes del precio; vacio
     * si no queda por debajo del normal ni por encima de 0 (por ejemplo, porque despues el normal bajo).
     */
    public Optional<BigDecimal> precioSobre(BigDecimal precioNormal) {
        BigDecimal bruto = switch (tipo) {
            case PRECIO_FIJO -> valor;
            case DESCUENTO_PORCENTAJE -> precioNormal.multiply(CIEN.subtract(valor)).divide(CIEN);
            case DESCUENTO_MONTO -> precioNormal.subtract(valor);
        };
        BigDecimal precio = PrecioCalculator.redondear(bruto, null);
        if (precio.signum() <= 0 || precio.compareTo(precioNormal) >= 0) {
            return Optional.empty();
        }
        return Optional.of(precio);
    }
}
