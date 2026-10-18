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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * El presupuesto (CU-07, GDS-13): una cotizacion con estados que no toca el stock ni la caja y despues se puede pasar a
 * venta. Los importes son los de {@link TotalesVenta}, sin recargo (depende de la forma de pago, que se elige al
 * cobrar). Sin relaciones JPA: los renglones guardan el id del presupuesto, como el resto del proyecto. Lo unico que cambia
 * despues de emitirlo es el estado.
 */
@Entity
@Table(name = "presupuesto")
public class Presupuesto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    /** Numero interno correlativo por ferreteria, propio: no se mezcla con el de las ventas. */
    @Column(nullable = false, updatable = false)
    private Long numero;

    @Column(name = "clave_idempotencia", nullable = false, updatable = false, length = 36)
    private String claveIdempotencia;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = HoraArgentina.ahora();

    /** Ultimo dia de validez. Vencer no bloquea nada: avisa (PP-004). */
    @Column(nullable = false, updatable = false)
    private LocalDate vence;

    /** Lo unico que cambia despues de emitirlo (PP-002). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EstadoPresupuesto estado;

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

    protected Presupuesto() {}

    /** Un presupuesto nuevo: siempre PENDIENTE, y vence {@code diasValidez} dias despues de hoy (hora argentina). */
    public static Presupuesto emitir(
            Long ferreteriaId, long numero, String claveIdempotencia, Long usuarioId, TotalesVenta totales,
            BigDecimal descuentoPorcentaje, int diasValidez, Long clienteId, String clienteNombre,
            String observaciones) {
        Presupuesto presupuesto = new Presupuesto();
        presupuesto.clienteId = clienteId;
        presupuesto.ferreteriaId = ferreteriaId;
        presupuesto.numero = numero;
        presupuesto.claveIdempotencia = claveIdempotencia;
        presupuesto.usuarioId = usuarioId;
        presupuesto.vence = presupuesto.fecha.toLocalDate().plusDays(diasValidez);
        presupuesto.estado = EstadoPresupuesto.PENDIENTE;
        presupuesto.clienteNombre = clienteNombre;
        presupuesto.observaciones = observaciones;
        presupuesto.subtotal = totales.subtotal();
        presupuesto.descuentoPorcentaje = descuentoPorcentaje;
        presupuesto.neto = totales.neto();
        presupuesto.iva = totales.iva();
        presupuesto.total = totales.total();
        return presupuesto;
    }

    public void cambiarEstado(EstadoPresupuesto nuevo) {
        this.estado = nuevo;
    }

    /** Pendiente o aceptado cuyo ultimo dia de validez ya paso. Uno rechazado o facturado no esta "vencido". */
    public boolean estaVencido(LocalDate hoy) {
        return (estado == EstadoPresupuesto.PENDIENTE || estado == EstadoPresupuesto.ACEPTADO) && vence.isBefore(hoy);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getNumero() { return numero; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Long getUsuarioId() { return usuarioId; }
    public LocalDateTime getFecha() { return fecha; }
    public LocalDate getVence() { return vence; }
    public EstadoPresupuesto getEstado() { return estado; }
    public Long getClienteId() { return clienteId; }
    public String getClienteNombre() { return clienteNombre; }
    public String getObservaciones() { return observaciones; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDescuentoPorcentaje() { return descuentoPorcentaje; }
    public BigDecimal getNeto() { return neto; }
    public BigDecimal getIva() { return iva; }
    public BigDecimal getTotal() { return total; }
}
