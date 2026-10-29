package com.ferreteria.facturacion.simulador;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * El último TA que emitió el WSAA del simulador en el borde para un servicio de la ferretería (tabla {@code ticket_simulado},
 * V35; rama 6, S6). No guarda el token ni el sign: solo su huella ({@link WsaaDelSimulador#huella}), con la que el simulador
 * reconoce el {@code Auth} que le llega a WSFE, y su vigencia, con la que aplica el lapso del TA. La escribe solo
 * {@link TicketSimuladoRepository#emitirScoped}.
 */
@Entity
@Table(name = "ticket_simulado")
public class TicketSimulado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(nullable = false, updatable = false, length = 32)
    private String servicio;

    /** SHA-256 del token y el sign del TA, en hexa mayúscula. */
    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String huella;

    /** El {@code generationTime} del TA. */
    @Column(nullable = false)
    private Instant generado;

    /** El {@code expirationTime} del TA. */
    @Column(nullable = false)
    private Instant vence;

    protected TicketSimulado() {
    }

    public Long getFerreteriaId() {
        return ferreteriaId;
    }

    public String getServicio() {
        return servicio;
    }

    public String getHuella() {
        return huella;
    }

    public Instant getGenerado() {
        return generado;
    }

    public Instant getVence() {
        return vence;
    }

    /** Si el TA sigue vigente en {@code ahora}. */
    boolean vigenteEn(Instant ahora) {
        return vence.isAfter(ahora);
    }
}
