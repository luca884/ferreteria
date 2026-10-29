package com.ferreteria.facturacion.arca;

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
 * El ticket de acceso de WSAA de una ferretería para un servicio y un ambiente (tabla {@code ticket_acceso}, V35; rama 6,
 * S2), con el lease de quien lo está pidiendo y la espera de C4. La escriben solo los UPDATE de
 * {@link TicketAccesoRepository}, desde {@link PasosDelTicket}: la entidad no tiene setters.
 *
 * <p>El token y el sign van cifrados ({@code CifradorDeClaves}, propósito {@code TICKET_DE_ACCESO} y la huella de la
 * credencial que lo pidió en el AAD), nunca en claro; {@link #toString()} no muestra ni el dato cifrado.
 */
@Entity
@Table(name = "ticket_acceso")
public class TicketAcceso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(nullable = false, length = 32, updatable = false)
    private String servicio;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private Ambiente ambiente;

    /** La huella de la credencial que pidió el TA; null sin TA. */
    @Column(name = "huella_credencial", length = 64, columnDefinition = "char(64)")
    private String huellaCredencial;

    @Column(name = "token_cifrado", length = 8192)
    private byte[] tokenCifrado;

    @Column(name = "sign_cifrado", length = 1024)
    private byte[] signCifrado;

    /** El {@code generationTime} del TA (UTC). */
    @Column(name = "generado")
    private Instant generado;

    /** El {@code expirationTime} del TA (UTC). */
    @Column(name = "vence")
    private Instant vence;

    @Column(name = "lease_dueno", length = 36)
    private String leaseDueno;

    @Column(name = "lease_vence")
    private Instant leaseVence;

    /** Antes de esta hora no se llama a WSAA (C4); null sin espera. */
    @Column(name = "espera_hasta")
    private Instant esperaHasta;

    @Column(name = "ultima_falla", length = 64)
    private String ultimaFalla;

    @Column(name = "ultima_falla_en")
    private Instant ultimaFallaEn;

    protected TicketAcceso() {}

    /** Si guarda un TA (lo que trae el TA va todo junto o no va: {@code ck_ticket_acceso_ta}). */
    public boolean tieneTa() {
        return huellaCredencial != null;
    }

    byte[] getTokenCifrado() { return tokenCifrado == null ? null : tokenCifrado.clone(); }
    byte[] getSignCifrado() { return signCifrado == null ? null : signCifrado.clone(); }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getServicio() { return servicio; }
    public Ambiente getAmbiente() { return ambiente; }
    public String getHuellaCredencial() { return huellaCredencial; }
    public Instant getGenerado() { return generado; }
    public Instant getVence() { return vence; }
    public String getLeaseDueno() { return leaseDueno; }
    public Instant getLeaseVence() { return leaseVence; }
    public Instant getEsperaHasta() { return esperaHasta; }
    public String getUltimaFalla() { return ultimaFalla; }
    public Instant getUltimaFallaEn() { return ultimaFallaEn; }

    @Override
    public String toString() {
        return "TicketAcceso[id=" + id + ", ferreteriaId=" + ferreteriaId + ", " + servicio + "/" + ambiente + ", huella="
                + huellaCredencial + ", ta=" + (tieneTa() ? "***" : "no") + ", vence=" + vence + ", espera=" + esperaHasta
                + "]";
    }
}
