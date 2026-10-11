package com.ferreteria.catalogo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Rubro de articulos (Buloneria, Pintureria, Herramientas...). Su margen es el que heredan
 * los articulos que no tienen uno propio (ver reglas-de-negocio en docs/spec.md).
 */
@Entity
@Table(name = "rubro")
public class Rubro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(nullable = false, length = 80)
    private String nombre;

    /** Porcentaje: 35.00 significa 35 %. */
    @Column(name = "margen_porcentaje", nullable = false, precision = 6, scale = 2)
    private BigDecimal margenPorcentaje;

    protected Rubro() {}

    public Rubro(Long ferreteriaId, String nombre, BigDecimal margenPorcentaje) {
        this.ferreteriaId = ferreteriaId;
        this.nombre = nombre;
        this.margenPorcentaje = margenPorcentaje;
    }

    public void actualizar(String nombre, BigDecimal margenPorcentaje) {
        this.nombre = nombre;
        this.margenPorcentaje = margenPorcentaje;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getNombre() { return nombre; }
    public BigDecimal getMargenPorcentaje() { return margenPorcentaje; }
}
