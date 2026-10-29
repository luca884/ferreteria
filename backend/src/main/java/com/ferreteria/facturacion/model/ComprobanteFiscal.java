package com.ferreteria.facturacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * El registro fiscal de un comprobante (tabla {@code comprobante_fiscal}, V32): uno solo vigente por venta, con lo que va
 * a ARCA congelado al registrarlo (ambiente y punto de venta de la configuración, letra y CbteTipo del encuadre, receptor,
 * emisor e importes del desglose) y el estado de su autorización. Desde la V36 (rama 7), el reintento del Dueño no lo
 * reescribe: lo deja como historia ({@link #getReemplazadoEn()}) y da de alta otro, vigente, con la copia de ese momento.
 *
 * <p>Único escritor: el motor ({@code facturacion.motor}). La entidad no tiene setters, y Hibernate no la actualiza nunca
 * ({@link Immutable}): el número, la CbteFch, el estado, el CAE y lo que respondió ARCA cambian solo por los UPDATE
 * condicionales de {@code ComprobanteFiscalRepository}, que miran el estado anterior. Lo congelado no lo cambia ninguno.
 */
@Entity
@Immutable
@Table(name = "comprobante_fiscal")
public class ComprobanteFiscal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "comprobante_id", nullable = false, updatable = false)
    private long comprobanteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private Ambiente ambiente;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private ClaseComprobante clase;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 1)
    private Letra letra;

    @Column(name = "cbte_tipo", nullable = false, updatable = false)
    private int cbteTipo;

    @Column(name = "punto_venta", nullable = false, updatable = false)
    private int puntoVenta;

    /** Null hasta enviar, y de nuevo null si ARCA lo rechaza o no llegó. */
    @Column
    private Long numero;

    /** Va junto con el número. */
    @Column(name = "cbte_fch")
    private LocalDate cbteFch;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private EstadoFiscal estado;

    @Column(length = 14)
    private String cae;

    @Column(name = "cae_vencimiento")
    private LocalDate caeVencimiento;

    /** «A» con AUTORIZADO, «R» con RECHAZADO; null en el resto. */
    @Column(length = 1)
    private String resultado;

    /** Las {@code Observaciones} de ARCA tal cual, en JSON ({@code [{"code":..,"msg":..}]}). */
    @Column(columnDefinition = "json")
    private String observaciones;

    /** Los {@code Errors} de ARCA tal cual, en JSON. */
    @Column(columnDefinition = "json")
    private String errores;

    @Column(name = "receptor_doc_tipo", nullable = false, updatable = false)
    private int receptorDocTipo;

    @Column(name = "receptor_doc_nro", nullable = false, updatable = false)
    private long receptorDocNro;

    @Column(name = "receptor_condicion_iva_id", nullable = false, updatable = false)
    private int receptorCondicionIvaId;

    @Column(name = "receptor_nombre", updatable = false, length = 120)
    private String receptorNombre;

    @Column(name = "receptor_domicilio", updatable = false, length = 200)
    private String receptorDomicilio;

    @Column(name = "emisor_cuit", nullable = false, updatable = false, length = 13)
    private String emisorCuit;

    @Column(name = "emisor_razon_social", nullable = false, updatable = false, length = 150)
    private String emisorRazonSocial;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "emisor_condicion_iva", nullable = false, updatable = false, length = 30)
    private CondicionIvaEmisor emisorCondicionIva;

    @Column(name = "emisor_domicilio_comercial", nullable = false, updatable = false, length = 200)
    private String emisorDomicilioComercial;

    @Column(name = "emisor_ingresos_brutos", nullable = false, updatable = false, length = 50)
    private String emisorIngresosBrutos;

    @Column(name = "emisor_inicio_actividades", nullable = false, updatable = false)
    private LocalDate emisorInicioActividades;

    @Column(name = "imp_total", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impTotal;

    @Column(name = "imp_neto", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impNeto;

    @Column(name = "imp_iva", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impIva;

    @Column(name = "imp_tot_conc", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impTotConc;

    @Column(name = "imp_op_ex", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal impOpEx;

    /**
     * La hora de la venta (UTC): de ella sale la CbteFch, en la fecha de Buenos Aires. En el registro del reintento del
     * Dueño, la del registro que reemplaza, o la del reintento si el Dueño confirmó la fecha de hoy (V36).
     */
    @Column(name = "registrado_en", nullable = false, updatable = false)
    private Instant registradoEn;

    /** La venta salió con el ticket provisorio: el CAE no llegó dentro de su espera (V36, S6). */
    @Column(name = "ticket_provisorio", nullable = false)
    private boolean ticketProvisorio;

    /** Por qué el Dueño la dejó sin factura; solo en SIN_FACTURA, con el usuario y la hora (V36). */
    @Column(name = "motivo_sin_factura", length = 200)
    private String motivoSinFactura;

    @Column(name = "usuario_sin_factura_id")
    private Long usuarioSinFacturaId;

    @Column(name = "sin_factura_en")
    private Instant sinFacturaEn;

    /**
     * Cuándo lo reemplazó el reintento del Dueño (V36): null en el vigente, que es uno solo por venta. Solo un RECHAZADO o
     * un FUERA_DE_FECHA queda reemplazado, y después no cambia más.
     */
    @Column(name = "reemplazado_en")
    private Instant reemplazadoEn;

    protected ComprobanteFiscal() {
    }

    /**
     * El registro de una venta que espera su turno (PENDIENTE, sin número): congela el ambiente y el punto de venta, el
     * encuadre (letra y CbteTipo), el receptor, el emisor y los importes del desglose.
     */
    public static ComprobanteFiscal pendiente(long ferreteriaId, long comprobanteId, Ambiente ambiente, int puntoVenta,
            ClaseComprobante clase, EncuadreFiscal encuadre, ReceptorFiscal receptor, EmisorFiscal emisor,
            DesgloseFiscal desglose, Instant registradoEn) {
        Objects.requireNonNull(ambiente, "Falta el ambiente de ARCA.");
        Objects.requireNonNull(clase, "Falta la clase del comprobante.");
        Objects.requireNonNull(encuadre, "Falta el encuadre fiscal.");
        Objects.requireNonNull(receptor, "Falta el receptor.");
        Objects.requireNonNull(emisor, "Falta el emisor.");
        Objects.requireNonNull(desglose, "Falta el desglose fiscal.");
        Objects.requireNonNull(registradoEn, "Falta la hora de la venta.");
        if (encuadre.condicionReceptor() != receptor.condicionIva()) {
            throw new IllegalArgumentException("El encuadre no es el de la condición frente al IVA del receptor.");
        }
        ComprobanteFiscal r = new ComprobanteFiscal();
        r.ferreteriaId = ferreteriaId;
        r.comprobanteId = comprobanteId;
        r.ambiente = ambiente;
        r.clase = clase;
        r.letra = encuadre.letra();
        r.cbteTipo = encuadre.cbteTipo(clase);
        r.puntoVenta = puntoVenta;
        r.estado = EstadoFiscal.PENDIENTE;
        r.receptorDocTipo = receptor.tipoDocumento().docTipo();
        r.receptorDocNro = receptor.numeroDocumento();
        r.receptorCondicionIvaId = receptor.condicionIva().condicionIvaReceptorId();
        r.receptorNombre = receptor.nombre();
        r.receptorDomicilio = receptor.domicilio();
        r.emisorCuit = emisor.cuit();
        r.emisorRazonSocial = emisor.razonSocial();
        r.emisorCondicionIva = emisor.condicionIva();
        r.emisorDomicilioComercial = emisor.domicilioComercial();
        r.emisorIngresosBrutos = emisor.ingresosBrutos();
        r.emisorInicioActividades = emisor.inicioActividades();
        r.impTotal = desglose.impTotal();
        r.impNeto = desglose.impNeto();
        r.impIva = desglose.impIva();
        r.impTotConc = desglose.impTotConc();
        r.impOpEx = desglose.impOpEx();
        r.registradoEn = registradoEn;
        return r;
    }

    /** La CUIT emisora como la pide WSFEv1: los 11 dígitos, sin guiones. */
    public long cuitEmisora() {
        return Long.parseLong(emisorCuit.replace("-", ""));
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public long getComprobanteId() { return comprobanteId; }
    public Ambiente getAmbiente() { return ambiente; }
    public ClaseComprobante getClase() { return clase; }
    public Letra getLetra() { return letra; }
    public int getCbteTipo() { return cbteTipo; }
    public int getPuntoVenta() { return puntoVenta; }
    public Long getNumero() { return numero; }
    public LocalDate getCbteFch() { return cbteFch; }
    public EstadoFiscal getEstado() { return estado; }
    public String getCae() { return cae; }
    public LocalDate getCaeVencimiento() { return caeVencimiento; }
    public String getResultado() { return resultado; }
    public String getObservaciones() { return observaciones; }
    public String getErrores() { return errores; }
    public int getReceptorDocTipo() { return receptorDocTipo; }
    public long getReceptorDocNro() { return receptorDocNro; }
    public int getReceptorCondicionIvaId() { return receptorCondicionIvaId; }
    public String getReceptorNombre() { return receptorNombre; }
    public String getReceptorDomicilio() { return receptorDomicilio; }
    public String getEmisorCuit() { return emisorCuit; }
    public String getEmisorRazonSocial() { return emisorRazonSocial; }
    public CondicionIvaEmisor getEmisorCondicionIva() { return emisorCondicionIva; }
    public String getEmisorDomicilioComercial() { return emisorDomicilioComercial; }
    public String getEmisorIngresosBrutos() { return emisorIngresosBrutos; }
    public LocalDate getEmisorInicioActividades() { return emisorInicioActividades; }
    public BigDecimal getImpTotal() { return impTotal; }
    public BigDecimal getImpNeto() { return impNeto; }
    public BigDecimal getImpIva() { return impIva; }
    public BigDecimal getImpTotConc() { return impTotConc; }
    public BigDecimal getImpOpEx() { return impOpEx; }
    public Instant getRegistradoEn() { return registradoEn; }
    public boolean isTicketProvisorio() { return ticketProvisorio; }
    public String getMotivoSinFactura() { return motivoSinFactura; }
    public Long getUsuarioSinFacturaId() { return usuarioSinFacturaId; }
    public Instant getSinFacturaEn() { return sinFacturaEn; }
    public Instant getReemplazadoEn() { return reemplazadoEn; }

    /** El registro de la venta que vale: el que no reemplazó ningún reintento. */
    public boolean esVigente() {
        return reemplazadoEn == null;
    }
}
