package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * La caja diaria de la ferreteria (CU-10). Hay UNA sola abierta por ferreteria, no por usuario: la base lo garantiza con
 * {@code abierta} (TRUE si esta abierta, NULL si esta cerrada) y UNIQUE(ferreteria_id, abierta), porque MySQL deja
 * repetir los NULL. Cerrada es inmutable: no se reabre ni se edita. El cierre es ciego: el que cierra carga el contado y
 * recien ahi se guardan el esperado y la diferencia. Sin relaciones JPA: guarda ids, como el resto del proyecto.
 */
@Entity
@Table(name = "caja")
public class Caja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    /** TRUE abierta, NULL cerrada (nunca FALSE): es lo que hace unica a la caja abierta. */
    @Column(name = "abierta")
    private Boolean abierta;

    @Column(name = "fecha_apertura", nullable = false, updatable = false)
    private LocalDateTime fechaApertura;

    @Column(name = "usuario_apertura_id", nullable = false, updatable = false)
    private Long usuarioAperturaId;

    @Column(name = "efectivo_inicial", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal efectivoInicial;

    /** El contado del ultimo cierre al momento de abrir; null si es la primera caja. */
    @Column(name = "efectivo_cierre_anterior", updatable = false, precision = 15, scale = 2)
    private BigDecimal efectivoCierreAnterior;

    /** Inicial - contado del ultimo cierre; null si es la primera caja. */
    @Column(name = "diferencia_apertura", updatable = false, precision = 15, scale = 2)
    private BigDecimal diferenciaApertura;

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    @Column(name = "usuario_cierre_id")
    private Long usuarioCierreId;

    @Column(name = "efectivo_contado", precision = 15, scale = 2)
    private BigDecimal efectivoContado;

    @Column(name = "efectivo_esperado", precision = 15, scale = 2)
    private BigDecimal efectivoEsperado;

    /** Contado - esperado. */
    @Column(precision = 15, scale = 2)
    private BigDecimal diferencia;

    protected Caja() {}

    /**
     * Abre la caja con el efectivo inicial (cero o mas). {@code contadoUltimoCierre} es el contado de la ultima caja
     * cerrada, o null si nunca hubo: con el se guarda la diferencia de apertura.
     */
    public static Caja abrir(Long ferreteriaId, Long usuarioId, BigDecimal efectivoInicial,
            BigDecimal contadoUltimoCierre, LocalDateTime ahora) {
        ImporteCaja.validarEfectivo("efectivo inicial", efectivoInicial).ifPresent(mensaje -> {
            throw new IllegalArgumentException(mensaje);
        });
        Caja caja = new Caja();
        caja.ferreteriaId = ferreteriaId;
        caja.abierta = Boolean.TRUE;
        caja.fechaApertura = ahora;
        caja.usuarioAperturaId = usuarioId;
        caja.efectivoInicial = efectivoInicial;
        caja.efectivoCierreAnterior = contadoUltimoCierre;
        caja.diferenciaApertura = contadoUltimoCierre == null ? null : efectivoInicial.subtract(contadoUltimoCierre);
        return caja;
    }

    public boolean estaAbierta() {
        return Boolean.TRUE.equals(abierta);
    }

    /** {@code hoy} es la fecha de Buenos Aires: una caja abierta ayer sigue abierta pero ya no sirve para vender. */
    public EstadoCaja estado(LocalDate hoy) {
        if (!estaAbierta()) {
            return EstadoCaja.CERRADA;
        }
        return fechaApertura.toLocalDate().equals(hoy) ? EstadoCaja.ABIERTA : EstadoCaja.DE_OTRO_DIA;
    }

    /**
     * Cierre ciego: guarda el contado (cero o mas), el esperado que calculo el sistema y la diferencia (contado -
     * esperado). Una caja cerrada no se vuelve a cerrar.
     */
    public void cerrar(Long usuarioId, BigDecimal contado, BigDecimal esperado, LocalDateTime ahora) {
        if (!estaAbierta()) {
            throw new IllegalStateException("Esta caja ya está cerrada.");
        }
        ImporteCaja.validarEfectivo("efectivo contado", contado).ifPresent(mensaje -> {
            throw new IllegalArgumentException(mensaje);
        });
        abierta = null;
        fechaCierre = ahora;
        usuarioCierreId = usuarioId;
        efectivoContado = contado;
        efectivoEsperado = esperado;
        diferencia = contado.subtract(esperado);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public LocalDateTime getFechaApertura() { return fechaApertura; }
    public Long getUsuarioAperturaId() { return usuarioAperturaId; }
    public BigDecimal getEfectivoInicial() { return efectivoInicial; }
    public BigDecimal getEfectivoCierreAnterior() { return efectivoCierreAnterior; }
    public BigDecimal getDiferenciaApertura() { return diferenciaApertura; }
    public LocalDateTime getFechaCierre() { return fechaCierre; }
    public Long getUsuarioCierreId() { return usuarioCierreId; }
    public BigDecimal getEfectivoContado() { return efectivoContado; }
    public BigDecimal getEfectivoEsperado() { return efectivoEsperado; }
    public BigDecimal getDiferencia() { return diferencia; }
}
