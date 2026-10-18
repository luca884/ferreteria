package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Contador de la numeracion de los remitos: una fila por ferreteria, aparte de la de las ventas y la de los presupuestos.
 * Solo se mueve con el UPSERT nativo de {@link com.ferreteria.ventas.repository.NumeradorRemitoRepository}, nunca desde
 * la entidad.
 */
@Entity
@Table(name = "numerador_remito")
public class NumeradorRemito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "ultimo_numero", nullable = false, updatable = false)
    private Long ultimoNumero;

    protected NumeradorRemito() {}

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getUltimoNumero() { return ultimoNumero; }
}
