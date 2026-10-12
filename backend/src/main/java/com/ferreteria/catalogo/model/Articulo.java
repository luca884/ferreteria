package com.ferreteria.catalogo.model;

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
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Articulo del catalogo de una ferreteria. El costo va sin IVA; el precio final se guarda
 * calculado. El rubro y el proveedor se guardan como id (sin @ManyToOne): el servicio los
 * resuelve siempre con findScoped para que un id de otra ferreteria devuelva 404.
 */
@Entity
@Table(name = "articulo")
public class Articulo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "codigo_interno", nullable = false, length = 30)
    private String codigoInterno;

    @Column(name = "codigo_barras", length = 30)
    private String codigoBarras;

    @Column(name = "codigo_proveedor", length = 40)
    private String codigoProveedor;

    @Column(nullable = false, length = 200)
    private String descripcion;

    @Column(name = "rubro_id")
    private Long rubroId;

    @Column(name = "proveedor_id")
    private Long proveedorId;

    /** Costo del proveedor, sin IVA. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal costo;

    /** Margen propio en porcentaje; si es null hereda el del rubro o el de la ferreteria. */
    @Column(name = "margen_porcentaje", precision = 6, scale = 2)
    private BigDecimal margenPorcentaje;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "alicuota_iva", nullable = false, length = 10)
    private AlicuotaIva alicuotaIva = AlicuotaIva.IVA_21;

    @Column(name = "precio_final", nullable = false, precision = 15, scale = 2)
    private BigDecimal precioFinal;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private Unidad unidad = Unidad.UNIDAD;

    /** Se mueve solo con movimientos de stock (CU-09), no se edita desde el articulo. */
    @Column(name = "stock_actual", nullable = false, precision = 15, scale = 3)
    private BigDecimal stockActual = BigDecimal.ZERO;

    @Column(name = "stock_minimo", nullable = false, precision = 15, scale = 3)
    private BigDecimal stockMinimo = BigDecimal.ZERO;

    @Column(length = 60)
    private String ubicacion;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    protected Articulo() {}

    public Articulo(Long ferreteriaId) {
        this.ferreteriaId = ferreteriaId;
    }

    /**
     * Aplica los datos editables y recalcula el precio final. Sirve para el alta y la edicion.
     * Devuelve el cambio de costo/precio si hubo alguno (en el alta, con anteriores null). No toca
     * el stock actual.
     */
    public Optional<CambioPrecio> actualizar(DatosArticulo datos, ContextoPrecio ctx) {
        BigDecimal costoAnterior = this.costo;
        BigDecimal precioAnterior = this.precioFinal;

        this.codigoInterno = datos.codigoInterno();
        this.codigoBarras = datos.codigoBarras();
        this.codigoProveedor = datos.codigoProveedor();
        this.descripcion = datos.descripcion();
        this.rubroId = datos.rubroId();
        this.proveedorId = datos.proveedorId();
        this.costo = datos.costo();
        this.margenPorcentaje = datos.margenPorcentaje();
        this.alicuotaIva = datos.alicuotaIva();
        this.unidad = datos.unidad();
        this.stockMinimo = datos.stockMinimo();
        this.ubicacion = datos.ubicacion();
        this.activo = datos.activo();

        calcularPrecio(ctx);
        return CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal);
    }

    /** Recalcula el precio sin cambiar los datos (cascada de margen de rubro). */
    public Optional<CambioPrecio> recalcularPrecio(ContextoPrecio ctx) {
        BigDecimal precioAnterior = this.precioFinal;

        calcularPrecio(ctx);
        return CambioPrecio.entre(this.costo, this.costo, precioAnterior, this.precioFinal);
    }

    /** Baja logica: el articulo y su historial de precios se conservan. */
    public void desactivar() {
        this.activo = false;
    }

    private void calcularPrecio(ContextoPrecio ctx) {
        BigDecimal margen = PrecioCalculator.margenEfectivo(margenPorcentaje, ctx.margenRubro(), ctx.margenDefecto());
        this.precioFinal = PrecioCalculator.precioFinal(costo, margen, alicuotaIva, ctx.redondeoMultiplo());
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getCodigoInterno() { return codigoInterno; }
    public String getCodigoBarras() { return codigoBarras; }
    public String getCodigoProveedor() { return codigoProveedor; }
    public String getDescripcion() { return descripcion; }
    public Long getRubroId() { return rubroId; }
    public Long getProveedorId() { return proveedorId; }
    public BigDecimal getCosto() { return costo; }
    public BigDecimal getMargenPorcentaje() { return margenPorcentaje; }
    public AlicuotaIva getAlicuotaIva() { return alicuotaIva; }
    public BigDecimal getPrecioFinal() { return precioFinal; }
    public Unidad getUnidad() { return unidad; }
    public BigDecimal getStockActual() { return stockActual; }
    public BigDecimal getStockMinimo() { return stockMinimo; }
    public String getUbicacion() { return ubicacion; }
    public boolean isActivo() { return activo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
