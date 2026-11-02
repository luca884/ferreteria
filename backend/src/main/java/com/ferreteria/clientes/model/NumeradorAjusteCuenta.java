package com.ferreteria.clientes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * El contador de números de un tipo de ajuste en una ferretería (una fila por ferretería y tipo, V40: los Recargos y los
 * Descuentos se numeran cada uno por su lado). Nadie lo modifica por la entidad: el número se toma con el UPSERT de
 * {@code NumeradorAjusteCuentaRepository}, que toma el lock de la fila y serializa los ajustes de ese tipo en la ferretería
 * (mismo molde que {@code NumeradorReciboRepository}). La entidad solo declara la tabla para que {@code ddl-auto=validate} la
 * conozca.
 */
@Entity
@Table(name = "numerador_ajuste_cuenta")
public class NumeradorAjusteCuenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, updatable = false, length = 10)
    private TipoAjuste tipo;

    @Column(name = "ultimo_numero", nullable = false, updatable = false)
    private Long ultimoNumero;

    protected NumeradorAjusteCuenta() {}

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public TipoAjuste getTipo() { return tipo; }
    public Long getUltimoNumero() { return ultimoNumero; }
}
