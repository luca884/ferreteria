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
import java.time.LocalDate;
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

    /** Desde este importe (igual o superior) la venta facturable a Consumidor Final necesita un cliente con documento (GDS-32). */
    @Column(name = "tope_consumidor_final", nullable = false, precision = 15, scale = 2)
    private BigDecimal topeConsumidorFinal = new BigDecimal("10000000.00");

    /** Domicilio del local donde se factura (RG 1415). Lo carga el Dueño (CU-01); vacio hasta que lo carga. */
    @Column(name = "domicilio_comercial", length = 200)
    private String domicilioComercial;

    /** Inicio de actividades del local donde se factura (RG 1415). */
    @Column(name = "inicio_actividades")
    private LocalDate inicioActividades;

    /** Ingresos Brutos como se imprimen: numero, Convenio Multilateral, Exento o No contribuyente (RG 1415). */
    @Column(name = "ingresos_brutos", length = 30)
    private String ingresosBrutos;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = HoraArgentina.ahora();

    protected Ferreteria() {}

    public Ferreteria(String razonSocial, String cuit, CondicionIva condicionIva) {
        this.razonSocial = razonSocial;
        this.cuit = cuit;
        this.condicionIva = condicionIva;
    }

    /**
     * Los datos del emisor que edita el Dueño (CU-01 paso 1): el CUIT no cambia nunca. Los textos se recortan, y un
     * domicilio o unos Ingresos Brutos en blanco quedan vacios (la base los rechaza en blanco).
     */
    public void actualizarDatosFiscales(String razonSocial, CondicionIva condicionIva, String domicilioComercial,
            LocalDate inicioActividades, String ingresosBrutos) {
        this.razonSocial = razonSocial.trim();
        this.condicionIva = condicionIva;
        this.domicilioComercial = textoOVacio(domicilioComercial);
        this.inicioActividades = inicioActividades;
        this.ingresosBrutos = textoOVacio(ingresosBrutos);
    }

    private static String textoOVacio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    /** El Dueño cambia el margen por defecto y el multiplo de redondeo; el recalculo de precios lo hace el catalogo. */
    public void cambiarConfiguracionPrecios(BigDecimal margenDefectoPorcentaje, BigDecimal redondeoMultiplo) {
        this.margenDefectoPorcentaje = margenDefectoPorcentaje;
        this.redondeoMultiplo = redondeoMultiplo;
    }

    /** El Dueño cambia el tope de venta a Consumidor Final sin identificar; mayor que 0 (lo valida el request y un CHECK). */
    public void cambiarTopeConsumidorFinal(BigDecimal topeConsumidorFinal) {
        this.topeConsumidorFinal = topeConsumidorFinal;
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
    public BigDecimal getTopeConsumidorFinal() { return topeConsumidorFinal; }
    public String getDomicilioComercial() { return domicilioComercial; }
    public LocalDate getInicioActividades() { return inicioActividades; }
    public String getIngresosBrutos() { return ingresosBrutos; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
