package com.ferreteria.facturacion.wsfe;

/**
 * El pedido salió pero no se sabe si ARCA lo procesó (por ejemplo, un timeout de lectura o un 5xx). Para
 * {@code FECAESolicitar} nunca se reenvía a ciegas: el registro pasa a {@code EN_VERIFICACION} y se resuelve con
 * {@code FECompUltimoAutorizado} y {@code FECompConsultar}.
 */
public class ResultadoInciertoException extends RuntimeException {

    public ResultadoInciertoException(String mensaje) {
        super(mensaje);
    }

    public ResultadoInciertoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
