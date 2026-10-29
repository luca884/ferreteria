package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * La CA del simulador de una ferretería (tabla {@code ca_simulador}, V34; rama 5, S5 y S15): una por ferretería, se crea la
 * primera vez que firma un pedido en SIMULADOR. Su clave privada va cifrada como cualquier otra, con su propio propósito en
 * el AAD ({@link Proposito#CLAVE_DE_CA_SIMULADOR}); {@link #toString()} la enmascara. Sin validez fiscal: ARCA no la conoce,
 * y un certificado suyo no entra fuera del simulador.
 */
@Entity
@Table(name = "ca_simulador")
public class CaSimulador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    /** El certificado de la CA, autofirmado, en PEM. */
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String certificado;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)", updatable = false)
    private String huella;

    @Column(name = "clave_privada", nullable = false, length = 4096, updatable = false)
    private byte[] clavePrivada;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    protected CaSimulador() {}

    static CaSimulador nueva(long ferreteriaId, String certificadoPem, String huella, byte[] clavePrivada, Instant ahora) {
        CaSimulador ca = new CaSimulador();
        ca.ferreteriaId = ferreteriaId;
        ca.certificado = certificadoPem;
        ca.huella = huella;
        ca.clavePrivada = clavePrivada.clone();
        ca.creadaEn = ahora;
        return ca;
    }

    ContextoDeCifrado contexto() {
        return new ContextoDeCifrado(Proposito.CLAVE_DE_CA_SIMULADOR, ferreteriaId, huella);
    }

    byte[] getClavePrivada() { return clavePrivada.clone(); }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getCertificado() { return certificado; }
    public String getHuella() { return huella; }
    public Instant getCreadaEn() { return creadaEn; }

    @Override
    public String toString() {
        return "CaSimulador[id=" + id + ", ferreteriaId=" + ferreteriaId + ", huella=" + huella + ", clave=***]";
    }
}
