package com.ferreteria.stock.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import com.ferreteria.common.HoraArgentina;
import java.time.LocalDateTime;

/**
 * Toda variacion de stock es un movimiento; el stock actual del articulo es su suma, cacheada. La cantidad
 * lleva signo (una venta resta). Registro inmutable: se escribe en la misma transaccion que la venta y no se
 * edita. Sin relaciones JPA: guarda ids, como HistorialPrecio.
 */
@Entity
@Table(name = "movimiento_stock")
public class MovimientoStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "articulo_id", nullable = false, updatable = false)
    private Long articuloId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private TipoMovimiento tipo;

    /** Con signo: negativa si el stock baja. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal cantidad;

    /** La venta que lo origino; null en los ajustes manuales. */
    @Column(name = "comprobante_id", updatable = false)
    private Long comprobanteId;

    /** El remito que lo origino (su salida, su anulacion o la venta que lo concilio); null en el resto. */
    @Column(name = "remito_id", updatable = false)
    private Long remitoId;

    /** La compra al proveedor que lo origino (CU-13); null en el resto. */
    @Column(name = "compra_id", updatable = false)
    private Long compraId;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(updatable = false, length = 200)
    private String motivo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = HoraArgentina.ahora();

    protected MovimientoStock() {}

    /** Salida por venta: guarda la cantidad vendida (positiva) como cantidad negativa. */
    public static MovimientoStock venta(
            Long ferreteriaId, Long articuloId, BigDecimal cantidadVendida, Long comprobanteId, Long usuarioId) {
        MovimientoStock movimiento = new MovimientoStock();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.articuloId = articuloId;
        movimiento.tipo = TipoMovimiento.VENTA;
        movimiento.cantidad = cantidadVendida.negate();
        movimiento.comprobanteId = comprobanteId;
        movimiento.usuarioId = usuarioId;
        return movimiento;
    }

    /** Salida por remito (RM-003): guarda la cantidad entregada (positiva) como cantidad negativa. */
    public static MovimientoStock remito(
            Long ferreteriaId, Long articuloId, BigDecimal cantidadEntregada, Long remitoId, Long usuarioId) {
        MovimientoStock movimiento = new MovimientoStock();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.articuloId = articuloId;
        movimiento.tipo = TipoMovimiento.REMITO;
        movimiento.cantidad = cantidadEntregada.negate();
        movimiento.remitoId = remitoId;
        movimiento.usuarioId = usuarioId;
        return movimiento;
    }

    /** Anular un remito (RM-005): devuelve la cantidad que salio, con signo positivo. */
    public static MovimientoStock anulacionRemito(
            Long ferreteriaId, Long articuloId, BigDecimal cantidadDevuelta, Long remitoId, Long usuarioId) {
        MovimientoStock movimiento = new MovimientoStock();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.articuloId = articuloId;
        movimiento.tipo = TipoMovimiento.ANULACION_REMITO;
        movimiento.cantidad = cantidadDevuelta;
        movimiento.remitoId = remitoId;
        movimiento.usuarioId = usuarioId;
        return movimiento;
    }

    /**
     * Lo que la venta mueve de mas o de menos respecto del remito que factura (RM-006): una VENTA con la diferencia ya
     * con signo (negativa si sale mas stock, positiva si vuelve), el comprobante, el remito y el motivo.
     */
    public static MovimientoStock diferenciaConRemito(
            Long ferreteriaId, Long articuloId, BigDecimal diferencia, Long comprobanteId, Long remitoId, String motivo,
            Long usuarioId) {
        if (diferencia == null || diferencia.signum() == 0) {
            throw new IllegalArgumentException("Una diferencia tiene que cambiar el stock.");
        }
        MovimientoStock movimiento = new MovimientoStock();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.articuloId = articuloId;
        movimiento.tipo = TipoMovimiento.VENTA;
        movimiento.cantidad = diferencia;
        movimiento.comprobanteId = comprobanteId;
        movimiento.remitoId = remitoId;
        movimiento.motivo = motivo;
        movimiento.usuarioId = usuarioId;
        return movimiento;
    }

    /** Entrada por compra (CU-13): la cantidad comprada, positiva, con la compra que la origino. */
    public static MovimientoStock compra(
            Long ferreteriaId, Long articuloId, BigDecimal cantidadComprada, Long compraId, Long usuarioId) {
        if (cantidadComprada == null || cantidadComprada.signum() <= 0) {
            throw new IllegalArgumentException("Una compra tiene que sumar stock.");
        }
        MovimientoStock movimiento = new MovimientoStock();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.articuloId = articuloId;
        movimiento.tipo = TipoMovimiento.COMPRA;
        movimiento.cantidad = cantidadComprada;
        movimiento.compraId = compraId;
        movimiento.usuarioId = usuarioId;
        return movimiento;
    }

    /** Ajuste manual (CU-09): la diferencia lleva signo y el motivo es obligatorio. Sin comprobante. */
    public static MovimientoStock ajuste(
            Long ferreteriaId, Long articuloId, BigDecimal diferencia, String motivo, Long usuarioId) {
        if (diferencia == null || diferencia.signum() == 0) {
            throw new IllegalArgumentException("Un ajuste tiene que cambiar el stock.");
        }
        if (motivo == null || motivo.isBlank() || motivo.length() > 200) {
            throw new IllegalArgumentException("Un ajuste necesita un motivo de hasta 200 caracteres.");
        }
        MovimientoStock movimiento = new MovimientoStock();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.articuloId = articuloId;
        movimiento.tipo = TipoMovimiento.AJUSTE;
        movimiento.cantidad = diferencia;
        movimiento.motivo = motivo;
        movimiento.usuarioId = usuarioId;
        return movimiento;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getArticuloId() { return articuloId; }
    public TipoMovimiento getTipo() { return tipo; }
    public BigDecimal getCantidad() { return cantidad; }
    public Long getComprobanteId() { return comprobanteId; }
    public Long getRemitoId() { return remitoId; }
    public Long getCompraId() { return compraId; }
    public Long getUsuarioId() { return usuarioId; }
    public String getMotivo() { return motivo; }
    public LocalDateTime getFecha() { return fecha; }
}
