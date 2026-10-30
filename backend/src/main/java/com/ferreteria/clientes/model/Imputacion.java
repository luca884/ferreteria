package com.ferreteria.clientes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Una imputación (CC-026, CC-041, GDS-20): «este crédito canceló este importe de esta deuda». El ORIGEN es el crédito: un
 * recibo, un Descuento o una nota de crédito (V39, CU-08). El DESTINO es la deuda: un comprobante o un Recargo. Registro inmutable (columnas
 * {@code updatable = false}, sin setters, nunca se borra). La única escritura posterior es la vigencia: al anular el recibo
 * de origen la imputación deja de estar {@code vigente} (CC-063), y eso lo hace una query nativa de la cuenta corriente, no la
 * entidad: por eso {@code vigente} tampoco es actualizable por JPA. Guarda el cliente propio para consultar los pendientes y
 * tomar los locks por cliente sin join; que el destino sea del cliente lo garantiza el servicio de cuenta corriente.
 * Referencia al origen y al destino solo por id, sin relaciones JPA. El pendiente de una deuda es lo que debe (Σ debe de sus
 * VENTA, o el importe del Recargo) menos Σ de sus imputaciones vigentes: nunca se guarda.
 */
@Entity
@Table(name = "imputacion")
public class Imputacion {

    /**
     * De dónde sale el crédito: exactamente uno de un recibo, un Descuento o una nota de crédito (el comprobante NOTA_CREDITO
     * de una venta a cuenta corriente, V39; ck_imputacion_origen_v39).
     */
    public record Origen(Long reciboId, Long ajusteId, Long notaDeCreditoId) {

        public static Origen recibo(Long reciboId) {
            return new Origen(reciboId, null, null);
        }

        public static Origen ajuste(Long ajusteId) {
            return new Origen(null, ajusteId, null);
        }

        /** La nota de crédito (o la anulación interna) de una venta a cuenta corriente (CU-08). */
        public static Origen notaDeCredito(Long notaDeCreditoId) {
            return new Origen(null, null, notaDeCreditoId);
        }
    }

    /** A qué deuda se aplica: exactamente uno de un comprobante o un Recargo (ck_imputacion_destino). */
    public record Destino(Long comprobanteId, Long ajusteId) {

        public static Destino comprobante(Long comprobanteId) {
            return new Destino(comprobanteId, null);
        }

        public static Destino ajuste(Long ajusteId) {
            return new Destino(null, ajusteId);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "cliente_id", nullable = false, updatable = false)
    private Long clienteId;

    /** El recibo de origen; null si el origen es un Descuento. */
    @Column(name = "recibo_id", updatable = false)
    private Long reciboId;

    /** El comprobante de destino; null si el destino es un Recargo. */
    @Column(name = "comprobante_id", updatable = false)
    private Long comprobanteId;

    /** El Descuento de origen (V28); null si el origen es un recibo. */
    @Column(name = "ajuste_origen_id", updatable = false)
    private Long ajusteOrigenId;

    /**
     * La nota de crédito de origen (V39): el comprobante NOTA_CREDITO; null si el origen es un recibo o un Descuento. Sus
     * imputaciones son siempre vigentes: la nota no se anula.
     */
    @Column(name = "nc_origen_id", updatable = false)
    private Long ncOrigenId;

    /** El Recargo de destino (V28); null si el destino es un comprobante. */
    @Column(name = "ajuste_destino_id", updatable = false)
    private Long ajusteDestinoId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal importe;

    /**
     * true si la creó sola la deuda nueva al consumir crédito (una venta a cuenta o un Recargo pagados con el saldo a favor);
     * false si la pidió el recibo o el Descuento, o si es la de una nota de crédito a la venta que anula (es la suya propia,
     * como la que pide un recibo; lo que otra deuda le consume después sí es automático). El recibo impreso y la cobranza repetida miran solo las no automáticas.
     */
    @Column(nullable = false, updatable = false)
    private boolean automatica;

    /** false cuando el recibo se anuló: deja de restar del comprobante (V22). Solo cambia por query, nunca por la entidad. */
    @Column(nullable = false, updatable = false)
    private boolean vigente;

    protected Imputacion() {}

    /** El recibo cancela un comprobante (CC-026): la forma de antes de V28. */
    public static Imputacion de(Long ferreteriaId, Long clienteId, Long reciboId, Long comprobanteId, BigDecimal importe) {
        if (reciboId == null || comprobanteId == null) {
            throw new IllegalArgumentException("La imputación necesita ferretería, cliente, recibo y comprobante.");
        }
        return armar(ferreteriaId, clienteId, Origen.recibo(reciboId), Destino.comprobante(comprobanteId), importe, false);
    }

    /** Un crédito (recibo, Descuento o nota de crédito) cancela una deuda (comprobante o Recargo) porque alguien lo pidió. */
    public static Imputacion de(Long ferreteriaId, Long clienteId, Origen origen, Destino destino, BigDecimal importe) {
        return armar(ferreteriaId, clienteId, origen, destino, importe, false);
    }

    /** Lo mismo; {@code automatica} marca la que crea sola la deuda nueva al consumir crédito. */
    public static Imputacion de(Long ferreteriaId, Long clienteId, Origen origen, Destino destino, BigDecimal importe,
            boolean automatica) {
        return armar(ferreteriaId, clienteId, origen, destino, importe, automatica);
    }

    // No se llama «de»: ModulosArchTest vigila quién llama a la fábrica «de», y las dos públicas comparten esto.
    private static Imputacion armar(Long ferreteriaId, Long clienteId, Origen origen, Destino destino, BigDecimal importe,
            boolean automatica) {
        if (ferreteriaId == null || clienteId == null || origen == null || destino == null) {
            throw new IllegalArgumentException("La imputación necesita ferretería, cliente, origen y destino.");
        }
        int origenes = (origen.reciboId() != null ? 1 : 0) + (origen.ajusteId() != null ? 1 : 0)
                + (origen.notaDeCreditoId() != null ? 1 : 0);
        if (origenes != 1) {
            throw new IllegalArgumentException("La imputación sale de exactamente un recibo, un Descuento o una nota de crédito.");
        }
        if ((destino.comprobanteId() == null) == (destino.ajusteId() == null)) {
            throw new IllegalArgumentException("La imputación va a exactamente un comprobante o un Recargo.");
        }
        if (importe == null || importe.signum() <= 0 || importe.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El importe imputado tiene que ser mayor que cero y de hasta 2 decimales.");
        }
        Imputacion imputacion = new Imputacion();
        imputacion.ferreteriaId = ferreteriaId;
        imputacion.clienteId = clienteId;
        imputacion.reciboId = origen.reciboId();
        imputacion.ajusteOrigenId = origen.ajusteId();
        imputacion.ncOrigenId = origen.notaDeCreditoId();
        imputacion.comprobanteId = destino.comprobanteId();
        imputacion.ajusteDestinoId = destino.ajusteId();
        imputacion.importe = importe;
        imputacion.automatica = automatica;
        imputacion.vigente = true;
        return imputacion;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getClienteId() { return clienteId; }
    public Long getReciboId() { return reciboId; }
    public Long getComprobanteId() { return comprobanteId; }
    public Long getAjusteOrigenId() { return ajusteOrigenId; }
    public Long getNcOrigenId() { return ncOrigenId; }
    public Long getAjusteDestinoId() { return ajusteDestinoId; }
    public BigDecimal getImporte() { return importe; }
    public boolean isAutomatica() { return automatica; }
    public boolean isVigente() { return vigente; }
}
