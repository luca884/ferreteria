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
 * Un ajuste manual de la cuenta corriente de un cliente (GDS-20, G59): un Recargo o un Descuento con número propio, motivo
 * obligatorio y la clave de idempotencia del cliente HTTP. Registro inmutable (columnas {@code updatable = false}, sin
 * setters, nunca se borra: un ajuste mal cargado se compensa con el contrario). Su efecto en la cuenta vive en el libro (un
 * renglón {@code AJUSTE} que lo referencia) y en las imputaciones; el pendiente de un Recargo, como el de un comprobante, se
 * deriva y nunca se guarda. Referencia al cliente y al usuario solo por id, sin relaciones JPA.
 */
@Entity
@Table(name = "ajuste_cuenta")
public class AjusteCuenta {

    private static final int MIN_MOTIVO = 5;
    private static final int MAX_MOTIVO = 200;
    private static final int MAX_CLAVE = 36;
    /** El tope de DECIMAL(15,2): 13 dígitos enteros. */
    private static final BigDecimal TOPE_IMPORTE = new BigDecimal("10000000000000");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "cliente_id", nullable = false, updatable = false)
    private Long clienteId;

    @Column(nullable = false, updatable = false)
    private Long numero;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private TipoAjuste tipo;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal importe;

    @Column(nullable = false, updatable = false, length = MAX_MOTIVO)
    private String motivo;

    @Column(nullable = false, updatable = false, length = MAX_CLAVE)
    private String clave;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    protected AjusteCuenta() {}

    public static AjusteCuenta nuevo(Long ferreteriaId, Long clienteId, Long numero, TipoAjuste tipo, BigDecimal importe,
            String motivo, String clave, LocalDateTime fecha, Long usuarioId) {
        if (ferreteriaId == null || clienteId == null || tipo == null || fecha == null || usuarioId == null) {
            throw new IllegalArgumentException("El ajuste necesita ferretería, cliente, tipo, fecha y usuario.");
        }
        if (numero == null || numero <= 0) {
            throw new IllegalArgumentException("El número del ajuste tiene que ser positivo.");
        }
        if (importe == null || importe.signum() <= 0 || importe.stripTrailingZeros().scale() > 2
                || importe.compareTo(TOPE_IMPORTE) >= 0) {
            throw new IllegalArgumentException("El importe del ajuste tiene que ser mayor que cero y de hasta 2 decimales.");
        }
        String recortado = motivo == null ? "" : motivo.trim();
        if (recortado.length() < MIN_MOTIVO || recortado.length() > MAX_MOTIVO) {
            throw new IllegalArgumentException(
                    "El motivo del ajuste tiene entre " + MIN_MOTIVO + " y " + MAX_MOTIVO + " caracteres.");
        }
        if (clave == null || clave.isBlank() || clave.length() > MAX_CLAVE) {
            throw new IllegalArgumentException("La clave del ajuste es obligatoria y de hasta " + MAX_CLAVE + " caracteres.");
        }
        AjusteCuenta ajuste = new AjusteCuenta();
        ajuste.ferreteriaId = ferreteriaId;
        ajuste.clienteId = clienteId;
        ajuste.numero = numero;
        ajuste.tipo = tipo;
        ajuste.importe = importe;
        ajuste.motivo = recortado;
        ajuste.clave = clave;
        ajuste.fecha = fecha;
        ajuste.usuarioId = usuarioId;
        return ajuste;
    }

    /** «Recargo N.º n» o «Descuento N.º n»: el texto que copian el libro y las listas. */
    public String referencia() {
        return tipo == TipoAjuste.RECARGO ? Referencias.recargo(numero) : Referencias.descuento(numero);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getClienteId() { return clienteId; }
    public Long getNumero() { return numero; }
    public TipoAjuste getTipo() { return tipo; }
    public BigDecimal getImporte() { return importe; }
    public String getMotivo() { return motivo; }
    public String getClave() { return clave; }
    public LocalDateTime getFecha() { return fecha; }
    public Long getUsuarioId() { return usuarioId; }
}
