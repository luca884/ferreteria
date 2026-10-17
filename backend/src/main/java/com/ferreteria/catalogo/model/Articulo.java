package com.ferreteria.catalogo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.ferreteria.common.HoraArgentina;
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

    private static final BigDecimal MARGEN_MAXIMO = BigDecimal.valueOf(1000);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Bloqueo optimista (V8): sube en 1 con cada escritura del articulo, menos el stock, que se mueve con un UPDATE
     * nativo que no la nombra. Long y no long: un articulo nuevo tiene la version nula hasta que se inserta.
     */
    @Version
    @Column(nullable = false)
    private Long version;

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

    /** Costo del proveedor, sin IVA, en la moneda del articulo. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal costo;

    /**
     * Moneda del costo. El precio final siempre se calcula y se guarda en pesos: para un articulo en USD,
     * costo x dolar vigente x margen x IVA.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 3)
    private Moneda moneda = Moneda.ARS;

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

    /**
     * Cache de la suma de movimientos de stock. Se mueve solo con un UPDATE relativo (ArticuloRepository.sumarStock),
     * nunca desde la entidad: updatable=false evita que guardar un articulo leido antes de una venta pise el
     * descuento con el valor viejo. Sigue siendo insertable (el alta inserta 0).
     */
    @Column(name = "stock_actual", nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal stockActual = BigDecimal.ZERO;

    @Column(name = "stock_minimo", nullable = false, precision = 15, scale = 3)
    private BigDecimal stockMinimo = BigDecimal.ZERO;

    @Column(length = 60)
    private String ubicacion;

    /**
     * Precio de lista tal como vino en el archivo del proveedor (con IVA si la lista lo trae, sin
     * bonificar). Lo fija solo la importacion de listas; actualizar no lo toca.
     */
    @Column(name = "precio_lista_proveedor", precision = 15, scale = 2)
    private BigDecimal precioListaProveedor;

    /**
     * Descuentos o recargos propios en %, negativo es descuento (hasta tres, encadenados despues de las
     * bonificaciones del proveedor). Los fija solo la importacion de listas: actualizar (el PUT) no los toca.
     */
    @Column(precision = 6, scale = 2)
    private BigDecimal ajuste1;

    @Column(precision = 6, scale = 2)
    private BigDecimal ajuste2;

    @Column(precision = 6, scale = 2)
    private BigDecimal ajuste3;

    /**
     * Base con la que se calculo el costo en la ultima importacion: las tres bonificaciones del proveedor de ese
     * momento y si la lista traia el IVA incluido (V7). Null es "sin base guardada". La fija solo la importacion
     * (registrarBaseDeLista): actualizar, ajustarCosto y fijarMargen no la tocan, ni siquiera el PUT que saca el
     * proveedor.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal bonificacion1;

    @Column(precision = 5, scale = 2)
    private BigDecimal bonificacion2;

    @Column(precision = 5, scale = 2)
    private BigDecimal bonificacion3;

    @Column(name = "lista_incluye_iva")
    private Boolean listaIncluyeIva;

    /** La ultima importacion de lista que toco este articulo; null si nunca lo toco una (alta manual, por ejemplo). */
    @Column(name = "ultima_importacion_id")
    private Long ultimaImportacionId;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = HoraArgentina.ahora();

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
        // Cambiar la moneda no convierte el costo: queda tal cual y el precio se recalcula con la moneda nueva.
        this.moneda = datos.moneda();
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

    /**
     * Importacion de listas: cambia el costo (sin IVA) y el precio de lista del proveedor, y
     * recalcula el precio final. No toca descripcion, rubro, margen, IVA, stock ni si esta activo.
     */
    public Optional<CambioPrecio> actualizarCosto(BigDecimal costoNuevo, BigDecimal precioLista, ContextoPrecio ctx) {
        if (costoNuevo == null || costoNuevo.signum() < 0) {
            throw new IllegalArgumentException("El costo debe ser un valor mayor o igual a 0.");
        }
        BigDecimal costoAnterior = this.costo;
        BigDecimal precioAnterior = this.precioFinal;

        this.costo = costoNuevo;
        registrarPrecioLista(precioLista);
        calcularPrecio(ctx);
        return CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal);
    }

    /**
     * Importacion de listas desde el corte 2: aplica una fila de la lista a este articulo. Fija el costo (ya con las
     * bonificaciones y los ajustes aplicados), el precio de lista del proveedor, los ajustes, y la moneda y la
     * alicuota de IVA si la fila las trae ({@code null} conserva las del articulo). Marca la importacion que lo
     * toco y recalcula el precio final. No toca descripcion, rubro, margen, stock ni si esta activo.
     *
     * <p>Devuelve el cambio de costo y de precio, o vacio si ninguno de los dos cambio (sin historial). Si la moneda
     * cambio el cambio SIEMPRE existe, aunque los numeros coincidan, y lleva la moneda anterior.
     *
     * @param ajustes los ajustes que quedan en el articulo, ya resueltos por posicion (conservar, borrar o valor)
     * @throws IllegalArgumentException si el costo es null o negativo
     */
    public Optional<CambioDeLista> aplicarLista(BigDecimal costoNuevo, BigDecimal precioLista, Moneda monedaNueva,
                                                AlicuotaIva alicuotaNueva, Ajustes ajustes, Long importacionId,
                                                ContextoPrecio ctx) {
        if (costoNuevo == null || costoNuevo.signum() < 0) {
            throw new IllegalArgumentException("El costo debe ser un valor mayor o igual a 0.");
        }
        BigDecimal costoAnterior = this.costo;
        BigDecimal precioAnterior = this.precioFinal;
        Moneda monedaAnterior = this.moneda;

        this.costo = costoNuevo;
        if (monedaNueva != null) {
            this.moneda = monedaNueva;
        }
        if (alicuotaNueva != null) {
            this.alicuotaIva = alicuotaNueva;
        }
        fijarAjustes(ajustes);
        registrarPrecioLista(precioLista);
        marcarImportacion(importacionId);
        calcularPrecio(ctx);

        Moneda cambioDeMoneda = monedaAnterior != this.moneda ? monedaAnterior : null;
        Optional<CambioPrecio> cambio = CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal);
        if (cambioDeMoneda != null) {
            // Se fuerza: un cambio de moneda siempre deja historial, aunque los numeros queden iguales.
            return Optional.of(new CambioDeLista(
                cambio.orElseGet(() -> new CambioPrecio(costoAnterior, this.costo, precioAnterior, this.precioFinal)),
                cambioDeMoneda));
        }
        return cambio.map(c -> new CambioDeLista(c, null));
    }

    /**
     * Actualizacion masiva (CU-05): cambia solo el costo y recalcula el precio final. No toca el
     * precio de lista del proveedor, el margen, el IVA, el stock ni si esta activo. El precio se
     * recalcula siempre, aunque el costo no cambie.
     */
    public Optional<CambioPrecio> ajustarCosto(BigDecimal costoNuevo, ContextoPrecio ctx) {
        if (costoNuevo == null || costoNuevo.signum() < 0) {
            throw new IllegalArgumentException("El costo debe ser un valor mayor o igual a 0.");
        }
        BigDecimal costoAnterior = this.costo;
        BigDecimal precioAnterior = this.precioFinal;

        this.costo = costoNuevo;
        calcularPrecio(ctx);
        return CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal);
    }

    /**
     * Actualizacion masiva (CU-05): fija el margen propio (de 0 a 1000, el cero se respeta) aunque el
     * articulo heredara el del rubro, y recalcula el precio final. El costo no cambia. Un cambio solo
     * del margen, con el mismo precio, devuelve vacio.
     */
    public Optional<CambioPrecio> fijarMargen(BigDecimal margen, ContextoPrecio ctx) {
        if (margen == null || margen.signum() < 0 || margen.compareTo(MARGEN_MAXIMO) > 0) {
            throw new IllegalArgumentException("El margen debe estar entre 0 y 1000.");
        }
        BigDecimal precioAnterior = this.precioFinal;

        this.margenPorcentaje = margen;
        calcularPrecio(ctx);
        return CambioPrecio.entre(this.costo, this.costo, precioAnterior, this.precioFinal);
    }

    /**
     * Fija solo el precio de lista del proveedor (filas "sin cambio" y altas). Se guarda a escala 2,
     * HALF_UP; no genera cambio de precio.
     */
    public void registrarPrecioLista(BigDecimal precioLista) {
        if (precioLista == null) {
            throw new IllegalArgumentException("El precio de lista es obligatorio.");
        }
        this.precioListaProveedor = precioLista.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Fija los tres ajustes (null es "sin ajuste"). No recalcula nada: el costo ya viene con los ajustes aplicados
     * ({@link CostoDeLista}).
     */
    public void fijarAjustes(Ajustes ajustes) {
        Ajustes nuevos = ajustes == null ? Ajustes.NINGUNO : ajustes;
        this.ajuste1 = nuevos.a1();
        this.ajuste2 = nuevos.a2();
        this.ajuste3 = nuevos.a3();
    }

    /**
     * Guarda la base con la que se calculo el costo de lista: las bonificaciones (null es ninguna) y si la lista traia
     * el IVA incluido. Lo llama solo la importacion, con las bonificaciones del proveedor de ese momento.
     */
    public void registrarBaseDeLista(Bonificaciones bonificaciones, boolean incluyeIva) {
        Bonificaciones guardadas = bonificaciones == null ? Bonificaciones.NINGUNA : bonificaciones;
        this.bonificacion1 = guardadas.b1();
        this.bonificacion2 = guardadas.b2();
        this.bonificacion3 = guardadas.b3();
        this.listaIncluyeIva = incluyeIva;
    }

    /** Las bonificaciones guardadas (V7); {@link Bonificaciones#NINGUNA} si no hay base guardada. */
    public Bonificaciones bonificacionesGuardadas() {
        return new Bonificaciones(bonificacion1, bonificacion2, bonificacion3);
    }

    /**
     * Si el articulo viene de una lista de proveedor y se puede recalcular su costo desde el precio de lista: tiene
     * precio de lista, proveedor y base guardada. Sin proveedor es "sin lista" aunque tenga precio de lista
     * (el PUT permite sacarlo y no borra el precio).
     */
    public boolean tieneLista() {
        return precioListaProveedor != null && proveedorId != null && listaIncluyeIva != null;
    }

    /**
     * Costo que sale del precio de lista con la base GUARDADA del articulo y los ajustes dados; vacio si no
     * {@link #tieneLista()}. Es el unico lugar que arma esta cuenta para un articulo.
     */
    public Optional<BigDecimal> costoDeLista(Ajustes ajustes) {
        if (!tieneLista()) {
            return Optional.empty();
        }
        return Optional.of(CostoDeLista.costo(
                precioListaProveedor, alicuotaIva, listaIncluyeIva, bonificacionesGuardadas(), ajustes));
    }

    /**
     * Edicion de los ajustes (corte 3): fija los tres, deja el costo en el que sale del precio de lista con la base
     * guardada y esos ajustes, y recalcula el precio. No toca el precio de lista, el margen, el IVA, la moneda, la
     * importacion, el stock, si esta activo ni las bonificaciones. Devuelve vacio si ni el costo ni el precio cambian
     * (por ejemplo, dos ajustes que solo cambian de posicion); las posiciones nuevas se guardan igual.
     *
     * @throws ArticuloSinListaException si el articulo no {@link #tieneLista()}: el servicio lo valida antes
     */
    public Optional<CambioPrecio> editarAjustes(Ajustes nuevos, ContextoPrecio ctx) {
        BigDecimal costoNuevo = costoDeLista(nuevos == null ? Ajustes.NINGUNO : nuevos)
                .orElseThrow(ArticuloSinListaException::new);
        BigDecimal costoAnterior = this.costo;
        BigDecimal precioAnterior = this.precioFinal;

        fijarAjustes(nuevos);
        this.costo = costoNuevo;
        calcularPrecio(ctx);
        return CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal);
    }

    /** Anota la importacion de lista que toco este articulo por ultima vez. */
    public void marcarImportacion(Long importacionId) {
        this.ultimaImportacionId = importacionId;
    }

    /** Baja logica: el articulo y su historial de precios se conservan. */
    public void desactivar() {
        this.activo = false;
    }

    /** Vuelve a activarlo despues de una baja logica. No toca precios ni stock. */
    public void reactivar() {
        this.activo = true;
    }

    private void calcularPrecio(ContextoPrecio ctx) {
        BigDecimal margen = PrecioCalculator.margenEfectivo(margenPorcentaje, ctx.margenRubro(), ctx.margenDefecto());
        this.precioFinal = PrecioCalculator.precioFinal(
                costo, margen, alicuotaIva, ctx.redondeoMultiplo(), ctx.cotizacionPara(moneda));
    }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getCodigoInterno() { return codigoInterno; }
    public String getCodigoBarras() { return codigoBarras; }
    public String getCodigoProveedor() { return codigoProveedor; }
    public String getDescripcion() { return descripcion; }
    public Long getRubroId() { return rubroId; }
    public Long getProveedorId() { return proveedorId; }
    public BigDecimal getCosto() { return costo; }
    public Moneda getMoneda() { return moneda; }
    public BigDecimal getMargenPorcentaje() { return margenPorcentaje; }
    public AlicuotaIva getAlicuotaIva() { return alicuotaIva; }
    public BigDecimal getPrecioFinal() { return precioFinal; }
    public Unidad getUnidad() { return unidad; }
    public BigDecimal getStockActual() { return stockActual; }
    public BigDecimal getStockMinimo() { return stockMinimo; }
    public String getUbicacion() { return ubicacion; }
    public BigDecimal getPrecioListaProveedor() { return precioListaProveedor; }
    public BigDecimal getAjuste1() { return ajuste1; }
    public BigDecimal getAjuste2() { return ajuste2; }
    public BigDecimal getAjuste3() { return ajuste3; }
    public Ajustes getAjustes() { return new Ajustes(ajuste1, ajuste2, ajuste3); }
    public BigDecimal getBonificacion1() { return bonificacion1; }
    public BigDecimal getBonificacion2() { return bonificacion2; }
    public BigDecimal getBonificacion3() { return bonificacion3; }
    public Boolean getListaIncluyeIva() { return listaIncluyeIva; }
    public Long getUltimaImportacionId() { return ultimaImportacionId; }
    public boolean isActivo() { return activo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
