package com.ferreteria.facturacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un {@code FECAESolicitar} (tabla {@code intento_fiscal}, V32): se guarda ABIERTO, con su número, su CbteFch y el pedido
 * tal cual, en una transacción corta ANTES de llamar; la respuesta y el estado se completan después con UPDATE
 * condicionales de {@code IntentoFiscalRepository}. Sin setters y sin actualizaciones de Hibernate ({@link Immutable}).
 */
@Entity
@Immutable
@Table(name = "intento_fiscal")
public class IntentoFiscal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "comprobante_fiscal_id", nullable = false, updatable = false)
    private long comprobanteFiscalId;

    @Column(name = "serie_fiscal_id", nullable = false, updatable = false)
    private long serieFiscalId;

    @Column(nullable = false, updatable = false)
    private long numero;

    @Column(name = "cbte_fch", nullable = false, updatable = false)
    private LocalDate cbteFch;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EstadoIntento estado;

    @Column(length = 1)
    private String resultado;

    /** El pedido de FECAESolicitar tal cual se mandó, en JSON. */
    @Column(nullable = false, updatable = false, columnDefinition = "mediumtext")
    private String pedido;

    /** La respuesta tal cual, en JSON (o la falla de la llamada). */
    @Column(columnDefinition = "mediumtext")
    private String respuesta;

    /** Lo que respondieron FECompUltimoAutorizado y FECompConsultar al verificarlo, en JSON. */
    @Column(name = "respuesta_verificacion", columnDefinition = "mediumtext")
    private String respuestaVerificacion;

    @Column(name = "iniciado_en", nullable = false, updatable = false)
    private Instant iniciadoEn;

    @Column(name = "terminado_en")
    private Instant terminadoEn;

    @Column(name = "cerrado_en")
    private Instant cerradoEn;

    protected IntentoFiscal() {
    }

    /** El intento recién guardado, antes de llamar. */
    public static IntentoFiscal abierto(long ferreteriaId, long comprobanteFiscalId, long serieFiscalId, long numero,
            LocalDate cbteFch, String pedido, Instant iniciadoEn) {
        IntentoFiscal i = new IntentoFiscal();
        i.ferreteriaId = ferreteriaId;
        i.comprobanteFiscalId = comprobanteFiscalId;
        i.serieFiscalId = serieFiscalId;
        i.numero = numero;
        i.cbteFch = Objects.requireNonNull(cbteFch, "Falta la CbteFch del intento.");
        i.estado = EstadoIntento.ABIERTO;
        i.pedido = Objects.requireNonNull(pedido, "Falta el pedido del intento.");
        i.iniciadoEn = Objects.requireNonNull(iniciadoEn, "Falta la hora del intento.");
        return i;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public long getComprobanteFiscalId() { return comprobanteFiscalId; }
    public long getSerieFiscalId() { return serieFiscalId; }
    public long getNumero() { return numero; }
    public LocalDate getCbteFch() { return cbteFch; }
    public EstadoIntento getEstado() { return estado; }
    public String getResultado() { return resultado; }
    public String getPedido() { return pedido; }
    public String getRespuesta() { return respuesta; }
    public String getRespuestaVerificacion() { return respuestaVerificacion; }
    public Instant getIniciadoEn() { return iniciadoEn; }
    public Instant getTerminadoEn() { return terminadoEn; }
    public Instant getCerradoEn() { return cerradoEn; }
}
