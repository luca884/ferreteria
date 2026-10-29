package com.ferreteria.facturacion.arca;

import java.util.Objects;

/**
 * WSAA contestó un SOAP Fault a {@code loginCms} (C3): el código de la especificación técnica ({@code faultcode} sin el
 * prefijo, por ejemplo {@code cms.cert.expired}) y el texto ({@code faultstring}). Qué hacer con cada código lo decide
 * {@link FallaWsaa} (C4). WSAA no procesó el pedido: no hay TA.
 */
public class FaultWsaaException extends RuntimeException {

    private final String codigo;
    private final String faultstring;

    public FaultWsaaException(String codigo, String faultstring) {
        super("WSAA rechazó loginCms: " + codigo + (faultstring.isEmpty() ? "" : " (" + faultstring + ")") + ".");
        this.codigo = Objects.requireNonNull(codigo, "el código");
        this.faultstring = faultstring;
    }

    /** El código, sin prefijo de namespace. */
    public String codigo() {
        return codigo;
    }

    /** El texto de ARCA; vacío si no vino. */
    public String faultstring() {
        return faultstring;
    }
}
