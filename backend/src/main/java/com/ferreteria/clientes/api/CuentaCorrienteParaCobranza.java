package com.ferreteria.clientes.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de la cuenta corriente de clientes hacia la cobranza de ventas (CC-026, CC-027, CC-030, CC-033, CC-037, CC-042).
 * Solo tipos del JDK (lo vigila ModulosArchTest). Es un puerto APARTE de {@link CuentaCorrienteParaVenta}: la venta y la
 * cobranza tienen actores y contratos distintos.
 *
 * <p>{@link #asentarCobro} exige una transacción abierta ({@code Propagation.MANDATORY}): el recibo, las imputaciones, el
 * COBRO del libro y el saldo tienen que ir en la MISMA transacción, bajo el lock de la fila del cliente que tomó
 * {@link ClientesParaVenta#tomarParaVenta} («primer paso de toda operación que mueve la cuenta: venta y cobranza»). Igual el
 * escritor se defiende solo: re-toma la fila del cliente y lee el imputado con lectura actual, así que no depende de que el
 * llamador haya respetado el orden. Sin transacción lanza {@code IllegalTransactionStateException}.
 *
 * <p>No declara ningún método de nota de crédito (ADR-12): el modelo (imputación por recibo) admite que la fase de
 * facturación agregue el suyo con un ALTER aditivo.
 */
public interface CuentaCorrienteParaCobranza {

    /**
     * Asienta un cobro ya numerado: valida las imputaciones contra los pendientes ACTUALES (bajo lock), guarda una imputación
     * por comprobante, UN movimiento COBRO (haber = total del recibo, con su recibo y la referencia «Recibo N.º n») y baja el
     * saldo con un UPDATE relativo. Sin imputaciones es válido: todo el total queda «a cuenta» (adelanto), sin chequear saldo
     * ni límite (el límite frena ventas, no cobros).
     *
     * @return las imputaciones asentadas, por comprobante ascendente, con la referencia de cada comprobante
     * @throws com.ferreteria.common.exception.ResourceNotFoundException (404) si el cliente no existe en la ferretería o algún
     *     comprobante no tiene débito VENTA en la cuenta de ESE cliente
     * @throws ImputacionInvalidaException si algún comprobante tiene menos pendiente que el importe imputado (incluye pendiente
     *     0); trae la deuda actual de todos los afectados
     * @throws IllegalArgumentException si los argumentos están mal formados (total o importe no positivo o con más de 2
     *     decimales, comprobante repetido, Σ imputaciones &gt; total): ventas ya los validó con 400
     */
    List<ImputacionAsentada> asentarCobro(AsientoCobro asiento);

    /**
     * Asienta la anulación de un cobro (CC-062, CC-063, CC-068): bajo el lock del cliente, da de baja la vigencia de TODAS las
     * imputaciones del recibo (los comprobantes vuelven a quedar pendientes), guarda UN contra-asiento {@code ANULACION}
     * (debe = total del recibo, con su referencia «Anulación Recibo N.º n» y el motivo) y SUBE el saldo con un UPDATE
     * relativo. {@code MANDATORY}: ventas ya marcó el recibo {@code ANULADO} en la misma transacción, bajo el mismo lock.
     * Se defiende sola: re-toma la fila del cliente y relee el libro y las imputaciones con lectura actual, así que rechaza
     * una doble anulación aunque quien llama haya respetado mal el orden. NO chequea límite (CC-065: anular puede dejar al
     * cliente excedido) ni si el cliente está activo: eso lo decide ventas.
     *
     * @throws com.ferreteria.common.exception.ResourceNotFoundException (404) si el cliente no existe en la ferretería
     * @throws IllegalStateException si el recibo no tiene exactamente un COBRO del cliente con haber = total, si ya hay una
     *     ANULACION, si alguna imputación ya no es vigente o si el UPDATE de vigencia o de saldo no afecta lo esperado:
     *     rompería I2b/I2e y es un estado ilegal (500 y rollback), no un 4xx
     * @throws IllegalArgumentException si los argumentos están mal formados (ids nulos, total no positivo o con más de 2
     *     decimales, motivo vacío o fuera de 5 a 200 caracteres): ventas ya los validó con 400
     */
    AnulacionAsentada asentarAnulacion(AsientoAnulacion asiento);

    /**
     * Las imputaciones de un recibo, en el orden en que se asentaron, con la referencia del comprobante. Todas, también las
     * no vigentes (el recibo anulado se reimprime con sus imputaciones originales): sirve para armar el recibo y para comparar
     * una cobranza repetida. Lectura. Vacío si el recibo quedó todo «a cuenta».
     */
    List<ImputacionAsentada> imputacionesDeRecibo(Long reciboId);

    /**
     * Un cobro ya numerado y guardado como recibo. {@code reciboNumero} es el número interno: el libro copia la referencia
     * «Recibo N.º n». {@code total} es lo que se cobró; {@code imputaciones} puede ser vacía.
     */
    record AsientoCobro(Long clienteId, Long reciboId, Long reciboNumero, BigDecimal total, List<Imputar> imputaciones,
            Long usuarioId) {}

    /**
     * La anulación de un cobro ya marcado {@code ANULADO} por ventas. {@code reciboNumero} es el número interno (la referencia
     * «Anulación Recibo N.º n»); {@code total} es el total del recibo (lo que vuelve a ser deuda); {@code motivo} ya viene
     * recortado.
     */
    record AsientoAnulacion(Long clienteId, Long reciboId, Long reciboNumero, BigDecimal total, String motivo,
            Long usuarioId) {}

    /** Lo asentado por una anulación: el id del contra-asiento y los comprobantes que volvieron a quedar pendientes. */
    record AnulacionAsentada(Long movimientoId, List<Long> comprobantesLiberados) {}

    /** Un renglón pedido: cuánto del cobro se aplica a este comprobante. */
    record Imputar(Long comprobanteId, BigDecimal importe) {}

    /** Una imputación ya guardada, con la referencia («Venta N.º n») copiada del débito del comprobante. */
    record ImputacionAsentada(Long comprobanteId, String referencia, BigDecimal importe) {}

    /**
     * Se pidió imputar más de lo que le queda por pagar a algún comprobante: «la deuda cambió desde que la viste».
     * {@link #deudas()} trae la deuda ACTUAL de todos los comprobantes afectados, en orden de comprobante, para que quien
     * llama refresque la pantalla.
     */
    class ImputacionInvalidaException extends RuntimeException {

        public enum Motivo { EXCEDE_DEUDA }

        /** El pendiente actual de un comprobante. */
        public record DeudaActual(Long comprobanteId, String referencia, BigDecimal pendiente) {}

        private final Motivo motivo;
        private final List<DeudaActual> deudas;

        public ImputacionInvalidaException(Motivo motivo, List<DeudaActual> deudas) {
            super("La deuda de algún comprobante cambió: se quiso imputar más de lo que queda por pagar.");
            this.motivo = motivo;
            this.deudas = List.copyOf(deudas);
        }

        public Motivo motivo() {
            return motivo;
        }

        public List<DeudaActual> deudas() {
            return deudas;
        }
    }
}
