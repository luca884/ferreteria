package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * La credencial de ARCA de una ferretería (tabla {@code credencial_arca}, V34; CU-01 paso 3, rama 5): la clave RSA que
 * generó el sistema, cifrada desde que nace (D8, S6), con su pedido de certificado (CSR) y, una vez validado, el
 * certificado de ARCA. Una ferretería tiene a lo sumo una {@link Estado#PENDIENTE} (el pedido que espera el .crt) y una
 * {@link Estado#ACTIVA} (S15).
 *
 * <p>La clave privada nunca sale del servidor ni aparece en un log: {@link #toString()} la enmascara, y solo
 * {@code CifradorDeClaves} la lee, con el {@link #contexto()} de la credencial.
 */
@Entity
@Table(name = "credencial_arca")
public class CredencialArca {

    /** PENDIENTE: el pedido que espera el .crt. ACTIVA: con el certificado validado. */
    public enum Estado {
        PENDIENTE,
        ACTIVA
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Estado estado;

    /** El del pedido y, al activarse, el del serialNumber del certificado. */
    @Column(nullable = false)
    private Long cuit;

    /** El SHA-256 de la clave pública, en hexa en mayúsculas (va en el AAD del cifrado). */
    @Column(nullable = false, length = 64, columnDefinition = "char(64)", updatable = false)
    private String huella;

    /** Cifrada con el formato de {@code CifradorDeClaves}. */
    @Column(name = "clave_privada", nullable = false, length = 4096, updatable = false)
    private byte[] clavePrivada;

    /** El CSR en PEM. */
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String pedido;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    @Column(columnDefinition = "text")
    private String certificado;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private Ambiente ambiente;

    @Column(length = 100)
    private String emisor;

    @Column(name = "vigente_desde")
    private Instant vigenteDesde;

    @Column(name = "vigente_hasta")
    private Instant vigenteHasta;

    @Column(name = "activada_en")
    private Instant activadaEn;

    /** La CA del simulador que la firmó; null fuera del simulador. */
    @Column(name = "ca_simulador_id")
    private Long caSimuladorId;

    protected CredencialArca() {}

    /** El pedido de certificado recién generado, con su clave ya cifrada. */
    static CredencialArca pendiente(long ferreteriaId, long cuit, String huella, byte[] clavePrivada, String pedido,
            Instant ahora) {
        CredencialArca credencial = new CredencialArca();
        credencial.ferreteriaId = ferreteriaId;
        credencial.estado = Estado.PENDIENTE;
        credencial.cuit = cuit;
        credencial.huella = huella;
        credencial.clavePrivada = clavePrivada.clone();
        credencial.pedido = pedido;
        credencial.creadaEn = ahora;
        return credencial;
    }

    /** El pedido pasa a ser la credencial activa, con el certificado validado. */
    void activar(String certificadoPem, long cuitDelCertificado, Ambiente ambienteDeLaCa, String nombreDelEmisor,
            Instant desde, Instant hasta, Long caDelSimulador, Instant ahora) {
        if (estado != Estado.PENDIENTE) {
            throw new IllegalStateException("Solo se activa un pedido pendiente.");
        }
        this.estado = Estado.ACTIVA;
        this.certificado = certificadoPem;
        this.cuit = cuitDelCertificado;
        this.ambiente = ambienteDeLaCa;
        this.emisor = nombreDelEmisor;
        this.vigenteDesde = desde;
        this.vigenteHasta = hasta;
        this.caSimuladorId = caDelSimulador;
        this.activadaEn = ahora;
    }

    /** A qué está atada la clave cifrada: su propósito, la ferretería y la huella de su clave pública. */
    ContextoDeCifrado contexto() {
        return new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, ferreteriaId, huella);
    }

    byte[] getClavePrivada() { return clavePrivada.clone(); }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Estado getEstado() { return estado; }
    public Long getCuit() { return cuit; }
    public String getHuella() { return huella; }
    public String getPedido() { return pedido; }
    public Instant getCreadaEn() { return creadaEn; }
    public String getCertificado() { return certificado; }
    public Ambiente getAmbiente() { return ambiente; }
    public String getEmisor() { return emisor; }
    public Instant getVigenteDesde() { return vigenteDesde; }
    public Instant getVigenteHasta() { return vigenteHasta; }
    public Instant getActivadaEn() { return activadaEn; }
    public Long getCaSimuladorId() { return caSimuladorId; }

    @Override
    public String toString() {
        return "CredencialArca[id=" + id + ", ferreteriaId=" + ferreteriaId + ", estado=" + estado + ", ambiente="
                + ambiente + ", huella=" + huella + ", clave=***]";
    }
}
