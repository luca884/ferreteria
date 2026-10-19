package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Contador de la numeración de recibos de cobranza: una fila por ferretería. Solo se mueve con el UPSERT nativo de
 * NumeradorReciboRepository, nunca desde la entidad (mismo patrón que {@link NumeradorComprobante}).
 */
@Entity
@Table(name = "numerador_recibo")
public class NumeradorRecibo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "ultimo_numero", nullable = false, updatable = false)
    private Long ultimoNumero;

    protected NumeradorRecibo() {}

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getUltimoNumero() { return ultimoNumero; }
}
