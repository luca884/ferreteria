package com.ferreteria.facturacion.wsfe;

/**
 * El pedido seguro no llegó a ARCA (por ejemplo, un timeout de conexión): ARCA no lo vio, así que no autorizó nada. Para
 * {@code FECAESolicitar}, el intento cuenta como no enviado ({@code NO_LLEGO}) y el número no se consumió.
 */
public class PedidoNoLlegoException extends RuntimeException {

    public PedidoNoLlegoException(String mensaje) {
        super(mensaje);
    }

    public PedidoNoLlegoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
