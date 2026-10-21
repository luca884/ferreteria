package com.ferreteria.compras.model;

import com.ferreteria.catalogo.api.CostoDeCompra;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Un renglon de la compra (CU-13). Copia los datos del articulo al confirmar, y el costo y el precio que tenia antes y
 * el precio con el que quedo: asi el detalle muestra que cambio aunque el articulo cambie despues.
 */
@Entity
@Table(name = "item_compra")
public class ItemCompra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "compra_id", nullable = false, updatable = false)
    private Long compraId;

    @Column(nullable = false, updatable = false)
    private Integer renglon;

    @Column(name = "articulo_id", nullable = false, updatable = false)
    private Long articuloId;

    @Column(name = "codigo_interno", nullable = false, updatable = false, length = 30)
    private String codigoInterno;

    @Column(nullable = false, updatable = false, length = 200)
    private String descripcion;

    @Column(nullable = false, updatable = false, length = 10)
    private String unidad;

    @Column(nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal cantidad;

    @Column(name = "costo_unitario", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal costoUnitario;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "costo_anterior", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal costoAnterior;

    @Column(name = "precio_anterior", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal precioAnterior;

    @Column(name = "precio_nuevo", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal precioNuevo;

    protected ItemCompra() {}

    public static ItemCompra de(Long ferreteriaId, Long compraId, int renglon, BigDecimal cantidad, CostoDeCompra costo) {
        ItemCompra item = new ItemCompra();
        item.ferreteriaId = ferreteriaId;
        item.compraId = compraId;
        item.renglon = renglon;
        item.articuloId = costo.articuloId();
        item.codigoInterno = costo.codigoInterno();
        item.descripcion = costo.descripcion();
        item.unidad = costo.unidad();
        item.cantidad = cantidad;
        item.costoUnitario = costo.costoNuevo();
        item.subtotal = subtotal(cantidad, costo.costoNuevo());
        item.costoAnterior = costo.costoAnterior();
        item.precioAnterior = costo.precioAnterior();
        item.precioNuevo = costo.precioNuevo();
        return item;
    }

    /** cantidad x costo unitario, al centavo (HALF_UP). */
    public static BigDecimal subtotal(BigDecimal cantidad, BigDecimal costoUnitario) {
        return cantidad.multiply(costoUnitario).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getCompraId() { return compraId; }
    public Integer getRenglon() { return renglon; }
    public Long getArticuloId() { return articuloId; }
    public String getCodigoInterno() { return codigoInterno; }
    public String getDescripcion() { return descripcion; }
    public String getUnidad() { return unidad; }
    public BigDecimal getCantidad() { return cantidad; }
    public BigDecimal getCostoUnitario() { return costoUnitario; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getCostoAnterior() { return costoAnterior; }
    public BigDecimal getPrecioAnterior() { return precioAnterior; }
    public BigDecimal getPrecioNuevo() { return precioNuevo; }
}
