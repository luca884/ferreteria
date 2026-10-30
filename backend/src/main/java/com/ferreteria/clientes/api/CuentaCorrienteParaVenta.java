package com.ferreteria.clientes.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de la cuenta corriente de clientes hacia ventas (CC-001, CC-004 a CC-009). Solo tipos del JDK (lo vigila
 * ModulosArchTest). Los métodos exigen una transacción abierta ({@code Propagation.MANDATORY}): el cupo y el asiento tienen
 * que ir en la MISMA transacción que el comprobante, bajo el lock de la fila del cliente que tomó
 * {@link ClientesParaVenta#tomarParaVenta}; sin transacción lanzan {@code IllegalTransactionStateException}.
 *
 * <p>La regla «excede» (saldo + importe &gt; límite) vive en clientes. La política de quién puede autorizar el exceso, en
 * ventas: clientes no conoce a auth.
 */
public interface CuentaCorrienteParaVenta {

    /**
     * Cuánto cupo tiene la cuenta del cliente para este importe, leyendo SALDO y LÍMITE bajo lock (lectura actual, nunca una
     * foto de la transacción). No escribe nada. Un cliente inexistente o de otra ferretería es 404.
     */
    CupoCuenta verificarCupo(Long clienteId, BigDecimal importe);

    /**
     * Asienta la venta en el libro: UN movimiento VENTA (debe = monto) por cada pago a cuenta corriente, y suma el total al
     * saldo con un UPDATE relativo. Un pago ya asentado choca con la clave única y revierte la transacción.
     *
     * @throws IllegalArgumentException si no hay pagos o alguno tiene un monto no positivo
     */
    void asentarVenta(AsientoVenta asiento);

    /**
     * Asienta la nota de crédito (o la anulación interna) de una venta a cuenta corriente (CU-08 punto 4, CU-12): la llama la
     * anulación de ventas en SU transacción, con el cliente ya tomado y después de dar de alta el comprobante de la nota. Por
     * cada pago a cuenta corriente de la venta, UN movimiento NC al haber por su monto; baja el saldo por la suma con un UPDATE
     * relativo; imputa primero a la venta anulada lo que todavía debe y el resto queda a favor (lo consume sola la próxima venta
     * a cuenta corriente o el próximo Recargo). Un pago ya acreditado choca con la clave única y revierte la transacción.
     *
     * @throws ClienteDesactivadoException (para ventas, 422 «El cliente está desactivado.») si el cliente está desactivado
     * @throws com.ferreteria.common.exception.ResourceNotFoundException (404) si el cliente no existe en la ferretería
     * @throws IllegalArgumentException si faltan ids, no hay pagos o alguno tiene un monto no positivo
     * @throws IllegalStateException si los pagos no son exactamente los que la venta cargó a la cuenta de ese cliente
     */
    void asentarNotaDeCredito(AsientoNota asiento);

    /**
     * La nota de crédito de una venta a cuenta corriente, ya guardada. {@code notaId} es el comprobante NOTA_CREDITO,
     * {@code referencia} el texto que copia el libro («Nota de crédito N.º n» o «Anulación N.º n»), {@code ventaId} la venta
     * que anula y {@code pagos} sus pagos a cuenta corriente (solo esos: la parte en otros medios no se acredita).
     */
    record AsientoNota(Long clienteId, Long notaId, Long notaNumero, String referencia, Long ventaId, List<CargoPago> pagos,
            Long usuarioId) {}

    /**
     * El cliente de una venta a cuenta corriente está desactivado: su nota de crédito no se asienta (un cliente desactivado tiene
     * saldo 0 y la nota lo dejaría a favor; primero se reactiva). Ventas la traduce a 422 con {@link #MENSAJE}.
     */
    class ClienteDesactivadoException extends RuntimeException {

        public static final String MENSAJE = "El cliente está desactivado.";

        public ClienteDesactivadoException() {
            super(MENSAJE);
        }
    }

    /**
     * El estado de la cuenta frente a un importe. {@code limite} y {@code disponible} son null si no está habilitada;
     * {@code exceso} es 0 si el importe alcanza.
     */
    record CupoCuenta(boolean habilitada, BigDecimal limite, BigDecimal saldo, BigDecimal disponible, BigDecimal exceso) {

        public boolean excede() {
            return exceso.signum() > 0;
        }

        /** La cuenta está habilitada y el importe entra en el límite. */
        public boolean alcanza() {
            return habilitada && !excede();
        }
    }

    /**
     * Una venta a cuenta corriente ya guardada (con sus ids). {@code comprobanteNumero} es el número interno del comprobante
     * recién numerado: el libro copia la referencia «Venta N.º n». {@code autorizadoPor} es el Dueño que autorizó un exceso
     * del límite, o null si no hubo exceso.
     */
    record AsientoVenta(Long clienteId, Long comprobanteId, Long comprobanteNumero, List<CargoPago> pagos, Long usuarioId,
            Long autorizadoPor) {}

    /** Un pago a cuenta corriente: su id y la parte de la venta que se carga a la cuenta. */
    record CargoPago(Long pagoId, BigDecimal monto) {}
}
