package com.ferreteria.cotizacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ultimo valor conocido de un tipo de cotizacion, por (moneda, casa): por ejemplo USD oficial, USD blue o EUR
 * oficial. Es GLOBAL a proposito: la cotizacion del mercado es la misma para todas las ferreterias, asi que no
 * tiene ferreteria_id.
 *
 * <p>{@code actualizadaEn} es la fecha que informa la fuente (la del mercado); {@code obtenidaEn}, el momento en que
 * el sistema obtuvo el valor con una respuesta exitosa. La antiguedad de la cotizacion se mide con
 * {@code obtenidaEn}: el mercado cerrado (fin de semana) no la hace "vieja".
 */
@Entity
@Table(name = "cotizacion")
public class Cotizacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** USD, EUR o BRL. */
    @Column(nullable = false, updatable = false, length = 3)
    private String moneda;

    /** Tipo dentro de la moneda: oficial, blue, bolsa (MEP), contadoconliqui, mayorista, tarjeta, cripto. */
    @Column(nullable = false, updatable = false, length = 30)
    private String casa;

    @Column(nullable = false, length = 60)
    private String nombre;

    /** Null si la fuente no informa ese lado. */
    @Column(precision = 15, scale = 4)
    private BigDecimal compra;

    @Column(precision = 15, scale = 4)
    private BigDecimal venta;

    /** Quien la informo: DOLARAPI o BLUELYTICS. */
    @Column(nullable = false, length = 20)
    private String fuente;

    @Column(name = "actualizada_en", nullable = false)
    private Instant actualizadaEn;

    @Column(name = "obtenida_en", nullable = false)
    private Instant obtenidaEn;

    protected Cotizacion() {}

    public Cotizacion(String moneda, String casa, String nombre, BigDecimal compra, BigDecimal venta, String fuente,
                      Instant actualizadaEn, Instant obtenidaEn) {
        this.moneda = moneda;
        this.casa = casa;
        actualizar(nombre, compra, venta, fuente, actualizadaEn, obtenidaEn);
    }

    /** Reemplaza el valor con uno recien obtenido; la moneda y la casa (la identidad del tipo) no cambian. */
    public void actualizar(String nombre, BigDecimal compra, BigDecimal venta, String fuente, Instant actualizadaEn,
                           Instant obtenidaEn) {
        this.nombre = nombre;
        this.compra = compra;
        this.venta = venta;
        this.fuente = fuente;
        this.actualizadaEn = actualizadaEn;
        this.obtenidaEn = obtenidaEn;
    }

    public Long getId() { return id; }
    public String getMoneda() { return moneda; }
    public String getCasa() { return casa; }
    public String getNombre() { return nombre; }
    public BigDecimal getCompra() { return compra; }
    public BigDecimal getVenta() { return venta; }
    public String getFuente() { return fuente; }
    public Instant getActualizadaEn() { return actualizadaEn; }
    public Instant getObtenidaEn() { return obtenidaEn; }
}
