package com.ferreteria.clientes.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de la cuenta corriente de clientes hacia ventas (CC-001, CC-004 a CC-009). Solo tipos del JDK (lo vigila
 * ModulosArchTest). Los dos métodos exigen una transacción abierta ({@code Propagation.MANDATORY}): el cupo y el asiento tienen
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
     * El estado de la cuenta frente a un importe. {@code limite} y {@code disponible} son null si no está habilitada;
     * {@code exceso} es 0 si el importe alcanza.
     */
    record CupoCuenta(boolean habilitada, BigDecimal limite, BigDecimal saldo, BigDecimal disponible, BigDecimal exceso) {

        public boolean excede() {
            return exceso.signum() > 0;
        }
    }

    /**
     * Una venta a cuenta corriente ya guardada (con sus ids). {@code autorizadoPor} es el Dueño que autorizó un exceso del
     * límite, o null si no hubo exceso.
     */
    record AsientoVenta(Long clienteId, Long comprobanteId, List<CargoPago> pagos, Long usuarioId, Long autorizadoPor) {}

    /** Un pago a cuenta corriente: su id y la parte de la venta que se carga a la cuenta. */
    record CargoPago(Long pagoId, BigDecimal monto) {}
}
