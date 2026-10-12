package com.ferreteria.catalogo.model;

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
 * Registro inmutable de un cambio de costo o de precio final de un articulo. Se escribe en la
 * misma transaccion que el cambio; no hay endpoints de escritura.
 */
@Entity
@Table(name = "historial_precio")
public class HistorialPrecio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "articulo_id", nullable = false, updatable = false)
    private Long articuloId;

    /** Null en el alta del articulo. */
    @Column(name = "costo_anterior", updatable = false, precision = 15, scale = 2)
    private BigDecimal costoAnterior;

    @Column(name = "costo_nuevo", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal costoNuevo;

    /** Null en el alta del articulo. */
    @Column(name = "precio_anterior", updatable = false, precision = 15, scale = 2)
    private BigDecimal precioAnterior;

    @Column(name = "precio_nuevo", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal precioNuevo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private OrigenPrecio origen;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    protected HistorialPrecio() {}

    /** Arma la entrada de un cambio del articulo; la ferreteria sale del propio articulo. */
    public static HistorialPrecio de(Articulo articulo, CambioPrecio cambio, OrigenPrecio origen, Long usuarioId) {
        HistorialPrecio historial = new HistorialPrecio();
        historial.ferreteriaId = articulo.getFerreteriaId();
        historial.articuloId = articulo.getId();
        historial.costoAnterior = cambio.costoAnterior();
        historial.costoNuevo = cambio.costoNuevo();
        historial.precioAnterior = cambio.precioAnterior();
        historial.precioNuevo = cambio.precioNuevo();
        historial.origen = origen;
        historial.usuarioId = usuarioId;
        return historial;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getArticuloId() { return articuloId; }
    public BigDecimal getCostoAnterior() { return costoAnterior; }
    public BigDecimal getCostoNuevo() { return costoNuevo; }
    public BigDecimal getPrecioAnterior() { return precioAnterior; }
    public BigDecimal getPrecioNuevo() { return precioNuevo; }
    public OrigenPrecio getOrigen() { return origen; }
    public LocalDateTime getFecha() { return fecha; }
    public Long getUsuarioId() { return usuarioId; }
}
