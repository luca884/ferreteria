package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Un renglon de la venta. COPIA la descripcion, la unidad, el precio y la alicuota del articulo al momento
 * de vender: los cambios posteriores del articulo no lo alteran (R-004). Inmutable.
 */
@Entity
@Table(name = "item_comprobante")
public class ItemComprobante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "comprobante_id", nullable = false, updatable = false)
    private Long comprobanteId;

    /** Posicion en el ticket, desde 1. */
    @Column(nullable = false, updatable = false)
    private Integer renglon;

    @Column(name = "articulo_id", nullable = false, updatable = false)
    private Long articuloId;

    @Column(nullable = false, updatable = false, length = 200)
    private String descripcion;

    /** Nombre de la unidad (UNIDAD, METRO...): texto, para no depender del modelo del catalogo. */
    @Column(nullable = false, updatable = false, length = 10)
    private String unidad;

    @Column(nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal cantidad;

    /** Precio final con IVA incluido. */
    @Column(name = "precio_unitario", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal precioUnitario;

    /** Nombre de la alicuota (IVA_21...), tal como la dio el servidor. */
    @Column(name = "alicuota_iva", nullable = false, updatable = false, length = 10)
    private String alicuotaIva;

    /** Precio x cantidad, antes de descuentos. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal bruto;

    /** El descuento propio del renglon en %. El global vive en el comprobante. */
    @Column(name = "descuento_porcentaje", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal descuentoPorcentaje;

    /** Ya con el descuento del renglon y el global. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal neto;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal iva;

    /** La parte del recargo de la forma de pago que le toca al renglon (GDS-11); ya esta sumada en el total. */
    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal recargo;

    /**
     * El costo del articulo al vender, sin IVA y en pesos (G66): la ganancia de los reportes no cambia si el costo cambia
     * despues. Null en las ventas anteriores a la V26 y si el articulo estaba en USD sin dolar guardado.
     */
    @Column(name = "costo_unitario", updatable = false, precision = 15, scale = 4)
    private BigDecimal costoUnitario;

    /** costoUnitario x cantidad, a 2 decimales. Null junto con costoUnitario. */
    @Column(updatable = false, precision = 15, scale = 2)
    private BigDecimal costo;

    protected ItemComprobante() {}

    public static ItemComprobante de(
            Long ferreteriaId, Long comprobanteId, int renglon, Long articuloId, String descripcion, String unidad,
            String alicuotaIva, BigDecimal costoUnitario, RenglonVenta calculado) {
        ItemComprobante item = new ItemComprobante();
        item.ferreteriaId = ferreteriaId;
        item.comprobanteId = comprobanteId;
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
        item.recargo = calculado.recargo();
        item.costoUnitario = costoUnitario;
        item.costo = costoUnitario == null
                ? null
                : costoUnitario.multiply(calculado.cantidad()).setScale(2, RoundingMode.HALF_UP);
        return item;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getComprobanteId() { return comprobanteId; }
    public Integer getRenglon() { return renglon; }
    public Long getArticuloId() { return articuloId; }
    public String getDescripcion() { return descripcion; }
    public String getUnidad() { return unidad; }
    public BigDecimal getCantidad() { return cantidad; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public String getAlicuotaIva() { return alicuotaIva; }
    public BigDecimal getBruto() { return bruto; }
    public BigDecimal getDescuentoPorcentaje() { return descuentoPorcentaje; }
    public BigDecimal getRecargo() { return recargo; }
    public BigDecimal getCostoUnitario() { return costoUnitario; }
    public BigDecimal getCosto() { return costo; }
    public BigDecimal getTotal() { return total; }
    public BigDecimal getNeto() { return neto; }
    public BigDecimal getIva() { return iva; }
}
