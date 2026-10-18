package com.ferreteria.ventas.model;

import com.ferreteria.common.HoraArgentina;
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

/**
 * El remito (CU-07, G55): un documento de entrega que descuenta el stock al emitirlo, no toca la caja y despues se
 * puede pasar a venta (RM-001). Los importes son los de {@link TotalesVenta}, sin recargo (depende de la forma de pago,
 * que se elige al facturar). Sin relaciones JPA: los renglones guardan el id del remito, como el resto del proyecto. Lo
 * unico que cambia despues de emitirlo es el estado.
 */
@Entity
@Table(name = "remito")
public class Remito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    /** Numero interno correlativo por ferreteria, propio: no se mezcla con el de las ventas ni el de los presupuestos. */
    @Column(nullable = false, updatable = false)
    private Long numero;

    @Column(name = "clave_idempotencia", nullable = false, updatable = false, length = 36)
    private String claveIdempotencia;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = HoraArgentina.ahora();

    /** Lo unico que cambia despues de emitirlo (RM-002). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EstadoRemito estado;

    /** El cliente elegido (CL-004); nulo si no se eligio uno. El nombre de abajo es una copia de su razon social. */
    @Column(name = "cliente_id", updatable = false)
    private Long clienteId;

    @Column(name = "cliente_nombre", updatable = false, length = 120)
    private String clienteNombre;

    @Column(updatable = false, length = 200)
    private String observaciones;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "descuento_porcentaje", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal descuentoPorcentaje;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal neto;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal iva;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal total;

    protected Remito() {}

    /** Un remito nuevo: siempre EMITIDO. */
    public static Remito emitir(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            BigDecimal descuentoPorcentaje, Long clienteId, String clienteNombre, String observaciones) {
        Remito remito = new Remito();
        remito.clienteId = clienteId;
        remito.ferreteriaId = ferreteriaId;
        remito.numero = numero;
        remito.claveIdempotencia = claveIdempotencia;
        remito.usuarioId = usuarioId;
        remito.estado = EstadoRemito.EMITIDO;
        remito.clienteNombre = clienteNombre;
        remito.observaciones = observaciones;
        remito.subtotal = totales.subtotal();
        remito.descuentoPorcentaje = descuentoPorcentaje;
        remito.neto = totales.neto();
        remito.iva = totales.iva();
        remito.total = totales.total();
        return remito;
    }

    public void cambiarEstado(EstadoRemito nuevo) {
        this.estado = nuevo;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getNumero() { return numero; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Long getUsuarioId() { return usuarioId; }
    public LocalDateTime getFecha() { return fecha; }
    public EstadoRemito getEstado() { return estado; }
    public Long getClienteId() { return clienteId; }
    public String getClienteNombre() { return clienteNombre; }
    public String getObservaciones() { return observaciones; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDescuentoPorcentaje() { return descuentoPorcentaje; }
    public BigDecimal getNeto() { return neto; }
    public BigDecimal getIva() { return iva; }
    public BigDecimal getTotal() { return total; }
}
