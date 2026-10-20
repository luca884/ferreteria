package com.ferreteria.ventas.model;

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
 * El recibo de una cobranza de cuenta corriente (CC-036, CC-041): cuánto cobró la ferretería a un cliente, en qué medio y en
 * qué caja. Lo que describe la emisión es inmutable (columnas {@code updatable = false}, sin setters). Lo único que cambia
 * después es el estado ({@code EMITIDO} → {@code ANULADO}, CC-069) y la auditoría de esa transición (cuándo, quién, en qué
 * caja y por qué, CC-062): cinco columnas {@code updatable = true} que escribe {@link #anular}, el único mutador (CC-102);
 * precedente: {@code remito.estado}. Es un recibo interno, no un comprobante fiscal. Sin relaciones JPA: guarda ids y copia
 * el nombre del cliente (renombrarlo después no cambia el recibo). El medio solo puede ser EFECTIVO o TRANSFERENCIA: un medio
 * inesperado corrompería el efectivo esperado de la caja. A qué comprobantes se imputó lo guarda {@code Imputacion} (en
 * clientes).
 */
@Entity
@Table(name = "recibo")
public class Recibo {

    private static final int MAX_CLAVE = 36;
    private static final int MAX_NOMBRE = 120;
    private static final int MIN_MOTIVO = 5;
    private static final int MAX_MOTIVO = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    /** Número interno correlativo por ferretería (numerador propio de recibos). */
    @Column(nullable = false, updatable = false)
    private Long numero;

    @Column(name = "clave_idempotencia", nullable = false, updatable = false, length = MAX_CLAVE)
    private String claveIdempotencia;

    @Column(name = "cliente_id", nullable = false, updatable = false)
    private Long clienteId;

    /** Copia de la razón social del cliente al cobrar. */
    @Column(name = "cliente_nombre", nullable = false, updatable = false, length = MAX_NOMBRE)
    private String clienteNombre;

    /** La caja abierta en la que se cobró (el recibo entra en su esperado si es efectivo). */
    @Column(name = "caja_id", nullable = false, updatable = false)
    private Long cajaId;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private MedioPago medio;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private EstadoRecibo estado;

    /** Cuándo se anuló (Clock de la ferretería); null mientras está EMITIDO. */
    @Column(name = "fecha_anulacion")
    private LocalDateTime fechaAnulacion;

    @Column(name = "usuario_anulacion_id")
    private Long usuarioAnulacionId;

    /** La caja ABIERTA que absorbió la anulación (puede no ser la del cobro: {@link #cajaId}). */
    @Column(name = "caja_anulacion_id")
    private Long cajaAnulacionId;

    @Column(name = "motivo_anulacion", length = MAX_MOTIVO)
    private String motivoAnulacion;

    protected Recibo() {}

    /** Emite un recibo; la fecha la fija el llamador con su Clock. */
    public static Recibo emitir(Long ferreteriaId, long numero, String claveIdempotencia, Long clienteId,
            String clienteNombre, Long cajaId, Long usuarioId, LocalDateTime fecha, MedioPago medio, BigDecimal total) {
        if (ferreteriaId == null || clienteId == null || cajaId == null || usuarioId == null || fecha == null) {
            throw new IllegalArgumentException("El recibo necesita ferretería, cliente, caja, usuario y fecha.");
        }
        if (numero <= 0) {
            throw new IllegalArgumentException("El número del recibo tiene que ser positivo.");
        }
        if (claveIdempotencia == null || claveIdempotencia.isBlank() || claveIdempotencia.length() > MAX_CLAVE) {
            throw new IllegalArgumentException("La clave del recibo es obligatoria y de hasta " + MAX_CLAVE + " caracteres.");
        }
        if (clienteNombre == null || clienteNombre.isBlank() || clienteNombre.length() > MAX_NOMBRE) {
            throw new IllegalArgumentException(
                    "El nombre del cliente del recibo es obligatorio y de hasta " + MAX_NOMBRE + " caracteres.");
        }
        if (medio != MedioPago.EFECTIVO && medio != MedioPago.TRANSFERENCIA) {
            throw new IllegalArgumentException("Un recibo se cobra en efectivo o por transferencia.");
        }
        if (total == null || total.signum() <= 0 || total.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El total del recibo tiene que ser mayor que cero y de hasta 2 decimales.");
        }
        Recibo recibo = new Recibo();
        recibo.ferreteriaId = ferreteriaId;
        recibo.numero = numero;
        recibo.claveIdempotencia = claveIdempotencia;
        recibo.clienteId = clienteId;
        recibo.clienteNombre = clienteNombre;
        recibo.cajaId = cajaId;
        recibo.usuarioId = usuarioId;
        recibo.fecha = fecha;
        recibo.medio = medio;
        recibo.total = total;
        recibo.estado = EstadoRecibo.EMITIDO;
        return recibo;
    }

    /**
     * Anula el recibo (CC-062): pasa a ANULADO con quién, cuándo, en qué caja y por qué. Una sola vez: anular un recibo ya
     * anulado es un error de programación (el servicio devuelve el 409 antes, con quién y cuándo). El motivo se recorta y tiene
     * entre 5 y 200 caracteres, igual que {@code ck_recibo_anulacion}; si algo no vale, el recibo queda como estaba.
     */
    public void anular(Long usuarioId, LocalDateTime fecha, Long cajaId, String motivo) {
        if (estado == EstadoRecibo.ANULADO) {
            throw new IllegalStateException("El recibo ya está anulado.");
        }
        if (usuarioId == null || fecha == null || cajaId == null || motivo == null) {
            throw new IllegalArgumentException("La anulación necesita usuario, fecha, caja y motivo.");
        }
        String recortado = motivo.trim();
        if (recortado.length() < MIN_MOTIVO || recortado.length() > MAX_MOTIVO) {
            throw new IllegalArgumentException(
                    "El motivo de la anulación tiene entre " + MIN_MOTIVO + " y " + MAX_MOTIVO + " caracteres.");
        }
        this.estado = EstadoRecibo.ANULADO;
        this.fechaAnulacion = fecha;
        this.usuarioAnulacionId = usuarioId;
        this.cajaAnulacionId = cajaId;
        this.motivoAnulacion = recortado;
    }

    public boolean isAnulado() { return estado == EstadoRecibo.ANULADO; }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getNumero() { return numero; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Long getClienteId() { return clienteId; }
    public String getClienteNombre() { return clienteNombre; }
    public Long getCajaId() { return cajaId; }
    public Long getUsuarioId() { return usuarioId; }
    public LocalDateTime getFecha() { return fecha; }
    public MedioPago getMedio() { return medio; }
    public BigDecimal getTotal() { return total; }
    public EstadoRecibo getEstado() { return estado; }
    public LocalDateTime getFechaAnulacion() { return fechaAnulacion; }
    public Long getUsuarioAnulacionId() { return usuarioAnulacionId; }
    public Long getCajaAnulacionId() { return cajaAnulacionId; }
    public String getMotivoAnulacion() { return motivoAnulacion; }
}
