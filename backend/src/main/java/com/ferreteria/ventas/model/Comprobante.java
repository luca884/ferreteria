package com.ferreteria.ventas.model;

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
 * La venta (CU-07): un comprobante de tipo VENTA y estado SIN_FACTURAR. Los importes son los de
 * {@link TotalesVenta}: la suma de sus renglones. El cliente nulo es Consumidor Final. Sin relaciones JPA:
 * los items y el pago guardan el id del comprobante, como el resto del proyecto. Lo unico que cambiara
 * despues es el estado (fase 5, al facturar).
 */
@Entity
@Table(name = "comprobante")
public class Comprobante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private TipoComprobante tipo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EstadoComprobante estado;

    /** Numero interno correlativo por ferreteria; no es el de ARCA. */
    @Column(nullable = false, updatable = false)
    private Long numero;

    @Column(name = "clave_idempotencia", nullable = false, updatable = false, length = 36)
    private String claveIdempotencia;

    /** Siempre null en el corte 1 (Consumidor Final); la tabla cliente es del corte 3. */
    @Column(name = "cliente_id", updatable = false)
    private Long clienteId;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    /** La caja abierta en la que se cobro (CU-10). Null solo en las ventas anteriores a la caja. */
    @Column(name = "caja_id", updatable = false)
    private Long cajaId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = HoraArgentina.ahora();

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal neto;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal iva;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal total;

    /** La suma de los brutos de los renglones, antes de cualquier descuento (GDS-12). */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    /** El descuento global en %, aplicado a cada renglon; el usuario que lo aplico es el de la venta. */
    @Column(name = "descuento_porcentaje", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal descuentoPorcentaje;

    /** El recargo de las formas de pago (GDS-11), ya sumado al total: subtotal - descuento + recargo = total. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal recargo;

    /** El presupuesto desde el que se hizo la venta (PP-006); null si no sale de uno. */
    @Column(name = "presupuesto_id", updatable = false)
    private Long presupuestoId;

    protected Comprobante() {}

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, BigDecimal.ZERO);
    }

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, descuentoPorcentaje, null);
    }

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje, Long presupuestoId) {
        Comprobante comprobante = new Comprobante();
        comprobante.presupuestoId = presupuestoId;
        comprobante.cajaId = cajaId;
        comprobante.ferreteriaId = ferreteriaId;
        comprobante.tipo = TipoComprobante.VENTA;
        comprobante.estado = EstadoComprobante.SIN_FACTURAR;
        comprobante.numero = numero;
        comprobante.claveIdempotencia = claveIdempotencia;
        comprobante.usuarioId = usuarioId;
        comprobante.neto = totales.neto();
        comprobante.iva = totales.iva();
        comprobante.total = totales.total();
        comprobante.subtotal = totales.subtotal();
        comprobante.descuentoPorcentaje = descuentoPorcentaje;
        comprobante.recargo = totales.recargo();
        return comprobante;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public TipoComprobante getTipo() { return tipo; }
    public EstadoComprobante getEstado() { return estado; }
    public Long getNumero() { return numero; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Long getClienteId() { return clienteId; }
    public Long getUsuarioId() { return usuarioId; }
    public Long getCajaId() { return cajaId; }
    public LocalDateTime getFecha() { return fecha; }
    public BigDecimal getNeto() { return neto; }
    public BigDecimal getIva() { return iva; }
    public BigDecimal getTotal() { return total; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDescuentoPorcentaje() { return descuentoPorcentaje; }
    public BigDecimal getRecargo() { return recargo; }
    public Long getPresupuestoId() { return presupuestoId; }
}
