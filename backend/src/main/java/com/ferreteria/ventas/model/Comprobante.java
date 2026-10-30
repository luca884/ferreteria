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
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * La venta (CU-07): un comprobante de tipo VENTA y estado SIN_FACTURAR (o NO_FACTURABLE si se marco asi). Desde CU-08
 * tambien la nota de credito y la anulacion interna ({@link #notaDeCredito}), de tipo NOTA_CREDITO. Los importes son los de
 * {@link TotalesVenta}: la suma de sus renglones. El cliente nulo es Consumidor Final. Sin relaciones JPA:
 * los items y el pago guardan el id del comprobante, como el resto del proyecto. Lo unico que cambiara
 * despues es el estado (fase 5, al facturar). La fecha (hora de Buenos Aires) la pone la venta con su {@code Clock}: es el
 * mismo instante de sus movimientos de stock y de su registro fiscal.
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

    /** El cliente elegido en el mostrador (CL-004); null es Consumidor Final. */
    @Column(name = "cliente_id", updatable = false)
    private Long clienteId;

    /** Copia de la razon social del cliente al vender: renombrarlo despues no cambia la venta. */
    @Column(name = "cliente_nombre", updatable = false, length = 120)
    private String clienteNombre;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    /** La caja abierta en la que se cobro (CU-10). Null solo en las ventas anteriores a la caja. */
    @Column(name = "caja_id", updatable = false)
    private Long cajaId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha;

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

    /** El remito desde el que se hizo la venta (RM-006); null si no sale de uno. */
    @Column(name = "remito_id", updatable = false)
    private Long remitoId;

    /**
     * La venta que anula esta NOTA_CREDITO (CU-08, V37: una sola por venta, de la misma ferreteria); null en una venta.
     */
    @Column(name = "comprobante_asociado_id", updatable = false)
    private Long comprobanteAsociadoId;

    /** El motivo de la anulacion interna (S13, hasta 200); null en la NC con CAE y en una venta. */
    @Column(name = "motivo_anulacion", updatable = false, length = 200)
    private String motivoAnulacion;

    protected Comprobante() {}

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, LocalDateTime fecha) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, BigDecimal.ZERO, fecha);
    }

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje, LocalDateTime fecha) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, descuentoPorcentaje, null,
                fecha);
    }

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje, Long presupuestoId, LocalDateTime fecha) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, descuentoPorcentaje,
                presupuestoId, null, fecha);
    }

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje, Long presupuestoId, Long remitoId, LocalDateTime fecha) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, descuentoPorcentaje,
                presupuestoId, remitoId, true, fecha);
    }

    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje, Long presupuestoId, Long remitoId, boolean facturable,
            LocalDateTime fecha) {
        return venta(ferreteriaId, numero, claveIdempotencia, usuarioId, totales, cajaId, descuentoPorcentaje,
                presupuestoId, remitoId, facturable, null, null, fecha);
    }

    /** {@code fecha}: la hora de Buenos Aires de la venta, del {@code Clock} de quien la registra. */
    public static Comprobante venta(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            Long cajaId, BigDecimal descuentoPorcentaje, Long presupuestoId, Long remitoId, boolean facturable,
            Long clienteId, String clienteNombre, LocalDateTime fecha) {
        Comprobante comprobante = new Comprobante();
        comprobante.fecha = Objects.requireNonNull(fecha, "Falta la fecha de la venta.");
        comprobante.clienteId = clienteId;
        comprobante.clienteNombre = clienteNombre;
        comprobante.presupuestoId = presupuestoId;
        comprobante.remitoId = remitoId;
        comprobante.cajaId = cajaId;
        comprobante.ferreteriaId = ferreteriaId;
        comprobante.tipo = TipoComprobante.VENTA;
        comprobante.estado = facturable ? EstadoComprobante.SIN_FACTURAR : EstadoComprobante.NO_FACTURABLE;
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

    /**
     * La nota de credito o la anulacion interna de la venta (CU-08, cu-08-nota-de-credito C3): una NOTA_CREDITO asociada a la
     * venta, con su numero interno, su clave, quien y cuando la hizo. Copia en positivo los totales, el descuento, el
     * recargo y el cliente de la venta (los lectores la restan); sin caja ni pagos (D11: la caja no cambia) y sin
     * presupuesto ni remito (cada uno es de una sola venta). Los renglones se copian aparte, con
     * {@link ItemComprobante#copiaParaLaNota}, cuando la nota ya tiene id.
     *
     * @param motivo el de la anulacion interna (sin espacios de mas), o null en la NC con CAE; la anulacion interna no se
     *     factura nunca (NO_FACTURABLE)
     * @throws IllegalArgumentException si no es una venta ya guardada o el motivo esta en blanco (quien anula valida antes)
     */
    public static Comprobante notaDeCredito(
            Comprobante venta, long numero, String claveIdempotencia, long usuarioId, LocalDateTime fecha, String motivo) {
        if (venta.tipo != TipoComprobante.VENTA || venta.id == null) {
            throw new IllegalArgumentException("Solo se anula una venta ya guardada.");
        }
        if (motivo != null && motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo de la anulación no puede estar en blanco.");
        }
        Comprobante nota = new Comprobante();
        nota.ferreteriaId = venta.ferreteriaId;
        nota.tipo = TipoComprobante.NOTA_CREDITO;
        nota.estado = motivo == null ? EstadoComprobante.SIN_FACTURAR : EstadoComprobante.NO_FACTURABLE;
        nota.numero = numero;
        nota.claveIdempotencia = Objects.requireNonNull(claveIdempotencia, "Falta la clave de la nota de crédito.");
        nota.usuarioId = usuarioId;
        nota.fecha = Objects.requireNonNull(fecha, "Falta la fecha de la nota de crédito.");
        nota.clienteId = venta.clienteId;
        nota.clienteNombre = venta.clienteNombre;
        nota.neto = venta.neto;
        nota.iva = venta.iva;
        nota.total = venta.total;
        nota.subtotal = venta.subtotal;
        nota.descuentoPorcentaje = venta.descuentoPorcentaje;
        nota.recargo = venta.recargo;
        nota.comprobanteAsociadoId = venta.id;
        nota.motivoAnulacion = motivo == null ? null : motivo.strip();
        return nota;
    }

    /** Una NOTA_CREDITO con motivo: la anulacion interna de una venta sin CAE (D12), sin documento fiscal. */
    public boolean esAnulacionInterna() {
        return tipo == TipoComprobante.NOTA_CREDITO && motivoAnulacion != null;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public TipoComprobante getTipo() { return tipo; }
    public EstadoComprobante getEstado() { return estado; }
    public Long getNumero() { return numero; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Long getClienteId() { return clienteId; }
    public String getClienteNombre() { return clienteNombre; }
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
    public Long getRemitoId() { return remitoId; }
    public Long getComprobanteAsociadoId() { return comprobanteAsociadoId; }
    public String getMotivoAnulacion() { return motivoAnulacion; }
}
