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
 * qué caja. Inmutable en el tramo 3a (columnas {@code updatable = false}, sin setters, sin estado: la anulación de 3b es un
 * ALTER aditivo). Es un recibo interno, no un comprobante fiscal. Sin relaciones JPA: guarda ids y copia el nombre del cliente
 * (renombrarlo después no cambia el recibo). El medio solo puede ser EFECTIVO o TRANSFERENCIA: un medio inesperado
 * corrompería el efectivo esperado de la caja. A qué comprobantes se imputó lo guarda {@code Imputacion} (en clientes).
 */
@Entity
@Table(name = "recibo")
public class Recibo {

    private static final int MAX_CLAVE = 36;
    private static final int MAX_NOMBRE = 120;

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
        return recibo;
    }

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
}
