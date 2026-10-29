package com.ferreteria.facturacion.arca;

import java.util.List;
import java.util.Objects;

/**
 * Una respuesta de WSFEv1 leída, con los {@code Events} que trajo aparte: los records del puerto ({@code facturacion.wsfe})
 * no los tienen, y el motor no los usa.
 *
 * @param respuesta la respuesta, en los records del puerto
 * @param eventos {@code Events}; vacío si no vino
 */
public record ConEventos<T>(T respuesta, List<Evt> eventos) {

    public ConEventos {
        Objects.requireNonNull(respuesta, "la respuesta");
        eventos = eventos == null ? List.of() : List.copyOf(eventos);
    }
}
