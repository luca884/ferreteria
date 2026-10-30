package com.ferreteria.clientes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un renglón del libro de cuenta corriente del cliente (CC-001). Registro inmutable y append-only, como MovimientoStock:
 * no se edita ni se borra (columnas {@code updatable = false}, sin setters). Lleva debe O haber, nunca los dos. Referencia al
 * comprobante, al pago y al recibo solo por id, sin relaciones JPA: clientes no conoce a ventas. {@code referencia} es el texto
 * copiado de lo que originó el renglón («Venta N.º n», «Recibo N.º n», «Anulación Recibo N.º n»; ver {@link Referencias}).
 * {@code motivo} solo lo llevan ANULACION (y el AJUSTE de 3b-2): ck_mcc_motivo. El saldo del cliente es la
 * suma de debe - haber de estos renglones.
 */
@Entity
@Table(name = "movimiento_cuenta_cliente")
public class MovimientoCuentaCliente {

    private static final int MAX_REFERENCIA = 60;
    private static final int MIN_MOTIVO = 5;
    private static final int MAX_MOTIVO = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "cliente_id", nullable = false, updatable = false)
    private Long clienteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private TipoMovimientoCuenta tipo;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal debe;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal haber;

    @Column(name = "comprobante_id", updatable = false)
    private Long comprobanteId;

    @Column(name = "pago_id", updatable = false)
    private Long pagoId;

    /** El recibo de un COBRO (o de su ANULACION); null en el resto. */
    @Column(name = "recibo_id", updatable = false)
    private Long reciboId;

    @Column(nullable = false, updatable = false, length = MAX_REFERENCIA)
    private String referencia;

    /** El ajuste de un AJUSTE (V28, ck_mcc_ajuste_ref); null en el resto. */
    @Column(name = "ajuste_id", updatable = false)
    private Long ajusteId;

    /** Por qué se anuló el cobro (ANULACION) o se hizo el ajuste (AJUSTE); null en VENTA, COBRO y NC. */
    @Column(updatable = false, length = MAX_MOTIVO)
    private String motivo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(name = "excedio_limite", nullable = false, updatable = false)
    private boolean excedioLimite;

    @Column(name = "autorizado_por", updatable = false)
    private Long autorizadoPor;

    protected MovimientoCuentaCliente() {}

    /**
     * La venta a cuenta de UN pago: debe = monto, haber = 0. {@code autorizadoPor} es el Dueño que autorizó un exceso del
     * límite, o null si no hubo exceso; excedio_limite es true si y solo si hay autorizador.
     */
    public static MovimientoCuentaCliente venta(Long ferreteriaId, Long clienteId, Long comprobanteId, Long pagoId,
            String referencia, BigDecimal monto, LocalDateTime fecha, Long usuarioId, Long autorizadoPor) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de una venta a cuenta corriente tiene que ser mayor que cero.");
        }
        if (comprobanteId == null || pagoId == null) {
            throw new IllegalArgumentException("Una venta a cuenta corriente referencia su comprobante y su pago.");
        }
        MovimientoCuentaCliente movimiento = new MovimientoCuentaCliente();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.clienteId = clienteId;
        movimiento.tipo = TipoMovimientoCuenta.VENTA;
        movimiento.debe = monto;
        movimiento.haber = BigDecimal.ZERO;
        movimiento.comprobanteId = comprobanteId;
        movimiento.pagoId = pagoId;
        movimiento.referencia = referenciaValida(referencia);
        movimiento.fecha = fecha;
        movimiento.usuarioId = usuarioId;
        movimiento.excedioLimite = autorizadoPor != null;
        movimiento.autorizadoPor = autorizadoPor;
        return movimiento;
    }

    /**
     * El cobro de UN recibo: debe = 0, haber = monto (el total del recibo, aunque impute a varios comprobantes), con su recibo
     * y su referencia; sin comprobante, pago ni exceso. Hay un COBRO por recibo (UNIQUE (recibo_id, tipo) en la base).
     */
    public static MovimientoCuentaCliente cobro(Long ferreteriaId, Long clienteId, Long reciboId, String referencia,
            BigDecimal monto, LocalDateTime fecha, Long usuarioId) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de un cobro tiene que ser mayor que cero.");
        }
        if (reciboId == null) {
            throw new IllegalArgumentException("Un cobro referencia su recibo.");
        }
        MovimientoCuentaCliente movimiento = new MovimientoCuentaCliente();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.clienteId = clienteId;
        movimiento.tipo = TipoMovimientoCuenta.COBRO;
        movimiento.debe = BigDecimal.ZERO;
        movimiento.haber = monto;
        movimiento.reciboId = reciboId;
        movimiento.referencia = referenciaValida(referencia);
        movimiento.fecha = fecha;
        movimiento.usuarioId = usuarioId;
        movimiento.excedioLimite = false;
        return movimiento;
    }

    /**
     * El contra-asiento de UN cobro anulado: debe = monto (el total del recibo, que vuelve a ser deuda del cliente), haber = 0,
     * con el recibo, su referencia y el motivo (recortado, de 5 a 200 caracteres); sin comprobante, pago ni exceso. Hay a lo
     * sumo una ANULACION por recibo (UNIQUE (recibo_id, tipo) en la base).
     */
    public static MovimientoCuentaCliente anulacion(Long ferreteriaId, Long clienteId, Long reciboId, String referencia,
            BigDecimal monto, String motivo, LocalDateTime fecha, Long usuarioId) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de una anulación tiene que ser mayor que cero.");
        }
        if (reciboId == null) {
            throw new IllegalArgumentException("Una anulación referencia su recibo.");
        }
        String recortado = motivo == null ? "" : motivo.trim();
        if (recortado.length() < MIN_MOTIVO || recortado.length() > MAX_MOTIVO) {
            throw new IllegalArgumentException(
                    "El motivo de la anulación tiene entre " + MIN_MOTIVO + " y " + MAX_MOTIVO + " caracteres.");
        }
        MovimientoCuentaCliente movimiento = new MovimientoCuentaCliente();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.clienteId = clienteId;
        movimiento.tipo = TipoMovimientoCuenta.ANULACION;
        movimiento.debe = monto;
        movimiento.haber = BigDecimal.ZERO;
        movimiento.reciboId = reciboId;
        movimiento.referencia = referenciaValida(referencia);
        movimiento.motivo = recortado;
        movimiento.fecha = fecha;
        movimiento.usuarioId = usuarioId;
        movimiento.excedioLimite = false;
        return movimiento;
    }

    /**
     * El renglón de un ajuste manual (GDS-20): un Recargo va al debe (sube la deuda) y un Descuento al haber (la baja), con el
     * ajuste, su referencia y el motivo (recortado, de 5 a 200 caracteres); sin comprobante, recibo, pago ni exceso. Hay un
     * AJUSTE por ajuste (UNIQUE (ajuste_id) en la base).
     */
    public static MovimientoCuentaCliente ajuste(Long ferreteriaId, Long clienteId, Long ajusteId, TipoAjuste tipo,
            String referencia, BigDecimal monto, String motivo, LocalDateTime fecha, Long usuarioId) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de un ajuste tiene que ser mayor que cero.");
        }
        if (ajusteId == null || tipo == null) {
            throw new IllegalArgumentException("Un ajuste referencia su ajuste y su tipo.");
        }
        String recortado = motivo == null ? "" : motivo.trim();
        if (recortado.length() < MIN_MOTIVO || recortado.length() > MAX_MOTIVO) {
            throw new IllegalArgumentException(
                    "El motivo del ajuste tiene entre " + MIN_MOTIVO + " y " + MAX_MOTIVO + " caracteres.");
        }
        MovimientoCuentaCliente movimiento = new MovimientoCuentaCliente();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.clienteId = clienteId;
        movimiento.tipo = TipoMovimientoCuenta.AJUSTE;
        movimiento.debe = tipo == TipoAjuste.RECARGO ? monto : BigDecimal.ZERO;
        movimiento.haber = tipo == TipoAjuste.DESCUENTO ? monto : BigDecimal.ZERO;
        movimiento.ajusteId = ajusteId;
        movimiento.referencia = referenciaValida(referencia);
        movimiento.motivo = recortado;
        movimiento.fecha = fecha;
        movimiento.usuarioId = usuarioId;
        movimiento.excedioLimite = false;
        return movimiento;
    }

    /**
     * La nota de crédito (o la anulación interna) de UN pago a cuenta corriente de la venta anulada (CU-08): debe = 0, haber =
     * monto (el de ese pago), con el pago de la venta, el comprobante NOTA_CREDITO y la referencia («Nota de crédito N.º n» o
     * «Anulación N.º n»); sin recibo, ajuste, motivo ni exceso. Un pago se acredita una sola vez (UNIQUE (pago_id, tipo)).
     */
    public static MovimientoCuentaCliente nc(Long ferreteriaId, Long clienteId, Long notaId, Long pagoId, String referencia,
            BigDecimal monto, LocalDateTime fecha, Long usuarioId) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de una nota de crédito tiene que ser mayor que cero.");
        }
        if (notaId == null || pagoId == null) {
            throw new IllegalArgumentException("Una nota de crédito referencia su comprobante y el pago que acredita.");
        }
        MovimientoCuentaCliente movimiento = new MovimientoCuentaCliente();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.clienteId = clienteId;
        movimiento.tipo = TipoMovimientoCuenta.NC;
        movimiento.debe = BigDecimal.ZERO;
        movimiento.haber = monto;
        movimiento.comprobanteId = notaId;
        movimiento.pagoId = pagoId;
        movimiento.referencia = referenciaValida(referencia);
        movimiento.fecha = fecha;
        movimiento.usuarioId = usuarioId;
        movimiento.excedioLimite = false;
        return movimiento;
    }

    private static String referenciaValida(String referencia) {
        if (referencia == null || referencia.isBlank() || referencia.length() > MAX_REFERENCIA) {
            throw new IllegalArgumentException(
                    "La referencia del movimiento es obligatoria y de hasta " + MAX_REFERENCIA + " caracteres.");
        }
        return referencia;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getClienteId() { return clienteId; }
    public TipoMovimientoCuenta getTipo() { return tipo; }
    public BigDecimal getDebe() { return debe; }
    public BigDecimal getHaber() { return haber; }
    public Long getComprobanteId() { return comprobanteId; }
    public Long getPagoId() { return pagoId; }
    public Long getReciboId() { return reciboId; }
    public Long getAjusteId() { return ajusteId; }
    public String getReferencia() { return referencia; }
    public String getMotivo() { return motivo; }
    public LocalDateTime getFecha() { return fecha; }
    public Long getUsuarioId() { return usuarioId; }
    public boolean isExcedioLimite() { return excedioLimite; }
    public Long getAutorizadoPor() { return autorizadoPor; }
}
