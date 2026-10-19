package com.ferreteria.clientes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Una imputación (CC-026, CC-041): «este recibo canceló este importe de este comprobante». Registro inmutable y append-only
 * (columnas {@code updatable = false}, sin setters, nunca se borra). Guarda el cliente propio para consultar los pendientes y
 * tomar los locks por cliente sin join; que el comprobante sea del cliente lo garantiza el servicio de cuenta corriente.
 * Referencia al recibo y al comprobante solo por id, sin relaciones JPA. El pendiente de un comprobante es lo que debe
 * (Σ debe de sus VENTA) menos Σ de sus imputaciones: nunca se guarda.
 */
@Entity
@Table(name = "imputacion")
public class Imputacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "cliente_id", nullable = false, updatable = false)
    private Long clienteId;

    @Column(name = "recibo_id", nullable = false, updatable = false)
    private Long reciboId;

    @Column(name = "comprobante_id", nullable = false, updatable = false)
    private Long comprobanteId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal importe;

    protected Imputacion() {}

    public static Imputacion de(Long ferreteriaId, Long clienteId, Long reciboId, Long comprobanteId, BigDecimal importe) {
        if (ferreteriaId == null || clienteId == null || reciboId == null || comprobanteId == null) {
            throw new IllegalArgumentException("La imputación necesita ferretería, cliente, recibo y comprobante.");
        }
        if (importe == null || importe.signum() <= 0 || importe.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El importe imputado tiene que ser mayor que cero y de hasta 2 decimales.");
        }
        Imputacion imputacion = new Imputacion();
        imputacion.ferreteriaId = ferreteriaId;
        imputacion.clienteId = clienteId;
        imputacion.reciboId = reciboId;
        imputacion.comprobanteId = comprobanteId;
        imputacion.importe = importe;
        return imputacion;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getClienteId() { return clienteId; }
    public Long getReciboId() { return reciboId; }
    public Long getComprobanteId() { return comprobanteId; }
    public BigDecimal getImporte() { return importe; }
}
