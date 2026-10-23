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
import com.ferreteria.common.HoraArgentina;
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

    @Column(nullable = false, length = 13, unique = true, updatable = false)
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

    /**
     * El precio final se redondea hacia arriba a un multiplo de este valor; 0 es sin redondeo (precio con centavos).
     * Arranca en 0 (V9) y lo cambia el Dueño, como el redondeo configurable de GDS.
     */
    @Column(name = "redondeo_multiplo", nullable = false, precision = 15, scale = 2)
    private BigDecimal redondeoMultiplo = new BigDecimal("0.00");

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = HoraArgentina.ahora();

    protected Ferreteria() {}

    public Ferreteria(String razonSocial, String cuit, CondicionIva condicionIva) {
        this.razonSocial = razonSocial;
        this.cuit = cuit;
        this.condicionIva = condicionIva;
    }

    /** Lo unico que el super admin edita de una ferreteria ya creada: el CUIT no cambia nunca. */
    public void actualizarDatos(String razonSocial, CondicionIva condicionIva) {
        this.razonSocial = razonSocial;
        this.condicionIva = condicionIva;
    }

    /** El Dueño cambia el margen por defecto y el multiplo de redondeo; el recalculo de precios lo hace el catalogo. */
    public void cambiarConfiguracionPrecios(BigDecimal margenDefectoPorcentaje, BigDecimal redondeoMultiplo) {
        this.margenDefectoPorcentaje = margenDefectoPorcentaje;
        this.redondeoMultiplo = redondeoMultiplo;
    }

    /** Suspender o reactivar. Idempotente; no toca ningun otro dato de la ferreteria. */
    public void cambiarActivo(boolean activo) {
        this.estado = activo ? EstadoFerreteria.ACTIVA : EstadoFerreteria.SUSPENDIDA;
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
