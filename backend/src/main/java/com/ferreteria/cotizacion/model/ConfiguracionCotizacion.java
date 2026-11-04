package com.ferreteria.cotizacion.model;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Configuracion de cotizacion de una ferreteria (una fila por ferreteria) y su estado de recalculo: el dolar vigente
 * con el que se calculan sus articulos en USD y el ultimo dia en que se recalcularon solos.
 *
 * <p>Los tipos se identifican con codigos {@code MONEDA:casa}, por ejemplo {@code USD:oficial}. {@code visibles} son los
 * que se muestran en pantalla (varios a la vez); {@code casaPrecios} y {@code ladoPrecios} son el UNICO dolar en el que
 * cotizan los precios. Un cambio de ese dolar no toca {@code vigente}: se aplica en el proximo recalculo.
 *
 * <p>La hora del recalculo es hora de Buenos Aires; el dia, tambien.
 */
@Entity
@Table(name = "configuracion_cotizacion")
public class ConfiguracionCotizacion {

    public static final String CASA_POR_DEFECTO = "oficial";
    public static final LadoCotizacion LADO_POR_DEFECTO = LadoCotizacion.VENTA;
    public static final String VISIBLE_POR_DEFECTO = "USD:oficial";
    public static final LocalTime HORA_POR_DEFECTO = LocalTime.of(8, 0);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    /** Apagada, los articulos en USD conservan el ultimo precio y no se recalculan. */
    @Column(nullable = false)
    private boolean activa = true;

    /** Casa del dolar de los precios (siempre de USD). */
    @Column(name = "casa_precios", nullable = false, length = 30)
    private String casaPrecios = CASA_POR_DEFECTO;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "lado_precios", nullable = false, length = 10)
    private LadoCotizacion ladoPrecios = LADO_POR_DEFECTO;

    /** Codigos MONEDA:casa de lo que se muestra en pantalla. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> visibles = List.of(VISIBLE_POR_DEFECTO);

    @Column(name = "hora_recalculo", nullable = false)
    private LocalTime horaRecalculo = HORA_POR_DEFECTO;

    /**
     * Porcentaje de diferencia contra el vigente por encima del cual un dolar nuevo espera la confirmacion del Dueno
     * ({@link DesvioDeCotizacion}).
     */
    @Column(name = "umbral_desvio_porcentaje", nullable = false, precision = 5, scale = 2)
    private BigDecimal umbralDesvioPorcentaje = DesvioDeCotizacion.UMBRAL_POR_DEFECTO;

    /** Dolar con el que se calcularon por ultima vez los precios de esta ferreteria; null si nunca se fijo. */
    @Column(precision = 15, scale = 4)
    private BigDecimal vigente;

    @Column(name = "vigente_casa", length = 30)
    private String vigenteCasa;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "vigente_lado", length = 10)
    private LadoCotizacion vigenteLado;

    @Column(name = "vigente_desde")
    private Instant vigenteDesde;

    /** Ultimo dia (de Buenos Aires) en que el recalculo diario corrio para esta ferreteria. */
    @Column(name = "ultimo_recalculo_dia")
    private LocalDate ultimoRecalculoDia;

    protected ConfiguracionCotizacion() {}

    /** Activada, dolar oficial vendedor, se muestra el oficial, recalculo a las 08:00, aviso al 5 % y sin dolar vigente. */
    public static ConfiguracionCotizacion porDefecto(Long ferreteriaId) {
        ConfiguracionCotizacion configuracion = new ConfiguracionCotizacion();
        configuracion.ferreteriaId = ferreteriaId;
        return configuracion;
    }

    /** Fija el dolar vigente: su valor y de que tipo y lado salio. */
    public void fijarVigente(BigDecimal valor, String casa, LadoCotizacion lado, Instant desde) {
        this.vigente = valor;
        this.vigenteCasa = casa;
        this.vigenteLado = lado;
        this.vigenteDesde = desde;
    }

    public void setActiva(boolean activa) { this.activa = activa; }
    public void setCasaPrecios(String casaPrecios) { this.casaPrecios = casaPrecios; }
    public void setLadoPrecios(LadoCotizacion ladoPrecios) { this.ladoPrecios = ladoPrecios; }
    public void setVisibles(List<String> visibles) { this.visibles = List.copyOf(visibles); }
    public void setHoraRecalculo(LocalTime horaRecalculo) { this.horaRecalculo = horaRecalculo; }
    public void setUltimoRecalculoDia(LocalDate ultimoRecalculoDia) { this.ultimoRecalculoDia = ultimoRecalculoDia; }
    public void setUmbralDesvioPorcentaje(BigDecimal umbral) { this.umbralDesvioPorcentaje = umbral; }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public boolean isActiva() { return activa; }
    public String getCasaPrecios() { return casaPrecios; }
    public LadoCotizacion getLadoPrecios() { return ladoPrecios; }
    public List<String> getVisibles() { return List.copyOf(visibles); }
    public LocalTime getHoraRecalculo() { return horaRecalculo; }
    public BigDecimal getVigente() { return vigente; }
    public String getVigenteCasa() { return vigenteCasa; }
    public LadoCotizacion getVigenteLado() { return vigenteLado; }
    public Instant getVigenteDesde() { return vigenteDesde; }
    public LocalDate getUltimoRecalculoDia() { return ultimoRecalculoDia; }
    public BigDecimal getUmbralDesvioPorcentaje() { return umbralDesvioPorcentaje; }
}
