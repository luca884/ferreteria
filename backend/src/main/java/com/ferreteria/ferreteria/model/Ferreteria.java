package com.ferreteria.ferreteria.model;

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
import java.time.LocalDateTime;

/** La ferreteria es el tenant: la raiz de todos los datos de negocio. */
@Entity
@Table(name = "ferreteria")
public class Ferreteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "razon_social", nullable = false, length = 150)
    private String razonSocial;

    @Column(nullable = false, length = 13, unique = true)
    private String cuit;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "condicion_iva", nullable = false, length = 30)
    private CondicionIva condicionIva;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EstadoFerreteria estado = EstadoFerreteria.ACTIVA;

    /** Margen de los articulos sin margen propio ni rubro. Porcentaje: 30.00 significa 30 %. */
    @Column(name = "margen_defecto_porcentaje", nullable = false, precision = 6, scale = 2)
    private BigDecimal margenDefectoPorcentaje = new BigDecimal("30.00");

    /** El precio final se redondea hacia arriba a un multiplo de este valor. */
    @Column(name = "redondeo_multiplo", nullable = false, precision = 15, scale = 2)
    private BigDecimal redondeoMultiplo = new BigDecimal("10.00");

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    protected Ferreteria() {}

    public Ferreteria(String razonSocial, String cuit, CondicionIva condicionIva) {
        this.razonSocial = razonSocial;
        this.cuit = cuit;
        this.condicionIva = condicionIva;
    }

    public boolean isActiva() {
        return estado == EstadoFerreteria.ACTIVA;
    }

    public Long getId() { return id; }
    public String getRazonSocial() { return razonSocial; }
    public String getCuit() { return cuit; }
    public CondicionIva getCondicionIva() { return condicionIva; }
    public EstadoFerreteria getEstado() { return estado; }
    public BigDecimal getMargenDefectoPorcentaje() { return margenDefectoPorcentaje; }
    public BigDecimal getRedondeoMultiplo() { return redondeoMultiplo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
