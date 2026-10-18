package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Contador de la numeracion de los presupuestos: una fila por ferreteria, aparte de la de las ventas. Solo se mueve con el
 * UPSERT nativo de {@link com.ferreteria.ventas.repository.NumeradorPresupuestoRepository}, nunca desde la entidad.
 */
@Entity
@Table(name = "numerador_presupuesto")
public class NumeradorPresupuesto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "ultimo_numero", nullable = false, updatable = false)
    private Long ultimoNumero;

    protected NumeradorPresupuesto() {}

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getUltimoNumero() { return ultimoNumero; }
}
