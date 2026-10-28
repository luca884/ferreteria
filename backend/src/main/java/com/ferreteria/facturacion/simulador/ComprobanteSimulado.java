package com.ferreteria.facturacion.simulador;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un comprobante que el simulador autorizó, con todos los campos del pedido y del resultado: lo que devuelve
 * {@code FECompConsultar}. Inmutable: no tiene setters. Los arrays van como texto JSON (ver V33).
 */
@Entity
@Table(name = "simulador_wsfe_comprobante")
public class ComprobanteSimulado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "serie_id", nullable = false, updatable = false)
    private Long serieId;

    @Column(name = "cbte_nro", nullable = false, updatable = false)
    private long cbteNro;

    @Column(nullable = false, updatable = false)
    private int concepto;

    @Column(name = "doc_tipo", nullable = false, updatable = false)
    private int docTipo;

    @Column(name = "doc_nro", nullable = false, updatable = false)
    private long docNro;

    @Column(name = "cbte_fch", nullable = false, updatable = false)
    private LocalDate cbteFch;

    @Column(name = "imp_total", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impTotal;

    @Column(name = "imp_tot_conc", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impTotConc;

    @Column(name = "imp_neto", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impNeto;

    @Column(name = "imp_op_ex", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impOpEx;

    @Column(name = "imp_trib", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impTrib;

    @Column(name = "imp_iva", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impIva;

    @Column(name = "mon_id", nullable = false, updatable = false, length = 3)
    private String monId;

    @Column(name = "mon_cotiz", nullable = false, updatable = false, precision = 10, scale = 6)
    private BigDecimal monCotiz;

    @Column(name = "condicion_iva_receptor_id", nullable = false, updatable = false)
    private int condicionIvaReceptorId;

    /** El array {@code Iva} como JSON. */
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String iva;

    /** El array {@code CbtesAsoc} como JSON. */
    @Column(name = "cbtes_asoc", nullable = false, updatable = false, columnDefinition = "text")
    private String cbtesAsoc;

    @Column(name = "periodo_asoc_desde", updatable = false)
    private LocalDate periodoAsocDesde;

    @Column(name = "periodo_asoc_hasta", updatable = false)
    private LocalDate periodoAsocHasta;

    @Column(nullable = false, updatable = false, length = 14)
    private String cae;

    @Column(name = "cae_fch_vto", nullable = false, updatable = false)
    private LocalDate caeFchVto;

    /** Las {@code Observaciones} con que se aprobó, como JSON. */
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String observaciones;

    @Column(name = "fch_proceso", nullable = false, updatable = false)
    private Instant fchProceso;

    protected ComprobanteSimulado() {
    }

    ComprobanteSimulado(Long ferreteriaId, Long serieId, long cbteNro, int concepto, int docTipo, long docNro,
            LocalDate cbteFch, BigDecimal impTotal, BigDecimal impTotConc, BigDecimal impNeto, BigDecimal impOpEx,
            BigDecimal impTrib, BigDecimal impIva, String monId, BigDecimal monCotiz, int condicionIvaReceptorId,
            String iva, String cbtesAsoc, LocalDate periodoAsocDesde, LocalDate periodoAsocHasta, String cae,
            LocalDate caeFchVto, String observaciones, Instant fchProceso) {
        this.ferreteriaId = ferreteriaId;
        this.serieId = serieId;
        this.cbteNro = cbteNro;
        this.concepto = concepto;
        this.docTipo = docTipo;
        this.docNro = docNro;
        this.cbteFch = cbteFch;
        this.impTotal = impTotal;
        this.impTotConc = impTotConc;
        this.impNeto = impNeto;
        this.impOpEx = impOpEx;
        this.impTrib = impTrib;
        this.impIva = impIva;
        this.monId = monId;
        this.monCotiz = monCotiz;
        this.condicionIvaReceptorId = condicionIvaReceptorId;
        this.iva = iva;
        this.cbtesAsoc = cbtesAsoc;
        this.periodoAsocDesde = periodoAsocDesde;
        this.periodoAsocHasta = periodoAsocHasta;
        this.cae = cae;
        this.caeFchVto = caeFchVto;
        this.observaciones = observaciones;
        this.fchProceso = fchProceso;
    }

    public long getCbteNro() { return cbteNro; }
    public int getConcepto() { return concepto; }
    public int getDocTipo() { return docTipo; }
    public long getDocNro() { return docNro; }
    public LocalDate getCbteFch() { return cbteFch; }
    public BigDecimal getImpTotal() { return impTotal; }
    public BigDecimal getImpTotConc() { return impTotConc; }
    public BigDecimal getImpNeto() { return impNeto; }
    public BigDecimal getImpOpEx() { return impOpEx; }
    public BigDecimal getImpTrib() { return impTrib; }
    public BigDecimal getImpIva() { return impIva; }
    public String getMonId() { return monId; }
    public BigDecimal getMonCotiz() { return monCotiz; }
    public int getCondicionIvaReceptorId() { return condicionIvaReceptorId; }
    public String getIva() { return iva; }
    public String getCbtesAsoc() { return cbtesAsoc; }
    public LocalDate getPeriodoAsocDesde() { return periodoAsocDesde; }
    public LocalDate getPeriodoAsocHasta() { return periodoAsocHasta; }
    public String getCae() { return cae; }
    public LocalDate getCaeFchVto() { return caeFchVto; }
    public String getObservaciones() { return observaciones; }
    public Instant getFchProceso() { return fchProceso; }
}
