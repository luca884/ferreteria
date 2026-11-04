package com.ferreteria.compras.model;

import com.ferreteria.common.HoraArgentina;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * La factura o el remito del proveedor (CU-13, tramo 1). Inmutable: el tramo 1 no anula ni edita una compra. El neto es
 * la suma de cantidad x costo unitario de los renglones, en la moneda de la compra (la de todos sus articulos). Copia el
 * nombre del proveedor, como el comprobante copia la razon social del cliente.
 */
@Entity
@Table(name = "compra")
public class Compra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "proveedor_id", nullable = false, updatable = false)
    private Long proveedorId;

    @Column(name = "proveedor_nombre", nullable = false, updatable = false, length = 100)
    private String proveedorNombre;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private TipoCompra tipo;

    @Column(name = "punto_venta", nullable = false, updatable = false)
    private Integer puntoVenta;

    @Column(nullable = false, updatable = false)
    private Long numero;

    @Column(nullable = false, updatable = false)
    private LocalDate fecha;

    @Column(nullable = false, updatable = false, length = 3)
    private String moneda;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal neto;

    /** Los recargos del costo que traia la compra (CU-13): se copian a cada articulo, igual que el costo. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private List<RecargoCompra> recargos = new ArrayList<>();

    @Column(name = "clave_idempotencia", nullable = false, updatable = false, length = 36)
    private String claveIdempotencia;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime registrada = HoraArgentina.ahora();

    protected Compra() {}

    public static Compra registrar(
            Long ferreteriaId, Long proveedorId, String proveedorNombre, TipoCompra tipo, int puntoVenta, long numero,
            LocalDate fecha, String moneda, BigDecimal neto, List<RecargoCompra> recargos, String claveIdempotencia,
            Long usuarioId) {
        Compra compra = new Compra();
        compra.ferreteriaId = ferreteriaId;
        compra.proveedorId = proveedorId;
        compra.proveedorNombre = proveedorNombre;
        compra.tipo = tipo;
        compra.puntoVenta = puntoVenta;
        compra.numero = numero;
        compra.fecha = fecha;
        compra.moneda = moneda;
        compra.neto = neto;
        compra.recargos = new ArrayList<>(recargos);
        compra.claveIdempotencia = claveIdempotencia;
        compra.usuarioId = usuarioId;
        return compra;
    }

    /** "Factura A 00003-00001234". */
    public String comprobante() {
        return tipo.comprobante(puntoVenta, numero);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getProveedorId() { return proveedorId; }
    public String getProveedorNombre() { return proveedorNombre; }
    public TipoCompra getTipo() { return tipo; }
    public Integer getPuntoVenta() { return puntoVenta; }
    public Long getNumero() { return numero; }
    public LocalDate getFecha() { return fecha; }
    public String getMoneda() { return moneda; }
    public BigDecimal getNeto() { return neto; }
    public List<RecargoCompra> getRecargos() { return List.copyOf(recargos); }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public Long getUsuarioId() { return usuarioId; }
    public LocalDateTime getRegistrada() { return registrada; }
}
