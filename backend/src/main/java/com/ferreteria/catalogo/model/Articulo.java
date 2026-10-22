package com.ferreteria.catalogo.model;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    static final String PRECIO_DEMASIADO_GRANDE = "Con este costo y margen el precio queda demasiado grande.";
    static final String PRECIO_CERO_O_MENOS = "Con los ajustes del precio final, el precio queda en 0 o menos.";
    static final String PRECIO_MANUAL_INVALIDO = "El precio tiene que ser mayor a 0.";
    static final String PRECIO_MANUAL_DEMASIADO_GRANDE = "El precio es demasiado grande.";
    static final String PRECIO_MANUAL_CON_DECIMALES = "El precio admite hasta 2 decimales.";
    static final String AJUSTES_DEMASIADO_GRANDES = "Con los ajustes del precio final, el precio queda demasiado grande.";

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

    /**
     * Precio fijado a mano por el Dueño (CU-14, como «Lista 1 calculada» destildada en GDS): mientras es true, nada
     * automatico recalcula el precio (listas, masivo, compras, cascada de rubro, cotizacion), aunque cambie el costo.
     */
    @Column(name = "precio_manual", nullable = false)
    private boolean precioManual;

    /** Ajustes sobre el precio final (CU-14), en el orden en que los cargo el Dueño. Con precio manual no se aplican. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ajustes_precio", nullable = false)
    private List<AjustePrecio> ajustesPrecio = new ArrayList<>();

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
        return actualizar(datos, PedidoDePrecio.CONSERVAR, ctx);
    }

    /**
     * Alta y edicion con lo que el Dueño pide sobre el precio (CU-14): primero los datos y los ajustes nuevos, despues
     * el precio, en un solo paso y con un solo cambio para el historial. Con {@code manual} true el precio queda el
     * escrito (mayor a 0 y dentro de DECIMAL(15,2), validado antes de tocar nada); con false se libera y se recalcula.
     */
    public Optional<CambioPrecio> actualizar(DatosArticulo datos, PedidoDePrecio pedido, ContextoPrecio ctx) {
        BigDecimal precioManualPedido = Boolean.TRUE.equals(pedido.manual()) ? precioManualValido(pedido.precio()) : null;
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
        if (pedido.ajustes() != null) {
            this.ajustesPrecio = new ArrayList<>(pedido.ajustes().lista());
        }
        if (pedido.manual() != null) {
            this.precioManual = pedido.manual();
        }

        if (precioManualPedido != null) {
            this.precioFinal = precioManualPedido;
        } else {
            calcularPrecio(ctx);
        }
        return CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal);
    }

    /** Recalcula el precio sin cambiar los datos (cascada de margen de rubro). */
    public Optional<CambioPrecio> recalcularPrecio(ContextoPrecio ctx) {
        BigDecimal precioAnterior = this.precioFinal;

        calcularPrecio(ctx);
        return CambioPrecio.entre(this.costo, this.costo, precioAnterior, this.precioFinal);
    }

    /**
     * Recalculo automatico (cascada del margen del rubro, cotizacion): igual que {@link #recalcularPrecio}, pero si los
     * ajustes sobre el precio final dejarian el precio en 0 o menos el articulo conserva su precio y el resultado trae
     * el aviso en vez de fallar el lote (S7).
     */
    public RecalculoAutomatico recalcularPrecioAutomatico(ContextoPrecio ctx) {
        try {
            return RecalculoAutomatico.de(recalcularPrecio(ctx));
        } catch (AjustesFueraDeRangoException e) {
            return new RecalculoAutomatico(Optional.empty(), Optional.of(avisoDePrecioSalteado(e)));
        }
    }

    /**
     * Compras (CU-13) al registrar: igual que {@link #ajustarCosto}, pero si los ajustes dejarian el precio en 0 o
     * menos el costo igual se pisa, el precio se conserva y el resultado trae el aviso (S7). Hay cambio (historial) si el
     * costo cambio, como siempre.
     */
    public RecalculoAutomatico ajustarCostoAutomatico(BigDecimal costoNuevo, ContextoPrecio ctx) {
        BigDecimal costoAnterior = this.costo;
        BigDecimal precioAnterior = this.precioFinal;
        try {
            return RecalculoAutomatico.de(ajustarCosto(costoNuevo, ctx));
        } catch (AjustesFueraDeRangoException e) {
            // ajustarCosto ya fijo el costo y fallo recien al calcular el precio, que no se toco.
            return new RecalculoAutomatico(
                CambioPrecio.entre(costoAnterior, this.costo, precioAnterior, this.precioFinal),
                Optional.of(avisoDePrecioSalteado(e)));
        }
    }

    /**
     * Vista previa de una compra con el criterio de {@link #ajustarCostoAutomatico}: el precio con ese costo o, si los
     * ajustes lo dejarian en 0 o menos, el de hoy con el aviso. No cambia nada.
     */
    public PrecioPlaneado precioConCostoAutomatico(BigDecimal costoNuevo, ContextoPrecio ctx) {
        try {
            return new PrecioPlaneado(precioConCosto(costoNuevo, ctx), Optional.empty());
        } catch (AjustesFueraDeRangoException e) {
            return new PrecioPlaneado(precioFinal, Optional.of(avisoDePrecioSalteado(e)));
        }
    }

    private String avisoDePrecioSalteado(AjustesFueraDeRangoException e) {
        return "No se cambió el precio de " + codigoInterno + " (" + descripcion
            + "): con sus ajustes quedaría " + e.queQuedaria() + ". Revisá sus ajustes.";
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

    /**
     * Fija los ajustes sobre el precio final (CU-14) y recalcula el precio, salvo con precio manual: ahi se guardan
     * para cuando se libere el precio. Si el precio quedaria en 0 o menos no cambia nada.
     */
    public Optional<CambioPrecio> fijarAjustesDePrecio(AjustesDePrecio nuevos, ContextoPrecio ctx) {
        AjustesDePrecio ajustes = nuevos == null ? AjustesDePrecio.NINGUNO : nuevos;
        BigDecimal precioAnterior = this.precioFinal;
        BigDecimal precioNuevo = precioManual ? this.precioFinal : precioCon(costo, ajustes, ctx);

        this.ajustesPrecio = new ArrayList<>(ajustes.lista());
        this.precioFinal = precioNuevo;
        return CambioPrecio.entre(this.costo, this.costo, precioAnterior, this.precioFinal);
    }

    /**
     * El Dueño escribe el precio final a mano (CU-14): queda marcado como manual y nada automatico lo vuelve a
     * calcular hasta {@link #liberarPrecio}. Tiene que ser mayor a 0 y entrar en DECIMAL(15,2).
     */
    public Optional<CambioPrecio> fijarPrecioManual(BigDecimal precio) {
        BigDecimal aDosDecimales = precioManualValido(precio);
        BigDecimal precioAnterior = this.precioFinal;

        this.precioManual = true;
        this.precioFinal = aDosDecimales;
        return CambioPrecio.entre(this.costo, this.costo, precioAnterior, this.precioFinal);
    }

    /**
     * El precio escrito a mano: mayor a 0, con hasta 2 decimales (no se redondea sin avisar) y dentro de DECIMAL(15,2),
     * o 400 en precioFinal.
     */
    private static BigDecimal precioManualValido(BigDecimal precio) {
        if (precio == null || precio.signum() <= 0) {
            throw new DatosInvalidosException(Map.of("precioFinal", PRECIO_MANUAL_INVALIDO));
        }
        if (precio.stripTrailingZeros().scale() > 2) {
            throw new DatosInvalidosException(Map.of("precioFinal", PRECIO_MANUAL_CON_DECIMALES));
        }
        BigDecimal aDosDecimales = precio.setScale(2, RoundingMode.UNNECESSARY);
        if (!TopeDeImporte.entra(aDosDecimales)) {
            throw new DatosInvalidosException(Map.of("precioFinal", PRECIO_MANUAL_DEMASIADO_GRANDE));
        }
        return aDosDecimales;
    }

    /** Saca la marca de precio manual y vuelve a calcular el precio con la regla y los ajustes. */
    public Optional<CambioPrecio> liberarPrecio(ContextoPrecio ctx) {
        if (!precioManual) {
            return Optional.empty();
        }
        BigDecimal precioAnterior = this.precioFinal;
        BigDecimal precioNuevo = precioCon(costo, getAjustesPrecio(), ctx);

        this.precioManual = false;
        this.precioFinal = precioNuevo;
        return CambioPrecio.entre(this.costo, this.costo, precioAnterior, this.precioFinal);
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

    /**
     * El precio tiene que entrar en DECIMAL(15,2): un costo y un margen absurdos (un tipeo con ceros de mas) dan un
     * 400 en {@code costo} antes de llegar a la base, que lo rechazaria como un "ya existe" engañoso.
     */
    private void calcularPrecio(ContextoPrecio ctx) {
        if (precioManual) {
            return;
        }
        this.precioFinal = precioConCosto(costo, ctx);
    }

    /**
     * El precio final que tendria el articulo con ese costo, sin cambiar nada (vista previa de una compra, CU-13).
     * Misma regla y mismo tope que {@link #calcularPrecio}: con precio manual es el precio de hoy.
     */
    public BigDecimal precioConCosto(BigDecimal costoNuevo, ContextoPrecio ctx) {
        if (precioManual) {
            return precioFinal;
        }
        return precioCon(costoNuevo, getAjustesPrecio(), ctx);
    }

    /** El aviso de margen negativo con ese costo, solo con precio manual (CU-14, {@link MargenNegativo}). */
    public Optional<String> margenNegativoCon(BigDecimal costoNuevo, ContextoPrecio ctx) {
        if (!precioManual) {
            return Optional.empty();
        }
        return MargenNegativo.mensaje(costoNuevo, ctx.cotizacionPara(moneda), alicuotaIva, precioFinal);
    }

    /**
     * La regla de precios con los ajustes sobre el precio final; el precio tiene que entrar en DECIMAL(15,2). Un costo
     * 0 sigue dando precio 0 (como antes de CU-14), pero los ajustes no pueden llevar el precio a 0 o menos.
     */
    private BigDecimal precioCon(BigDecimal costoNuevo, AjustesDePrecio ajustes, ContextoPrecio ctx) {
        BigDecimal margen = PrecioCalculator.margenEfectivo(margenPorcentaje, ctx.margenRubro(), ctx.margenDefecto());
        BigDecimal calculado = PrecioCalculator.precioFinal(
                costoNuevo, margen, alicuotaIva, ctx.redondeoMultiplo(), ctx.cotizacionPara(moneda));
        BigDecimal precio = ajustes.aplicar(calculado, ctx.redondeoMultiplo());
        if (!TopeDeImporte.entra(precio)) {
            // Si el calculado ya no entra, la culpa es del costo y el margen; si entra, de los ajustes.
            if (!TopeDeImporte.entra(calculado)) {
                throw new DatosInvalidosException(Map.of("costo", PRECIO_DEMASIADO_GRANDE));
            }
            throw new AjustesFueraDeRangoException(AJUSTES_DEMASIADO_GRANDES, "demasiado grande");
        }
        if (AjustesDePrecio.dejanElPrecioEnCeroOMenos(calculado, precio)) {
            throw new AjustesFueraDeRangoException(PRECIO_CERO_O_MENOS, "en 0 o menos");
        }
        return precio;
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
    public boolean isPrecioManual() { return precioManual; }
    public AjustesDePrecio getAjustesPrecio() { return new AjustesDePrecio(ajustesPrecio); }
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
