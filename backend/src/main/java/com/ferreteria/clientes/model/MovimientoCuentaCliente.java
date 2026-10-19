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
 * comprobante y al pago solo por id, sin relaciones JPA: clientes no conoce a ventas. El saldo del cliente es la suma de
 * debe - haber de estos renglones.
 */
@Entity
@Table(name = "movimiento_cuenta_cliente")
public class MovimientoCuentaCliente {

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
            BigDecimal monto, LocalDateTime fecha, Long usuarioId, Long autorizadoPor) {
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
        movimiento.fecha = fecha;
        movimiento.usuarioId = usuarioId;
        movimiento.excedioLimite = autorizadoPor != null;
        movimiento.autorizadoPor = autorizadoPor;
        return movimiento;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getClienteId() { return clienteId; }
    public TipoMovimientoCuenta getTipo() { return tipo; }
    public BigDecimal getDebe() { return debe; }
    public BigDecimal getHaber() { return haber; }
    public Long getComprobanteId() { return comprobanteId; }
    public Long getPagoId() { return pagoId; }
    public LocalDateTime getFecha() { return fecha; }
    public Long getUsuarioId() { return usuarioId; }
    public boolean isExcedioLimite() { return excedioLimite; }
    public Long getAutorizadoPor() { return autorizadoPor; }
}
