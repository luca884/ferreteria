package com.ferreteria.facturacion.simulador;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Una serie de ARCA (CUIT emisora, punto de venta y CbteTipo) del lado del simulador: el último número autorizado y su
 * CbteFch. La fila la crea {@link SerieSimuladaRepository#bloquearScoped} y la cambia solo una autorización.
 */
@Entity
@Table(name = "simulador_wsfe_serie")
public class SerieSimulada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(nullable = false, updatable = false)
    private long cuit;

    @Column(name = "punto_venta", nullable = false, updatable = false)
    private int puntoVenta;

    @Column(name = "cbte_tipo", nullable = false, updatable = false)
    private int cbteTipo;

    /** El último autorizado; 0 si la serie no tiene ninguno. */
    @Column(name = "ultimo_nro", nullable = false)
    private long ultimoNro;

    /** La CbteFch del último autorizado; null si no tiene ninguno. */
    @Column(name = "ultima_cbte_fch")
    private LocalDate ultimaCbteFch;

    protected SerieSimulada() {
    }

    /** Registra el comprobante recién autorizado como el último de la serie. */
    void registrarAutorizado(long numero, LocalDate cbteFch) {
        this.ultimoNro = numero;
        this.ultimaCbteFch = cbteFch;
    }

    public Long getId() {
        return id;
    }

    public Long getFerreteriaId() {
        return ferreteriaId;
    }

    public long getUltimoNro() {
        return ultimoNro;
    }

    public LocalDate getUltimaCbteFch() {
        return ultimaCbteFch;
    }
}
