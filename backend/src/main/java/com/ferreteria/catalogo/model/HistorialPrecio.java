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

    /**
     * Moneda de los costos de esta entrada (la del articulo al momento del cambio). Los precios son siempre en
     * pesos.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private Moneda moneda = Moneda.ARS;

    /**
     * Moneda del costo ANTERIOR, solo cuando esta entrada es un cambio de moneda (la lista pasa un articulo de pesos a
     * dolares o al reves): asi el costo anterior y el nuevo no se leen como si fueran de la misma moneda. Null en el
     * resto.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "moneda_anterior", updatable = false, length = 3)
    private Moneda monedaAnterior;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    /**
     * Quien hizo el cambio. Null en el recalculo diario automatico por cotizacion: cuando alguna pantalla muestre
     * el historial, null se mapea a "Automatico" (hoy ninguna lo muestra).
     */
    @Column(name = "usuario_id", updatable = false)
    private Long usuarioId;

    protected HistorialPrecio() {}

    /**
     * Arma la entrada de un cambio del articulo; la ferreteria y la moneda salen del propio articulo.
     * {@code usuarioId} puede ser null solo en el recalculo automatico por cotizacion.
     */
    public static HistorialPrecio de(Articulo articulo, CambioPrecio cambio, OrigenPrecio origen, Long usuarioId) {
        return de(articulo, cambio, origen, usuarioId, null);
    }

    /**
     * Como {@link #de(Articulo, CambioPrecio, OrigenPrecio, Long)} para un cambio de moneda: {@code monedaAnterior} es
     * la que tenia el articulo antes (la nueva es la del articulo). Null si la moneda no cambio.
     */
    public static HistorialPrecio de(Articulo articulo, CambioPrecio cambio, OrigenPrecio origen, Long usuarioId,
                                     Moneda monedaAnterior) {
        HistorialPrecio historial = new HistorialPrecio();
        historial.ferreteriaId = articulo.getFerreteriaId();
        historial.articuloId = articulo.getId();
        historial.costoAnterior = cambio.costoAnterior();
        historial.costoNuevo = cambio.costoNuevo();
        historial.precioAnterior = cambio.precioAnterior();
        historial.precioNuevo = cambio.precioNuevo();
        historial.origen = origen;
        historial.moneda = articulo.getMoneda();
        historial.monedaAnterior = monedaAnterior;
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
    public Moneda getMoneda() { return moneda; }
    public Moneda getMonedaAnterior() { return monedaAnterior; }
    public LocalDateTime getFecha() { return fecha; }
    public Long getUsuarioId() { return usuarioId; }
}
