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
 * y la verificacion de deriva del aplicar depende de eso. No mira {@code crearNuevos} ni
 * {@code rubroId}: la clasificacion es la misma con o sin la opcion de crear.
 *
 * <p>Orden de validacion de cada fila (se informa solo el primer error): codigo, codigo repetido,
 * costo, costo calculado en cero y, para las que no coinciden con ningun articulo, si se puede crear.
 * La fila de un articulo existente que esta en dolares es siempre un error: por ahora la lista no lo actualiza.
 */
public final class PlanificadorImportacion {

    /** Una variacion de costo mayor a este porcentaje (hacia arriba o abajo) genera una advertencia. */
    public static final BigDecimal UMBRAL_VARIACION_PORCENTAJE = VariacionFuerte.UMBRAL_PORCENTAJE;

    private static final int LARGO_MAXIMO_CODIGO_PROVEEDOR = 40;
    private static final int LARGO_MAXIMO_CODIGO_INTERNO = 30;
    private static final int LARGO_MAXIMO_DESCRIPCION = 200;
    private static final String NO_CREABLE = "No se puede crear como artículo nuevo: ";
    private static final int MAXIMO_DIGITOS_ENTEROS = 10;
    /** Guarda hasta que la importacion maneje la moneda de cada articulo (corte 2). */
    private static final String MENSAJE_EN_DOLARES =
        "El artículo está en dólares: por ahora su costo no se actualiza desde una lista. Cargalo a mano.";
    private static final String MENSAJE_COSTO_CERO =
        "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.";

    private PlanificadorImportacion() {
    }

    public static PlanImportacion planificar(EntradaPlan entrada) {
        List<FilaDeDatos> filas = extraerFilas(entrada);
        Map<String, List<Integer>> filasPorClave = filasPorClave(filas);
        Map<String, ArticuloVigente> articulosPorClave = indexar(entrada.articulosDelProveedor());

        Acumulado acumulado = new Acumulado();
        Set<String> presentes = new HashSet<>();

        for (FilaDeDatos d : filas) {
            Optional<String> errorDeCodigo = errorDeCodigo(d);
            if (errorDeCodigo.isPresent()) {
                acumulado.errores.add(aviso(d, errorDeCodigo.get()));
                continue;
            }
            presentes.add(d.clave());

            List<Integer> repetidas = filasPorClave.get(d.clave());
            if (repetidas.size() > 1) {
                acumulado.errores.add(
                    aviso(d, "El código aparece repetido en la lista (filas " + listar(repetidas) + ")."));
                continue;
            }
            Optional<String> errorDeCosto = errorDeCosto(d);
            if (errorDeCosto.isPresent()) {
                acumulado.errores.add(aviso(d, errorDeCosto.get()));
                continue;
            }
            ArticuloVigente articulo = articulosPorClave.get(d.clave());
            if (articulo != null && articulo.moneda() == Moneda.USD) {
                acumulado.errores.add(aviso(d, MENSAJE_EN_DOLARES));
            } else if (articulo != null) {
                clasificar(d, articulo, entrada, acumulado);
            } else {
                planificarNuevo(d, entrada, acumulado);
            }
        }

        List<ArticuloVigente> noEstan = entrada.articulosDelProveedor().stream()
            .filter(ArticuloVigente::activo)
            .filter(a -> {
                String clave = NormalizadorCodigo.clave(a.codigoProveedor());
                return clave == null || !presentes.contains(clave);
            })
            .toList();

        acumulado.errores.sort(Comparator.comparing(AvisoDeFila::fila));
        acumulado.advertencias.sort(Comparator.comparing(AvisoDeFila::fila));
        // Las de encabezado no son de ninguna fila: van primero.
        acumulado.advertencias.addAll(0, advertenciasDeEncabezado(entrada));
        ConteosImportacion conteos = new ConteosImportacion(
            (int) acumulado.cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) > 0).count(),
            (int) acumulado.cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) < 0).count(),
            acumulado.altas.size(), acumulado.sinCambio.size(), noEstan.size(), acumulado.errores.size());
        return new PlanImportacion(conteos, acumulado.cambios, acumulado.altas, acumulado.sinCambio, acumulado.errores,
            acumulado.advertencias, noEstan);
    }

    /**
     * Los codigos (con trim) de las filas de datos que podrian ser codigo interno de un articulo
     * nuevo, es decir de 30 caracteres o menos, sin repetir segun la clave. El servicio los usa para
     * preguntar de una vez cuales ya estan ocupados.
     */
    public static List<String> codigosCandidatos(List<FilaLeida> filas, MapeoLista mapeo) {
        Map<String, String> porClave = new LinkedHashMap<>();
        for (FilaLeida fila : filas) {
            if (fila.numero() < mapeo.filaDesde()) {
                continue;
            }
            String codigo = recortar(fila.celda(mapeo.columnaCodigo()).texto());
            if (!codigo.isEmpty() && largo(codigo) <= LARGO_MAXIMO_CODIGO_INTERNO) {
                porClave.putIfAbsent(NormalizadorCodigo.clave(codigo), codigo);
            }
        }
        return new ArrayList<>(porClave.values());
    }

    private static List<AvisoDeFila> advertenciasDeEncabezado(EntradaPlan entrada) {
        return entrada.mapeo().conEncabezados(entrada.encabezado()).advertenciasContra(entrada.mapeoGuardado())
            .stream()
            .map(mensaje -> new AvisoDeFila(null, null, mensaje))
            .toList();
    }

    /** Las listas que se van llenando fila a fila. */
    private static final class Acumulado {
        private final List<AvisoDeFila> errores = new ArrayList<>();
        private final List<AvisoDeFila> advertencias = new ArrayList<>();
        private final List<CambioPlaneado> cambios = new ArrayList<>();
        private final List<PrecioListaPlaneado> sinCambio = new ArrayList<>();
        private final List<AltaPlaneada> altas = new ArrayList<>();
    }

    // ------------------------------------------------------------------ coincidencia

    /**
     * Una fila que coincide con un articulo: sube, baja, sin cambio, o error si el costo calculado es
     * 0. El costo se calcula con la alicuota de ESE articulo; el precio nuevo es solo para mostrar.
     */
    private static void clasificar(FilaDeDatos d, ArticuloVigente articulo, EntradaPlan entrada,
                                   Acumulado acumulado) {
        BigDecimal costoNuevo = costoCalculado(d.precioLista(), articulo.alicuotaIva(), entrada);
        if (costoNuevo.signum() == 0) {
            acumulado.errores.add(aviso(d, MENSAJE_COSTO_CERO));
            return;
        }
        int comparacion = costoNuevo.compareTo(articulo.costo());
        if (comparacion == 0) {
            acumulado.sinCambio.add(new PrecioListaPlaneado(articulo.id(), d.precioLista()));
            return;
        }
        Optional<String> variacion = VariacionFuerte.mensaje(Sujeto.COSTO, articulo.costo(), costoNuevo);
        variacion.ifPresent(mensaje -> acumulado.advertencias.add(aviso(d, mensaje)));
        acumulado.cambios.add(new CambioPlaneado(d.numero(), articulo.id(), d.codigo(), articulo.descripcion(),
            d.precioLista(), articulo.costo(), costoNuevo, articulo.precioFinal(),
            precioNuevo(articulo, costoNuevo, entrada), articulo.activo(), variacion.isPresent()));
    }

    // ------------------------------------------------------------------ nuevos

    /**
     * Una fila sin coincidencia: alta si se puede crear, error si no. El costo se calcula con IVA_21
     * (la alicuota de los articulos nuevos) y su error tiene prioridad sobre "no creable".
     */
    private static void planificarNuevo(FilaDeDatos d, EntradaPlan entrada, Acumulado acumulado) {
        BigDecimal costo = costoCalculado(d.precioLista(), AlicuotaIva.IVA_21, entrada);
        if (costo.signum() == 0) {
            acumulado.errores.add(aviso(d, MENSAJE_COSTO_CERO));
            return;
        }
        if (largo(d.codigo()) > LARGO_MAXIMO_CODIGO_INTERNO) {
            acumulado.errores.add(aviso(d, NO_CREABLE + "el código tiene más de 30 caracteres."));
            return;
        }
        if (entrada.codigosInternosOcupados().contains(d.clave())) {
            acumulado.errores.add(aviso(d,
                NO_CREABLE + "ya existe un artículo con el código interno " + d.codigo() + "."));
            return;
        }
        if (d.descripcion().isEmpty()) {
            acumulado.errores.add(aviso(d, NO_CREABLE + "falta la descripción."));
            return;
        }
        String descripcion = d.descripcion();
        if (largo(descripcion) > LARGO_MAXIMO_DESCRIPCION) {
            descripcion = descripcion.substring(0, descripcion.offsetByCodePoints(0, LARGO_MAXIMO_DESCRIPCION));
            acumulado.advertencias.add(
                aviso(d, "La descripción tenía más de 200 caracteres y se cortó a 200."));
        }
        acumulado.altas.add(new AltaPlaneada(d.numero(), d.codigo(), descripcion, d.precioLista(), costo));
    }

    /** Precio final con el contexto del articulo (rubro o sin rubro) y su margen propio si lo tiene. */
    private static BigDecimal precioNuevo(ArticuloVigente articulo, BigDecimal costoNuevo, EntradaPlan entrada) {
        ContextoPrecio ctx = articulo.rubroId() == null
            ? entrada.contextoSinRubro()
            : entrada.contextoPorRubro().get(articulo.rubroId());
        if (ctx == null) {
            throw new IllegalStateException("Falta el contexto de precio del rubro " + articulo.rubroId());
        }
        BigDecimal margen = PrecioCalculator.margenEfectivo(
            articulo.margenPorcentaje(), ctx.margenRubro(), ctx.margenDefecto());
        return PrecioCalculator.precioFinal(costoNuevo, margen, articulo.alicuotaIva(), ctx.redondeoMultiplo());
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

    /** Numeros de fila por clave, solo para filas con codigo valido. */
    private static Map<String, List<Integer>> filasPorClave(List<FilaDeDatos> filas) {
        Map<String, List<Integer>> porClave = new LinkedHashMap<>();
        for (FilaDeDatos d : filas) {
            if (errorDeCodigo(d).isEmpty()) {
                porClave.computeIfAbsent(d.clave(), k -> new ArrayList<>()).add(d.numero());
            }
        }
        return porClave;
    }

    /** "4 y 9" o "3, 5 y 8". */
    private static String listar(List<Integer> numeros) {
        String ultimo = String.valueOf(numeros.get(numeros.size() - 1));
        if (numeros.size() == 1) {
            return ultimo;
        }
        StringBuilder anteriores = new StringBuilder();
        for (int i = 0; i < numeros.size() - 1; i++) {
            if (i > 0) {
                anteriores.append(", ");
            }
            anteriores.append(numeros.get(i));
        }
        return anteriores + " y " + ultimo;
    }

    // ------------------------------------------------------------------ lectura de la fila

    /** Los datos de una fila ya extraidos por el mapeo; {@code precioLista} es null si no se pudo leer. */
    private record FilaDeDatos(int numero, String codigo, String clave, String descripcion, BigDecimal precioLista) {}

    /** Las filas de datos: sin las anteriores a filaDesde y sin las que no tienen codigo ni costo. */
    private static List<FilaDeDatos> extraerFilas(EntradaPlan entrada) {
        MapeoLista mapeo = entrada.mapeo();
        List<FilaDeDatos> filas = new ArrayList<>();
        for (FilaLeida fila : entrada.filas()) {
            if (fila.numero() < mapeo.filaDesde()) {
                continue;
            }
            String codigo = recortar(fila.celda(mapeo.columnaCodigo()).texto());
            Celda celdaCosto = fila.celda(mapeo.columnaCosto());
            if (codigo.isEmpty() && celdaCosto.vacia()) {
                continue;
            }
            String descripcion = mapeo.columnaDescripcion() == null
                ? "" : recortar(fila.celda(mapeo.columnaDescripcion()).texto());
            BigDecimal precioLista = ParserImporte.parsear(celdaCosto).orElse(null);
            filas.add(new FilaDeDatos(
                fila.numero(), codigo, NormalizadorCodigo.clave(codigo), descripcion, precioLista));
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

    private static AvisoDeFila aviso(FilaDeDatos d, String mensaje) {
        return new AvisoDeFila(d.numero(), d.codigo().isEmpty() ? null : d.codigo(), mensaje);
    }

    private static BigDecimal costoCalculado(BigDecimal precioLista, AlicuotaIva alicuota, EntradaPlan entrada) {
        return CostoDeLista.costo(precioLista, alicuota, entrada.listaIncluyeIva(), entrada.bonificaciones());
    }
}
