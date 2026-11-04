package com.ferreteria.catalogo.model;

import com.ferreteria.catalogo.model.VariacionFuerte.Sujeto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Planifica una importacion de lista de precios: clasifica cada fila (sube, baja, sin cambio, nueva
 * o con error) sin tocar nada. Dominio puro y determinista: el mismo input da siempre el mismo plan,
 * y la verificacion de deriva del aplicar depende de eso. No mira {@code crearNuevos}: la clasificacion es la misma con
 * o sin la opcion de crear. El rubro elegido ({@link EntradaPlan#rubroElegidoId()}) solo cambia el precio que se muestra
 * de los nuevos sin rubro propio, nunca la clasificacion.
 *
 * <p>Orden de validacion de cada fila (se informa solo el primer error): codigo, codigo repetido,
 * costo, costo calculado en cero y, para las que no coinciden con ningun articulo, si se puede crear.
 * La moneda de la fila (columna o moneda fija) decide en que moneda queda el articulo: si cambia, la fila es un
 * "cambio de moneda" y no se compara el costo; si no, se compara en la moneda del articulo. Los articulos en dolares
 * se calculan con el dolar vigente del contexto y, con la cotizacion apagada o sin ningun valor, la fila da error.
 */
public final class PlanificadorImportacion {

    /** Una variacion de costo mayor a este porcentaje (hacia arriba o abajo) genera una advertencia. */
    public static final BigDecimal UMBRAL_VARIACION_PORCENTAJE = VariacionFuerte.UMBRAL_PORCENTAJE;

    private static final int LARGO_MAXIMO_CODIGO_PROVEEDOR = 40;
    private static final int LARGO_MAXIMO_CODIGO_INTERNO = 30;
    private static final int LARGO_MAXIMO_DESCRIPCION = 200;
    private static final String NO_CREABLE = "No se puede crear como artículo nuevo: ";
    private static final int MAXIMO_DIGITOS_ENTEROS = 10;
    private static final String MENSAJE_COTIZACION_APAGADA = "Activá la cotización para cargar artículos en dólares";
    private static final String MENSAJE_SIN_COTIZACION = "Todavía no hay cotización del dólar. Probá en unos minutos";
    /** Por hoja y por fila; lo que no tiene hoja o fila (las advertencias generales) va antes. */
    private static final Comparator<AvisoDeFila> PORLUGAR = Comparator
        .comparing(AvisoDeFila::hoja, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
        .thenComparing(AvisoDeFila::fila, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()));
    private static final String MENSAJE_COSTO_CERO =
        "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.";
    private static final String MENSAJE_AJUSTES_EN_CERO =
        "Con los ajustes del precio final, el precio queda en 0 o menos.";
    private static final String MENSAJE_DOLARES_SIN_MONEDA = "El costo está en dólares, pero la lista no dice la "
        + "moneda. Mapeá la columna de moneda o elegí la moneda de todas las filas.";

    private PlanificadorImportacion() {
    }

    public static PlanImportacion planificar(EntradaPlan entrada) {
        ZonaDeDatos.Resultado zona = ZonaDeDatos.clasificar(libroLeido(entrada),
            entrada.mapeo().conEncabezados(entrada.encabezado()));
        List<FilaDeDatos> filas = extraerFilas(zona.filas(), entrada.mapeo(), !entrada.hojas().isEmpty());
        Map<String, List<FilaDeDatos>> filasPorClave = filasPorClave(filas);
        Map<String, ArticuloVigente> articulosPorClave = indexar(entrada.articulosDelProveedor());

        Acumulado acumulado = new Acumulado();
        acumulado.barrasEnUso.addAll(ocupadas(entrada));
        Set<String> presentes = new HashSet<>();

        for (FilaDeDatos d : filas) {
            Optional<String> errorDeCodigo = errorDeCodigo(d);
            if (errorDeCodigo.isPresent()) {
                acumulado.errores.add(aviso(d, errorDeCodigo.get()));
                continue;
            }
            presentes.add(d.clave());

            List<FilaDeDatos> repetidas = filasPorClave.get(d.clave());
            if (repetidas.size() > 1) {
                acumulado.errores.add(aviso(d, "El código aparece repetido en la lista ("
                    + ubicaciones(repetidas, entrada.hojas()) + ")."));
                continue;
            }
            Optional<String> errorDeCosto = errorDeCosto(d);
            if (errorDeCosto.isPresent()) {
                acumulado.errores.add(aviso(d, errorDeCosto.get()));
                continue;
            }
            Campos campos = Campos.leer(d, entrada.mapeo());
            if (campos.error() != null) {
                acumulado.errores.add(aviso(d, campos.error()));
                continue;
            }
            ArticuloVigente articulo = articulosPorClave.get(d.clave());
            boolean aceptada = articulo != null
                ? clasificar(d, articulo, campos, entrada, acumulado)
                : planificarNuevo(d, campos, entrada, acumulado);
            if (aceptada && campos.advertenciaDeIva() != null) {
                acumulado.advertencias.add(aviso(d, campos.advertenciaDeIva()));
            }
        }

        List<ArticuloVigente> noEstan = entrada.articulosDelProveedor().stream()
            .filter(ArticuloVigente::activo)
            .filter(a -> {
                String clave = NormalizadorCodigo.clave(a.codigoProveedor());
                return clave == null || !presentes.contains(clave);
            })
            .toList();

        acumulado.errores.sort(PORLUGAR);
        acumulado.advertencias.sort(PORLUGAR);
        // Las de encabezado y las de hojas sin encabezado no son de ninguna fila: van primero.
        List<AvisoDeFila> generales = new ArrayList<>(advertenciasDeEncabezado(entrada));
        generales.addAll(zona.advertencias());
        acumulado.advertencias.addAll(0, generales);
        ConteosImportacion conteos = new ConteosImportacion(
            (int) acumulado.cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) > 0).count(),
            (int) acumulado.cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) < 0).count(),
            acumulado.altas.size(), acumulado.sinCambio.size(), noEstan.size(), acumulado.errores.size(),
            acumulado.cambiosDeMoneda.size(), acumulado.correcciones.size(), zona.salteadas());
        return new PlanImportacion(conteos, acumulado.cambios, acumulado.altas, acumulado.sinCambio, acumulado.errores,
            acumulado.advertencias, noEstan, acumulado.cambiosDeMoneda, acumulado.correcciones, zona.salteadas());
    }

    /**
     * Los codigos (con trim) de las filas de datos que podrian ser codigo interno de un articulo
     * nuevo, es decir de 30 caracteres o menos, sin repetir segun la clave. El servicio los usa para
     * preguntar de una vez cuales ya estan ocupados. Las filas anteriores a {@code filaDesde} no cuentan.
     */
    public static List<String> codigosCandidatos(List<FilaLeida> filas, MapeoLista mapeo) {
        return codigosCandidatosDeLaZona(filas.stream().filter(fila -> fila.numero() >= mapeo.filaDesde()).toList(), mapeo);
    }

    /**
     * Lo mismo para las filas de la zona de datos ({@link ZonaDeDatos}), donde cada hoja arranca en su encabezado:
     * aca no se mira {@code filaDesde}.
     */
    public static List<String> codigosCandidatosDeLaZona(List<FilaLeida> filasDeDatos, MapeoLista mapeo) {
        Map<String, String> porClave = new LinkedHashMap<>();
        for (FilaLeida fila : filasDeDatos) {
            String codigo = recortar(fila.celda(mapeo.columnaCodigo()).texto());
            if (!codigo.isEmpty() && largo(codigo) <= LARGO_MAXIMO_CODIGO_INTERNO) {
                porClave.putIfAbsent(NormalizadorCodigo.clave(codigo), codigo);
            }
        }
        return new ArrayList<>(porClave.values());
    }

    /**
     * Los codigos de barras (sin espacios sobrantes) de las filas de datos, sin repetir y en el orden del archivo,
     * para preguntar de una vez cuales ya usa un articulo activo. Los que no se pueden leer (de mas de 30
     * caracteres) no se consultan: la fila ya da error por su cuenta.
     */
    public static List<String> codigosDeBarras(List<FilaLeida> filasDeDatos, MapeoLista mapeo) {
        if (mapeo.columnaCodigoBarras() == null) {
            return List.of();
        }
        Map<String, String> porClave = new LinkedHashMap<>();
        for (FilaLeida fila : filasDeDatos) {
            CodigoBarrasDeLista.ResultadoBarras barras = CodigoBarrasDeLista.interpretar(fila.celda(mapeo.columnaCodigoBarras()));
            if (barras.esValor()) {
                porClave.putIfAbsent(NormalizadorCodigo.clave(barras.codigo()), barras.codigo());
            }
        }
        return new ArrayList<>(porClave.values());
    }

    private static List<AvisoDeFila> advertenciasDeEncabezado(EntradaPlan entrada) {
        return entrada.mapeo().conEncabezados(entrada.encabezado()).advertenciasContra(entrada.mapeoGuardado())
            .stream()
            .map(mensaje -> new AvisoDeFila(entrada.hojas().isEmpty() ? null : entrada.mapeo().hoja(), null, null, mensaje))
            .toList();
    }

    /** Las listas que se van llenando fila a fila. */
    private static final class Acumulado {
        private final List<AvisoDeFila> errores = new ArrayList<>();
        private final List<AvisoDeFila> advertencias = new ArrayList<>();
        private final List<CambioPlaneado> cambios = new ArrayList<>();
        private final List<PrecioListaPlaneado> sinCambio = new ArrayList<>();
        private final List<AltaPlaneada> altas = new ArrayList<>();
        private final List<CambioPlaneado> cambiosDeMoneda = new ArrayList<>();
        private final List<CambioPlaneado> correcciones = new ArrayList<>();
        /** Claves de los codigos de barras ya usados: los de articulos activos y los de las altas ya planeadas. */
        private final Set<String> barrasEnUso = new HashSet<>();
    }

    // ------------------------------------------------------------------ coincidencia

    /**
     * Una fila que coincide con un articulo: cambia de moneda, sube, baja, se corrige, sin cambio, o error si el costo
     * calculado es 0 o la cotizacion no alcanza. Devuelve false si la fila quedo como error. El costo se calcula con la
     * alicuota de la fila si la trae y, si no, con la del articulo, y con los ajustes de la fila (las columnas que no
     * estan mapeadas conservan los del articulo). La moneda de la fila decide la del articulo; sin dato conserva la
     * suya. El precio nuevo es solo para mostrar.
     */
    private static boolean clasificar(FilaDeDatos d, ArticuloVigente articulo, Campos campos, EntradaPlan entrada,
                                      Acumulado acumulado) {
        Moneda monedaFinal = campos.moneda() != null ? campos.moneda() : articulo.moneda();
        Optional<String> errorDeCotizacion =
            errorDeCotizacion(monedaFinal, articulo.moneda() == Moneda.USD, entrada);
        if (errorDeCotizacion.isPresent()) {
            acumulado.errores.add(aviso(d, errorDeCotizacion.get()));
            return false;
        }
        AlicuotaIva alicuota = campos.alicuota() != null ? campos.alicuota() : articulo.alicuotaIva();
        Ajustes ajustes = campos.ajustesSobre(articulo.ajustes());
        BigDecimal costoNuevo = costoCalculado(d.precioLista(), alicuota, ajustes, entrada);
        if (costoNuevo.signum() == 0) {
            acumulado.errores.add(aviso(d, MENSAJE_COSTO_CERO));
            return false;
        }
        BigDecimal precioNuevo = precioNuevo(articulo, costoNuevo, alicuota, monedaFinal, entrada);
        if (precioNuevo.signum() <= 0 && !articulo.ajustesPrecio().estaVacia()) {
            acumulado.errores.add(aviso(d, MENSAJE_AJUSTES_EN_CERO));
            return false;
        }
        articulo.margenNegativo(costoNuevo, alicuota, contexto(articulo, entrada).cotizacionPara(monedaFinal))
            .ifPresent(mensaje -> acumulado.advertencias.add(aviso(d, mensaje)));
        CambioPlaneado cambio = cambio(d, articulo, costoNuevo, precioNuevo, monedaFinal, alicuota, ajustes, false);
        if (monedaFinal != articulo.moneda()) {
            // Un costo en dolares no se compara con uno en pesos: es un tipo propio.
            acumulado.cambiosDeMoneda.add(cambio);
            return true;
        }
        int comparacion = costoNuevo.compareTo(articulo.costo());
        if (comparacion != 0) {
            Optional<String> variacion = VariacionFuerte.mensaje(Sujeto.COSTO, articulo.costo(), costoNuevo);
            variacion.ifPresent(mensaje -> acumulado.advertencias.add(aviso(d, mensaje)));
            acumulado.cambios.add(cambio(d, articulo, costoNuevo, precioNuevo, monedaFinal, alicuota, ajustes,
                variacion.isPresent()));
            return true;
        }
        boolean cambianLosAjustes = !ajustes.equals(articulo.ajustes());
        boolean cambiaLaAlicuota = alicuota != articulo.alicuotaIva();
        boolean cambiaElPrecio = precioNuevo.compareTo(articulo.precioFinal()) != 0;
        // "Corrige" = cambian los ajustes, la alicuota o el precio (aunque el costo sea el mismo, p. ej. un precio
        // guardado desactualizado): la vista previa lo muestra y aplicar lo guarda. Con la alicuota se factura, asi que
        // una correccion de IVA lo es aunque el redondeo deje el precio igual (decision 37); si el precio no cambia, no
        // hay historial. "Sin cambio" no cambia nada del articulo.
        if (cambianLosAjustes || cambiaLaAlicuota || cambiaElPrecio) {
            acumulado.correcciones.add(cambio);
        } else {
            acumulado.sinCambio.add(new PrecioListaPlaneado(articulo.id(), d.precioLista(), d.hoja(), d.numero(),
                d.codigo(), articulo.descripcion(), costoNuevo));
        }
        return true;
    }

    private static CambioPlaneado cambio(FilaDeDatos d, ArticuloVigente articulo, BigDecimal costoNuevo,
                                         BigDecimal precioNuevo, Moneda monedaFinal, AlicuotaIva alicuota,
                                         Ajustes ajustes, boolean variacionFuerte) {
        return new CambioPlaneado(d.numero(), articulo.id(), d.codigo(), articulo.descripcion(), d.precioLista(),
            articulo.costo(), costoNuevo, articulo.precioFinal(), precioNuevo, articulo.activo(), variacionFuerte,
            d.hoja(), articulo.moneda(), monedaFinal, articulo.alicuotaIva(), alicuota, articulo.ajustes(), ajustes);
    }

    /**
     * Las reglas de cotizacion de la carga manual (corte 1) aplicadas por fila: un articulo que queda en dolares
     * sin haberlo estado exige la cotizacion activada, y todo articulo en dolares necesita un dolar vigente.
     */
    private static Optional<String> errorDeCotizacion(Moneda monedaFinal, boolean yaEraEnDolares, EntradaPlan entrada) {
        if (monedaFinal != Moneda.USD) {
            return Optional.empty();
        }
        if (!yaEraEnDolares && !entrada.cotizacionActiva()) {
            return Optional.of(MENSAJE_COTIZACION_APAGADA);
        }
        if (entrada.contextoSinRubro().cotizacionUsd() == null) {
            return Optional.of(MENSAJE_SIN_COTIZACION);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ nuevos

    /**
     * Una fila sin coincidencia: alta si se puede crear, error si no (devuelve false). El costo se calcula con la
     * alicuota de la fila, o IVA_21 si no la trae, y con los ajustes de la fila; su error tiene prioridad sobre "no
     * creable".
     */
    private static boolean planificarNuevo(FilaDeDatos d, Campos campos, EntradaPlan entrada, Acumulado acumulado) {
        Moneda moneda = campos.moneda() != null ? campos.moneda() : Moneda.ARS;
        Optional<String> errorDeCotizacion = errorDeCotizacion(moneda, false, entrada);
        if (errorDeCotizacion.isPresent()) {
            acumulado.errores.add(aviso(d, errorDeCotizacion.get()));
            return false;
        }
        AlicuotaIva alicuota = campos.alicuota() != null ? campos.alicuota() : AlicuotaIva.IVA_21;
        Ajustes ajustes = campos.ajustesSobre(Ajustes.NINGUNO);
        BigDecimal costo = costoCalculado(d.precioLista(), alicuota, ajustes, entrada);
        if (costo.signum() == 0) {
            acumulado.errores.add(aviso(d, MENSAJE_COSTO_CERO));
            return false;
        }
        if (largo(d.codigo()) > LARGO_MAXIMO_CODIGO_INTERNO) {
            acumulado.errores.add(aviso(d, NO_CREABLE + "el código tiene más de 30 caracteres."));
            return false;
        }
        if (entrada.codigosInternosOcupados().contains(d.clave())) {
            acumulado.errores.add(aviso(d,
                NO_CREABLE + "ya existe un artículo con el código interno " + d.codigo() + "."));
            return false;
        }
        if (d.descripcion().isEmpty()) {
            acumulado.errores.add(aviso(d, NO_CREABLE + "falta la descripción."));
            return false;
        }
        // Solo los nuevos toman la unidad, el codigo de barras y el rubro de la lista (decision 32).
        UnidadDeLista.ResultadoUnidad unidad = UnidadDeLista.interpretar(Campos.celda(d, entrada.mapeo().columnaUnidad()));
        if (unidad.esError()) {
            acumulado.errores.add(aviso(d, unidad.mensaje()));
            return false;
        }
        CodigoBarrasDeLista.ResultadoBarras barras =
            CodigoBarrasDeLista.interpretar(Campos.celda(d, entrada.mapeo().columnaCodigoBarras()));
        if (barras.esError()) {
            acumulado.errores.add(aviso(d, barras.mensaje()));
            return false;
        }
        if (barras.esValor() && acumulado.barrasEnUso.contains(NormalizadorCodigo.clave(barras.codigo()))) {
            acumulado.errores.add(aviso(d,
                "Ya existe un artículo activo con el código de barras " + barras.codigo() + "."));
            return false;
        }
        String descripcion = d.descripcion();
        if (largo(descripcion) > LARGO_MAXIMO_DESCRIPCION) {
            descripcion = descripcion.substring(0, descripcion.offsetByCodePoints(0, LARGO_MAXIMO_DESCRIPCION));
            acumulado.advertencias.add(
                aviso(d, "La descripción tenía más de 200 caracteres y se cortó a 200."));
        }
        Long rubroId = rubroDeLaFila(d, entrada, acumulado);
        // El precio usa el rubro de la fila o, si no lo trae, el elegido al importar (como al crear el articulo).
        Long rubroDelPrecio = rubroId != null ? rubroId : entrada.rubroElegidoId();
        ContextoPrecio contexto = rubroDelPrecio == null
            ? entrada.contextoSinRubro()
            : entrada.contextoPorRubro().getOrDefault(rubroDelPrecio, entrada.contextoSinRubro());
        BigDecimal precio = precioDelAlta(costo, RecargosDeCosto.NINGUNO, alicuota, moneda, contexto);
        if (barras.esValor()) {
            acumulado.barrasEnUso.add(NormalizadorCodigo.clave(barras.codigo()));
        }
        acumulado.altas.add(new AltaPlaneada(d.numero(), d.codigo(), descripcion, d.precioLista(), costo, d.hoja(),
            moneda, alicuota, ajustes, unidad.esValor() ? unidad.unidad() : Unidad.UNIDAD,
            barras.esValor() ? barras.codigo() : null, rubroId, precio));
        return true;
    }

    /** Las claves de los codigos de barras de los articulos activos, para comparar sin distinguir mayusculas. */
    private static Set<String> ocupadas(EntradaPlan entrada) {
        Set<String> claves = new HashSet<>();
        for (String codigo : entrada.codigosBarrasOcupados()) {
            String clave = NormalizadorCodigo.clave(codigo);
            if (clave != null) {
                claves.add(clave);
            }
        }
        return claves;
    }

    /**
     * El rubro que nombra la fila, buscado entre los de la ferreteria sin distinguir mayusculas ni tildes. Si no existe
     * no se crea: la fila suma una advertencia y el alta usa el rubro elegido al importar (null aca). Nunca se usa el
     * nombre de la hoja.
     */
    private static Long rubroDeLaFila(FilaDeDatos d, EntradaPlan entrada, Acumulado acumulado) {
        Integer columna = entrada.mapeo().columnaRubro();
        String nombre = columna == null ? "" : recortar(d.fila().celda(columna).texto());
        if (nombre.isEmpty()) {
            return null;
        }
        Long rubroId = entrada.rubrosPorNombre().get(NormalizadorCodigo.clave(nombre));
        if (rubroId == null) {
            acumulado.advertencias.add(aviso(d, "El rubro «" + nombre
                + "» no existe: el artículo se crea con el rubro elegido al importar, o sin rubro."));
        }
        return rubroId;
    }

    /**
     * El precio final del alta en pesos con el contexto dado (el de su rubro, o el de los articulos sin rubro). Un alta
     * no trae recargos del costo (CU-13), pero el calculo parte del costo real igual que el resto.
     */
    static BigDecimal precioDelAlta(BigDecimal costo, RecargosDeCosto recargos, AlicuotaIva alicuota, Moneda moneda,
                                    ContextoPrecio ctx) {
        BigDecimal margen = PrecioCalculator.margenEfectivo(null, ctx.margenRubro(), ctx.margenDefecto());
        return PrecioCalculator.precioFinal(costo, recargos, margen, alicuota, ctx.redondeoMultiplo(),
            ctx.cotizacionPara(moneda), null);
    }

    /**
     * Precio final con el contexto del articulo (rubro o sin rubro) y su margen propio si lo tiene, con la regla de
     * {@link Articulo}: con precio manual queda el de hoy; si no, el calculado mas los ajustes (CU-14).
     */
    private static BigDecimal precioNuevo(ArticuloVigente articulo, BigDecimal costoNuevo, AlicuotaIva alicuota,
                                          Moneda moneda, EntradaPlan entrada) {
        ContextoPrecio ctx = contexto(articulo, entrada);
        BigDecimal margen = PrecioCalculator.margenEfectivo(
            articulo.margenPorcentaje(), ctx.margenRubro(), ctx.margenDefecto());
        return articulo.precioCon(costoNuevo, margen, alicuota, ctx.redondeoMultiplo(), ctx.cotizacionPara(moneda));
    }

    /** El contexto de precio del articulo: el de su rubro, o el de los articulos sin rubro. */
    private static ContextoPrecio contexto(ArticuloVigente articulo, EntradaPlan entrada) {
        ContextoPrecio ctx = articulo.rubroId() == null
            ? entrada.contextoSinRubro()
            : entrada.contextoPorRubro().get(articulo.rubroId());
        if (ctx == null) {
            throw new IllegalStateException("Falta el contexto de precio del rubro " + articulo.rubroId());
        }
        return ctx;
    }

    /** Indice por clave del codigo del proveedor; si dos comparten clave gana el primero. */
    private static Map<String, ArticuloVigente> indexar(List<ArticuloVigente> articulos) {
        Map<String, ArticuloVigente> indice = new HashMap<>();
        for (ArticuloVigente articulo : articulos) {
            String clave = NormalizadorCodigo.clave(articulo.codigoProveedor());
            if (clave != null) {
                indice.putIfAbsent(clave, articulo);
            }
        }
        return indice;
    }

    // ------------------------------------------------------------------ duplicados

    /** Las filas por clave, solo las que tienen codigo valido, en el orden del libro. */
    private static Map<String, List<FilaDeDatos>> filasPorClave(List<FilaDeDatos> filas) {
        Map<String, List<FilaDeDatos>> porClave = new LinkedHashMap<>();
        for (FilaDeDatos d : filas) {
            if (errorDeCodigo(d).isEmpty()) {
                porClave.computeIfAbsent(d.clave(), k -> new ArrayList<>()).add(d);
            }
        }
        return porClave;
    }

    /**
     * Donde esta cada aparicion de un codigo repetido: "filas 4 y 9" si es todo de una hoja y "TORNILLOS fila 5 y
     * TUERCAS fila 9" si son de varias (o la hoja misma, sin nombre, como "hoja 2").
     */
    private static String ubicaciones(List<FilaDeDatos> repetidas, List<String> nombresDeHojas) {
        boolean deUnaHoja = repetidas.stream().map(FilaDeDatos::hoja).distinct().count() == 1;
        if (deUnaHoja) {
            return "filas " + listar(repetidas.stream().map(r -> String.valueOf(r.numero())).toList());
        }
        return listar(repetidas.stream().map(r -> nombreDeHoja(r.hoja(), nombresDeHojas) + " fila " + r.numero()).toList());
    }

    private static String nombreDeHoja(int hoja, List<String> nombres) {
        return hoja < nombres.size() ? nombres.get(hoja).strip() : "hoja " + (hoja + 1);
    }

    /** "4 y 9" o "3, 5 y 8". */
    private static String listar(List<String> textos) {
        String ultimo = textos.get(textos.size() - 1);
        if (textos.size() == 1) {
            return ultimo;
        }
        return String.join(", ", textos.subList(0, textos.size() - 1)) + " y " + ultimo;
    }

    // ------------------------------------------------------------------ lectura de la fila

    /** Los datos de una fila ya extraidos por el mapeo; {@code precioLista} es null si no se pudo leer. */
    private record FilaDeDatos(
        int hoja, int numero, String codigo, String clave, String descripcion, BigDecimal precioLista, FilaLeida fila,
        boolean hojaConocida) {

        /** La hoja para los avisos: null si la entrada no conoce los nombres de las hojas (un archivo de una sola hoja). */
        Integer avisoHoja() {
            return hojaConocida ? hoja : null;
        }
    }

    /** El libro como lo leyo el lector; el encabezado de referencia se suma si no esta entre las filas. */
    private static LecturaLista libroLeido(EntradaPlan entrada) {
        FilaLeida encabezado = entrada.encabezado();
        boolean yaEsta = encabezado == null || entrada.filas().stream()
            .anyMatch(f -> f.hoja() == encabezado.hoja() && f.numero() == encabezado.numero());
        if (yaEsta) {
            return new LecturaLista(entrada.hojas(), entrada.filas());
        }
        List<FilaLeida> filas = new ArrayList<>(entrada.filas());
        filas.add(encabezado);
        return new LecturaLista(entrada.hojas(), filas);
    }

    /** Las filas de datos de la zona, sin las que no tienen codigo ni costo. */
    private static List<FilaDeDatos> extraerFilas(List<FilaLeida> deLaZona, MapeoLista mapeo, boolean hojaConocida) {
        List<FilaDeDatos> filas = new ArrayList<>();
        for (FilaLeida fila : deLaZona) {
            String codigo = recortar(fila.celda(mapeo.columnaCodigo()).texto());
            Celda celdaCosto = fila.celda(mapeo.columnaCosto());
            if (codigo.isEmpty() && celdaCosto.vacia()) {
                continue;
            }
            String descripcion = mapeo.columnaDescripcion() == null
                ? "" : recortar(fila.celda(mapeo.columnaDescripcion()).texto());
            BigDecimal precioLista = ParserImporte.parsear(celdaCosto).orElse(null);
            filas.add(new FilaDeDatos(fila.hoja(), fila.numero(), codigo, NormalizadorCodigo.clave(codigo), descripcion,
                precioLista, fila, hojaConocida));
        }
        return filas;
    }

    /** Trim que tambien saca los espacios no separables, como {@link NormalizadorCodigo}. */
    private static String recortar(String texto) {
        return texto.replace(' ', ' ').replace(' ', ' ').strip();
    }

    private static int largo(String texto) {
        return texto.codePointCount(0, texto.length());
    }

    // ------------------------------------------------------------------ errores

    private static Optional<String> errorDeCodigo(FilaDeDatos d) {
        if (d.codigo().isEmpty()) {
            return Optional.of("Falta el código del proveedor.");
        }
        if (largo(d.codigo()) > LARGO_MAXIMO_CODIGO_PROVEEDOR) {
            return Optional.of("El código tiene más de 40 caracteres.");
        }
        return Optional.empty();
    }

    private static Optional<String> errorDeCosto(FilaDeDatos d) {
        BigDecimal precio = d.precioLista();
        if (precio == null) {
            return Optional.of("El costo está vacío o no es un número.");
        }
        if (precio.signum() < 0) {
            return Optional.of("El costo no puede ser negativo.");
        }
        if (digitosEnteros(precio) > MAXIMO_DIGITOS_ENTEROS) {
            return Optional.of("El costo es demasiado grande.");
        }
        return Optional.empty();
    }

    /** Cantidad de digitos de la parte entera; un valor menor que 1 tiene 0. */
    private static int digitosEnteros(BigDecimal valor) {
        return Math.max(0, valor.precision() - valor.scale());
    }

    /**
     * Lo que dice la fila de la moneda, la alicuota y los ajustes, ya interpretado. Si algun campo da error, queda el
     * primer mensaje en {@code error} (en el orden moneda, IVA, ajustes) y el resto no se mira.
     *
     * @param moneda             null si la lista no la dice (el existente conserva la suya, el nuevo es ARS)
     * @param alicuota           null si la fila no trae datos de IVA (el existente conserva la suya, el nuevo es IVA_21)
     * @param ajustes            el estado de los tres ajustes por posicion
     * @param advertenciaDeIva   la advertencia del IVA derivado del par sin IVA / con IVA; null si no hay
     */
    private record Campos(
            Moneda moneda,
            AlicuotaIva alicuota,
            AjusteDeFila.ResultadoAjuste[] ajustes,
            String advertenciaDeIva,
            String error) {

        static Campos leer(FilaDeDatos d, MapeoLista mapeo) {
            MonedaDeLista.ResultadoMoneda moneda = MonedaDeLista.interpretar(
                celda(d, mapeo.columnaMoneda()), mapeo.columnaMoneda() != null, mapeo.monedaFija());
            if (moneda.esError()) {
                return conError(moneda.mensaje());
            }
            // Sin dato de moneda, un costo «US$ 4,00» entraria en pesos: no se adivina la moneda.
            if (moneda.esSinDato() && ParserImporte.enDolares(d.fila().celda(mapeo.columnaCosto()))) {
                return conError(MENSAJE_DOLARES_SIN_MONEDA);
            }
            AlicuotaDeFila.ResultadoAlicuota iva = AlicuotaDeFila.interpretar(
                celda(d, mapeo.columnaIva()), celda(d, mapeo.columnaSinIva()), celda(d, mapeo.columnaConIva()));
            if (iva.esError()) {
                return conError(iva.mensaje());
            }
            Integer[] columnas = {mapeo.columnaAjuste1(), mapeo.columnaAjuste2(), mapeo.columnaAjuste3()};
            AjusteDeFila.ResultadoAjuste[] ajustes = new AjusteDeFila.ResultadoAjuste[columnas.length];
            for (int i = 0; i < columnas.length; i++) {
                ajustes[i] = AjusteDeFila.interpretar(i + 1, celda(d, columnas[i]));
                if (ajustes[i].esError()) {
                    return conError(ajustes[i].mensaje());
                }
            }
            return new Campos(moneda.esValor() ? moneda.moneda() : null, iva.esValor() ? iva.alicuota() : null, ajustes,
                iva.advertencia(), null);
        }

        private static Campos conError(String mensaje) {
            return new Campos(null, null, null, null, mensaje);
        }

        /** La celda de la fila en esa columna, o null si la columna no esta mapeada. */
        private static Celda celda(FilaDeDatos d, Integer columna) {
            return columna == null ? null : d.fila().celda(columna);
        }

        /** Los ajustes que quedan: el valor de la fila, ninguno si la celda esta vacia, o el que ya tenia el articulo. */
        Ajustes ajustesSobre(Ajustes actuales) {
            return new Ajustes(
                resolver(ajustes[0], actuales.a1()), resolver(ajustes[1], actuales.a2()),
                resolver(ajustes[2], actuales.a3()));
        }

        private static BigDecimal resolver(AjusteDeFila.ResultadoAjuste ajuste, BigDecimal actual) {
            if (ajuste.esValor()) {
                return ajuste.valor();
            }
            return ajuste.esBorrar() ? null : actual;
        }
    }

    private static AvisoDeFila aviso(FilaDeDatos d, String mensaje) {
        return new AvisoDeFila(d.avisoHoja(), d.numero(), d.codigo().isEmpty() ? null : d.codigo(), mensaje);
    }

    private static BigDecimal costoCalculado(BigDecimal precioLista, AlicuotaIva alicuota, Ajustes ajustes,
                                             EntradaPlan entrada) {
        return CostoDeLista.costo(precioLista, alicuota, entrada.listaIncluyeIva(), entrada.bonificaciones(), ajustes);
    }
}
