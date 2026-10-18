package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Un renglon del presupuesto. COPIA la descripcion, la unidad, el precio y la alicuota del articulo al momento de
 * emitirlo, como {@link ItemComprobante}: los cambios posteriores del articulo no lo alteran. Inmutable.
 */
@Entity
@Table(name = "item_presupuesto")
public class ItemPresupuesto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "presupuesto_id", nullable = false, updatable = false)
    private Long presupuestoId;

    /** Posicion en el ticket, desde 1. */
    @Column(nullable = false, updatable = false)
    private Integer renglon;

    @Column(name = "articulo_id", nullable = false, updatable = false)
    private Long articuloId;

    @Column(nullable = false, updatable = false, length = 200)
    private String descripcion;

    @Column(nullable = false, updatable = false, length = 10)
    private String unidad;

    @Column(nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal cantidad;

    /** Precio final con IVA incluido. */
    @Column(name = "precio_unitario", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal precioUnitario;

    @Column(name = "alicuota_iva", nullable = false, updatable = false, length = 10)
    private String alicuotaIva;

    /** Precio x cantidad, antes de descuentos. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal bruto;

    /** El descuento propio del renglon en %. El global vive en el presupuesto. */
    @Column(name = "descuento_porcentaje", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal descuentoPorcentaje;

    /** Ya con el descuento del renglon y el global. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal neto;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal iva;

    protected ItemPresupuesto() {}

    public static ItemPresupuesto de(
            Long ferreteriaId, Long presupuestoId, int renglon, Long articuloId, String descripcion, String unidad,
            String alicuotaIva, RenglonVenta calculado) {
        ItemPresupuesto item = new ItemPresupuesto();
        item.ferreteriaId = ferreteriaId;
        item.presupuestoId = presupuestoId;
        item.renglon = renglon;
        item.articuloId = articuloId;
        item.descripcion = descripcion;
        item.unidad = unidad;
        item.cantidad = calculado.cantidad();
        item.precioUnitario = calculado.precioUnitario();
        item.alicuotaIva = alicuotaIva;
        item.bruto = calculado.bruto();
        item.descuentoPorcentaje = calculado.descuentoPorcentaje();
        item.total = calculado.total();
        item.neto = calculado.neto();
        item.iva = calculado.iva();
        return item;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getPresupuestoId() { return presupuestoId; }
    public Integer getRenglon() { return renglon; }
    public Long getArticuloId() { return articuloId; }
    public String getDescripcion() { return descripcion; }
    public String getUnidad() { return unidad; }
    public BigDecimal getCantidad() { return cantidad; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public String getAlicuotaIva() { return alicuotaIva; }
    public BigDecimal getBruto() { return bruto; }
    public BigDecimal getDescuentoPorcentaje() { return descuentoPorcentaje; }
    public BigDecimal getTotal() { return total; }
    public BigDecimal getNeto() { return neto; }
    public BigDecimal getIva() { return iva; }
}
