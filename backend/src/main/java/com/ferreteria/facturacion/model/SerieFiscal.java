package com.ferreteria.facturacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Una serie de numeración (tabla {@code serie_fiscal}, V32): ferretería, ambiente, punto de venta y CbteTipo. Guarda lo
 * último que se supo autorizado (null: todavía no se consultó a ARCA; 0: la serie no tiene ninguno) y el lease con dueño
 * que la toma entre instancias; desde la V36, el backoff del job de reintento. Solo lectura desde Java: la fila la crean y
 * la cambian los UPDATE condicionales de {@code SerieFiscalRepository}.
 */
@Entity
@Immutable
@Table(name = "serie_fiscal")
public class SerieFiscal {

    @Id
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private Ambiente ambiente;

    @Column(name = "punto_venta", nullable = false, updatable = false)
    private int puntoVenta;

    @Column(name = "cbte_tipo", nullable = false, updatable = false)
    private int cbteTipo;

    @Column(name = "ultimo_autorizado")
    private Long ultimoAutorizado;

    @Column(name = "ultima_cbte_fch")
    private LocalDate ultimaCbteFch;

    @Column(name = "lease_dueno", length = 36)
    private String leaseDueno;

    @Column(name = "lease_vence")
    private Instant leaseVence;

    /** El backoff del job de reintento (V36, C5): cuántas pasadas seguidas terminaron en un error transitorio. */
    @Column(name = "fallas_seguidas", nullable = false)
    private int fallasSeguidas;

    /** Desde cuándo el job vuelve a atenderla; null sin backoff (con cero fallas seguidas). */
    @Column(name = "proximo_reintento")
    private Instant proximoReintento;

    protected SerieFiscal() {
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Ambiente getAmbiente() { return ambiente; }
    public int getPuntoVenta() { return puntoVenta; }
    public int getCbteTipo() { return cbteTipo; }
    public Long getUltimoAutorizado() { return ultimoAutorizado; }
    public LocalDate getUltimaCbteFch() { return ultimaCbteFch; }
    public String getLeaseDueno() { return leaseDueno; }
    public Instant getLeaseVence() { return leaseVence; }
    public int getFallasSeguidas() { return fallasSeguidas; }
    public Instant getProximoReintento() { return proximoReintento; }
}
