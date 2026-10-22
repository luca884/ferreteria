package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Planificador de importacion: logica pura, con filas armadas a mano. Mapeo base: codigo en la
 * columna 0, descripcion en la 1, costo en la 2, datos desde la fila 2 (la 1 es el encabezado).
 */
class PlanificadorImportacionTest {

    private static final MapeoLista MAPEO = new MapeoLista(0, 2, 0, 1, 2, null);

    private static Celda celda(String texto) {
        return texto == null ? Celda.VACIA : new Celda(texto, null);
    }

    private static FilaLeida fila(int numero, String codigo, String descripcion, String costo) {
        return new FilaLeida(numero, List.of(celda(codigo), celda(descripcion), celda(costo)));
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /**
     * Una fila con mas columnas que las de siempre: codigo (0), descripcion (1), costo (2) y despues, en este orden,
     * moneda (3), precio sin IVA (4), precio con IVA (5), % IVA (6), ajustes 1 a 3 (7 a 9), codigo de barras (10),
     * unidad (11) y rubro (12). Lo que no se pasa queda vacio.
     */
    private static FilaLeida filaDe(int numero, String... textos) {
        return filaEnHoja(0, numero, textos);
    }

    private static FilaLeida filaEnHoja(int hoja, int numero, String... textos) {
        List<Celda> celdas = new ArrayList<>();
        for (String texto : textos) {
            celdas.add(celda(texto));
        }
        return new FilaLeida(hoja, numero, celdas);
    }

    /** Mapeo con las columnas de {@link #filaDe}, de a una. */
    private static final class M {
        private Integer moneda;
        private Integer sinIva;
        private Integer conIva;
        private Integer iva;
        private Integer ajuste1;
        private Integer ajuste2;
        private Integer ajuste3;
        private Integer barras;
        private Integer unidad;
        private Integer rubro;
        private Moneda monedaFija;
        private List<Integer> hojas;
        private int filaDesde = 2;

        static M mapeo() {
            return new M();
        }

        M moneda() {
            moneda = 3;
            return this;
        }

        M monedaFija(Moneda valor) {
            monedaFija = valor;
            return this;
        }

        M parDeIva() {
            sinIva = 4;
            conIva = 5;
            return this;
        }

        M iva() {
            iva = 6;
            return this;
        }

        M ajuste1() {
            ajuste1 = 7;
            return this;
        }

        M ajuste2() {
            ajuste2 = 8;
            return this;
        }

        M ajuste3() {
            ajuste3 = 9;
            return this;
        }

        M barras() {
            barras = 10;
            return this;
        }

        M unidad() {
            unidad = 11;
            return this;
        }

        M rubro() {
            rubro = 12;
            return this;
        }

        M hojas(Integer... indices) {
            hojas = List.of(indices);
            return this;
        }

        M filaDesde(int valor) {
            filaDesde = valor;
            return this;
        }

        MapeoLista build() {
            return new MapeoLista(0, filaDesde, 0, 1, 2, null, hojas, moneda, monedaFija, sinIva, conIva, iva,
                ajuste1, ajuste2, ajuste3, barras, unidad, rubro);
        }
    }

    /**
     * Articulo vigente de prueba: IVA_21, activo, sin rubro ni margen propio. Si no se le da {@link #precio}, queda con
     * el precio que da su costo con el contexto del escenario (al dia): asi no es una "correccion" por precio viejo.
     */
    private static final class Art {
        private final long id;
        private final String codigoProveedor;
        private final String costo;
        private AlicuotaIva iva = AlicuotaIva.IVA_21;
        private boolean activo = true;
        private String margen;
        private Long rubroId;
        private String precioFinal;
        private Moneda moneda = Moneda.ARS;
        private Ajustes ajustes = Ajustes.NINGUNO;
        private boolean precioManual;
        private AjustesDePrecio ajustesPrecio = AjustesDePrecio.NINGUNO;

        private Art(long id, String codigoProveedor, String costo) {
            this.id = id;
            this.codigoProveedor = codigoProveedor;
            this.costo = costo;
        }

        static Art art(long id, String codigoProveedor, String costo) {
            return new Art(id, codigoProveedor, costo);
        }

        Art iva(AlicuotaIva valor) {
            this.iva = valor;
            return this;
        }

        Art inactivo() {
            this.activo = false;
            return this;
        }

        Art margen(String valor) {
            this.margen = valor;
            return this;
        }

        Art rubro(long valor) {
            this.rubroId = valor;
            return this;
        }

        Art precio(String valor) {
            this.precioFinal = valor;
            return this;
        }

        Art enDolares() {
            this.moneda = Moneda.USD;
            return this;
        }

        Art ajustes(String a1, String a2, String a3) {
            this.ajustes = new Ajustes(a1 == null ? null : bd(a1), a2 == null ? null : bd(a2), a3 == null ? null : bd(a3));
            return this;
        }

        /** Con precio manual (CU-14): conviene darle tambien {@link #precio}. */
        Art manual() {
            this.precioManual = true;
            return this;
        }

        /** Ajustes sobre el precio final (CU-14). */
        Art ajustesPrecio(AjustePrecio... valores) {
            this.ajustesPrecio = new AjustesDePrecio(List.of(valores));
            return this;
        }

        /** El articulo; sin precio explicito, el que corresponde a su costo con el contexto dado (el de su rubro). */
        ArticuloVigente vigente(Map<Long, ContextoPrecio> contextoPorRubro, ContextoPrecio contextoSinRubro) {
            BigDecimal margenPropio = margen == null ? null : bd(margen);
            return new ArticuloVigente(id, "INT-" + id, codigoProveedor, "Articulo " + id, bd(costo),
                precioFinal != null ? bd(precioFinal) : alDia(margenPropio, contextoPorRubro, contextoSinRubro),
                margenPropio, rubroId, iva, activo, moneda, ajustes, precioManual, ajustesPrecio);
        }

        private BigDecimal alDia(BigDecimal margenPropio, Map<Long, ContextoPrecio> contextoPorRubro,
                                 ContextoPrecio contextoSinRubro) {
            ContextoPrecio ctx = rubroId == null ? contextoSinRubro : contextoPorRubro.get(rubroId);
            // Sin contexto del rubro o sin dolar no hay precio que calcular: el planificador falla o da error por su cuenta.
            if (ctx == null || (moneda == Moneda.USD && ctx.cotizacionUsd() == null)) {
                return bd("0.00");
            }
            return PrecioCalculator.precioFinal(bd(costo),
                PrecioCalculator.margenEfectivo(margenPropio, ctx.margenRubro(), ctx.margenDefecto()), iva,
                ctx.redondeoMultiplo(), ctx.cotizacionPara(moneda), ajustesPrecio);
        }
    }

    /** Arma una EntradaPlan con valores por defecto razonables y permite cambiarlos. */
    private static final class Escenario {
        private final List<FilaLeida> filas = new ArrayList<>();
        private final List<Art> articulos = new ArrayList<>();
        private final Map<Long, String> margenesDeRubro = new HashMap<>();
        private final Set<String> codigosInternosOcupados = new HashSet<>();
        private final Map<String, Long> rubrosPorNombre = new HashMap<>();
        private final Set<String> barrasOcupadas = new HashSet<>();
        private List<String> hojas = List.of();
        private Long rubroElegido;
        private BigDecimal cotizacion;
        private boolean cotizacionActiva = true;
        private MapeoLista mapeo = MAPEO;
        private boolean incluyeIva;
        private Bonificaciones bonificaciones = Bonificaciones.NINGUNA;
        private MapeoLista mapeoGuardado;
        private FilaLeida encabezado;

        Escenario filas(FilaLeida... valores) {
            filas.addAll(List.of(valores));
            return this;
        }

        Escenario articulos(Art... valores) {
            articulos.addAll(List.of(valores));
            return this;
        }

        Escenario mapeo(MapeoLista valor) {
            this.mapeo = valor;
            return this;
        }

        Escenario incluyeIva() {
            this.incluyeIva = true;
            return this;
        }

        Escenario bonificaciones(String b1, String b2, String b3) {
            this.bonificaciones = new Bonificaciones(
                b1 == null ? null : bd(b1), b2 == null ? null : bd(b2), b3 == null ? null : bd(b3));
            return this;
        }

        Escenario contextoRubro(long rubroId, String margenRubro) {
            margenesDeRubro.put(rubroId, margenRubro);
            return this;
        }

        Escenario ocupados(String... claves) {
            codigosInternosOcupados.addAll(List.of(claves));
            return this;
        }

        Escenario guardado(MapeoLista valor, FilaLeida filaEncabezado) {
            this.mapeoGuardado = valor;
            this.encabezado = filaEncabezado;
            return this;
        }

        /** El dolar vigente de la ferreteria: sin llamarlo no hay ninguna cotizacion. */
        Escenario dolar(String valor) {
            this.cotizacion = bd(valor);
            return this;
        }

        Escenario cotizacionApagada() {
            this.cotizacionActiva = false;
            return this;
        }

        /** Nombres de las hojas del libro: con ellos los avisos llevan la hoja. */
        Escenario hojas(String... nombres) {
            this.hojas = List.of(nombres);
            return this;
        }

        Escenario rubroPorNombre(String nombre, long rubroId) {
            rubrosPorNombre.put(NormalizadorCodigo.clave(nombre), rubroId);
            return this;
        }

        /** El rubro que se elegiria al importar: respaldo de los nuevos que no nombran rubro en la fila. */
        Escenario rubroElegido(long rubroId) {
            this.rubroElegido = rubroId;
            return this;
        }

        Escenario barrasOcupadas(String... codigos) {
            barrasOcupadas.addAll(List.of(codigos));
            return this;
        }

        PlanImportacion planificar() {
            Map<Long, ContextoPrecio> contextoPorRubro = new HashMap<>();
            margenesDeRubro.forEach((id, margen) ->
                contextoPorRubro.put(id, new ContextoPrecio(bd(margen), bd("30.00"), bd("10"), cotizacion)));
            ContextoPrecio contextoSinRubro = new ContextoPrecio(null, bd("30.00"), bd("10"), cotizacion);
            List<ArticuloVigente> vigentes = articulos.stream()
                .map(art -> art.vigente(contextoPorRubro, contextoSinRubro)).toList();
            return PlanificadorImportacion.planificar(new EntradaPlan(
                filas, mapeo, incluyeIva, bonificaciones, vigentes, contextoPorRubro, contextoSinRubro,
                codigosInternosOcupados, mapeoGuardado, encabezado, hojas, cotizacionActiva, rubrosPorNombre,
                barrasOcupadas, rubroElegido));
        }
    }

    private static Escenario escenario() {
        return new Escenario();
    }

    private static List<String> mensajes(List<AvisoDeFila> avisos) {
        return avisos.stream().map(AvisoDeFila::mensaje).toList();
    }

    // ------------------------------------------------------------------ moneda por fila (T-125)

    @Nested
    @DisplayName("T-125: moneda por fila, sin la guarda de dolares")
    class MonedaPorFila {

        private static final String COSTO_USD_12 = "12";

        @Test
        @DisplayName("un articulo en USD con costo 10 y la fila USD 12 sube y su precio es 18.880 (12 x 1.000 x 1,30 x 1,21 = 18.876)")
        void articuloEnDolaresSube() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "USD-1", "10").enDolares().precio("15730.00"))
                .filas(filaDe(2, "USD-1", "x", COSTO_USD_12, "USD"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
            CambioPlaneado cambio = plan.cambios().get(0);
            assertThat(cambio.costoAnterior()).isEqualByComparingTo("10");
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("12.00");
            assertThat(cambio.precioAnterior()).isEqualByComparingTo("15730");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("18880");
            assertThat(cambio.monedaAnterior()).isEqualTo(Moneda.USD);
            assertThat(cambio.monedaNueva()).isEqualTo(Moneda.USD);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("la fila USD 10 de un articulo en USD con costo 10 es sin cambio y no es un error")
        void articuloEnDolaresSinCambio() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "USD-1", "10").enDolares().precio("15730.00"))
                .filas(filaDe(2, "USD-1", "x", "10", "USD"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).extracting(PrecioListaPlaneado::articuloId).containsExactly(1L);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 1, 0, 0));
        }

        @Test
        @DisplayName("un articulo en pesos con costo 1.000 y la fila USD 10 cambia de moneda: costo 10, precio 15.730, ni sube ni baja")
        void deArsAUsd() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "1000").precio("1580.00"))
                .filas(filaDe(2, "A-1", "x", "10", "USD"))
                .planificar();

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.cambiosDeMoneda()).hasSize(1);
            CambioPlaneado cambio = plan.cambiosDeMoneda().get(0);
            assertThat(cambio.monedaAnterior()).isEqualTo(Moneda.ARS);
            assertThat(cambio.monedaNueva()).isEqualTo(Moneda.USD);
            assertThat(cambio.costoAnterior()).isEqualByComparingTo("1000");
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("10.00");
            assertThat(cambio.precioAnterior()).isEqualByComparingTo("1580");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("15730");
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 0, 1, 0, 0));
        }

        @Test
        @DisplayName("un articulo en USD con costo 10 y la fila ARG 12.000 pasa a pesos: costo 12.000 y precio 18.880")
        void deUsdAArs() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "10").enDolares().precio("15730.00"))
                .filas(filaDe(2, "A-1", "x", "12000", "ARG"))
                .planificar();

            assertThat(plan.cambiosDeMoneda()).hasSize(1);
            CambioPlaneado cambio = plan.cambiosDeMoneda().get(0);
            assertThat(cambio.monedaAnterior()).isEqualTo(Moneda.USD);
            assertThat(cambio.monedaNueva()).isEqualTo(Moneda.ARS);
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("12000.00");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("18880");
        }

        @Test
        @DisplayName("100 pesos contra 100 USD no se comparan: cambian de moneda = 1 y suben = 0")
        void cienPesosContraCienDolares() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "100", "USD"))
                .planificar();

            assertThat(plan.conteos().cambianMoneda()).isEqualTo(1);
            assertThat(plan.conteos().suben()).isZero();
            assertThat(plan.conteos().bajan()).isZero();
            assertThat(plan.conteos().sinCambio()).isZero();
        }

        @Test
        @DisplayName("con la misma moneda se compara como siempre: sube, baja y sin cambio, en pesos con la columna mapeada")
        void mismaMonedaSeCompara() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"), Art.art(3, "A-3", "100"))
                .filas(filaDe(2, "A-1", "x", "120", "ARG"), filaDe(3, "A-2", "x", "80", "ARS"),
                    filaDe(4, "A-3", "x", "100", "ARG"))
                .planificar();

            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 1, 0, 1, 0, 0));
            assertThat(plan.cambiosDeMoneda()).isEmpty();
        }

        @Test
        @DisplayName("sin columna de moneda el articulo existente conserva la suya: un USD sigue en USD")
        void sinColumnaConservaLaMoneda() {
            PlanImportacion plan = escenario().dolar("1000")
                .articulos(Art.art(1, "USD-1", "10").enDolares().precio("15730.00"), Art.art(2, "ARS-1", "50"))
                .filas(fila(2, "USD-1", "x", "12"), fila(3, "ARS-1", "x", "121"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambiosDeMoneda()).isEmpty();
            assertThat(plan.cambios()).extracting(CambioPlaneado::monedaNueva).containsExactly(Moneda.USD, Moneda.ARS);
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("18880");
        }

        @Test
        @DisplayName("un articulo en USD que no esta en la lista cuenta como 'no esta', igual que uno en pesos")
        void noEstaEnLaLista() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "USD-1", "10").enDolares())
                .filas(fila(2, "OTRO", "x", "121"))
                .planificar();

            assertThat(plan.noEstan()).extracting(ArticuloVigente::id).containsExactly(1L);
        }

        @Test
        @DisplayName("una moneda que no se reconoce o vacia es un error de esa fila y las demas siguen")
        void monedaConError() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"), Art.art(3, "A-3", "100"))
                .filas(filaDe(2, "A-1", "x", "120", "EUR"), filaDe(3, "A-2", "x", "120", ""),
                    filaDe(4, "A-3", "x", "120", "ARG"))
                .planificar();

            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(2, 3);
            assertThat(mensajes(plan.errores())).containsExactly("Moneda no reconocida: «EUR»", "Falta la moneda");
            assertThat(plan.cambios()).extracting(CambioPlaneado::fila).containsExactly(4);
        }

        @Test
        @DisplayName("con moneda fija USD todas las filas son dolares")
        void monedaFija() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().monedaFija(Moneda.USD).build())
                .articulos(Art.art(1, "A-1", "1000").precio("1580.00"))
                .filas(fila(2, "A-1", "x", "10"))
                .planificar();

            assertThat(plan.cambiosDeMoneda()).extracting(CambioPlaneado::monedaNueva).containsExactly(Moneda.USD);
        }
    }

    // ------------------------------------------------------------------ cotizacion por fila (T-126)

    @Nested
    @DisplayName("T-126: reglas de cotizacion por fila")
    class CotizacionPorFila {

        private static final String APAGADA = "Activá la cotización para cargar artículos en dólares";
        private static final String SIN_COTIZACION = "Todavía no hay cotización del dólar. Probá en unos minutos";

        @Test
        @DisplayName("cotizacion apagada y fila USD de un articulo nuevo: error en esa fila y las demas son normales")
        void apagadaYNuevoEnDolares() {
            PlanImportacion plan = escenario().dolar("1000").cotizacionApagada().mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "N-USD", "Nuevo", "10", "USD"), filaDe(3, "N-ARS", "Nuevo", "10", "ARG"),
                    filaDe(4, "A-1", "x", "120", "ARG"))
                .planificar();

            assertThat(plan.errores()).containsExactly(new AvisoDeFila(2, "N-USD", APAGADA));
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-ARS");
            assertThat(plan.cambios()).extracting(CambioPlaneado::fila).containsExactly(4);
        }

        @Test
        @DisplayName("cotizacion apagada y un articulo en pesos que pasaria a USD: el mismo error, y no cambia de moneda")
        void apagadaYDeArsAUsd() {
            PlanImportacion plan = escenario().dolar("1000").cotizacionApagada().mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "1000"))
                .filas(filaDe(2, "A-1", "x", "10", "USD"))
                .planificar();

            assertThat(plan.errores()).containsExactly(new AvisoDeFila(2, "A-1", APAGADA));
            assertThat(plan.cambiosDeMoneda()).isEmpty();
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 1));
        }

        @Test
        @DisplayName("cotizacion apagada y un articulo que ya es USD con la fila USD 12: se actualiza con el dolar vigente")
        void apagadaYYaEsUsd() {
            PlanImportacion plan = escenario().dolar("1000").cotizacionApagada().mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "10").enDolares().precio("15730.00"))
                .filas(filaDe(2, "A-1", "x", "12", "USD"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("12");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("18880");
        }

        @Test
        @DisplayName("sin ninguna cotizacion guardada: la fila que queda en USD da error y las filas en pesos se importan")
        void sinNingunaCotizacion() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "U-1", "10").enDolares(), Art.art(2, "A-1", "100"))
                .filas(filaDe(2, "N-USD", "Nuevo", "10", "USD"), filaDe(3, "U-1", "x", "12", "USD"),
                    filaDe(4, "A-1", "x", "120", "ARG"), filaDe(5, "N-ARS", "Nuevo", "10", "ARG"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, "N-USD", SIN_COTIZACION), new AvisoDeFila(3, "U-1", SIN_COTIZACION));
            assertThat(plan.cambios()).extracting(CambioPlaneado::fila).containsExactly(4);
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-ARS");
        }

        @Test
        @DisplayName("con cotizacion apagada y sin ningun valor, un nuevo en USD da primero el error de la cotizacion apagada")
        void apagadaGanaSobreSinCotizacion() {
            PlanImportacion plan = escenario().cotizacionApagada().mapeo(M.mapeo().moneda().build())
                .filas(filaDe(2, "N-USD", "Nuevo", "10", "USD"))
                .planificar();

            assertThat(mensajes(plan.errores())).containsExactly(APAGADA);
        }

        @Test
        @DisplayName("una fila en pesos no se ve afectada por la cotizacion apagada ni por no tener ninguna")
        void filasEnPesosSinEfecto() {
            PlanImportacion plan = escenario().cotizacionApagada().mapeo(M.mapeo().moneda().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "120", "ARG"), filaDe(3, "N-1", "Nuevo", "10", "ARS"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.altas()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ alicuota de la fila y "Corrige" (T-127)

    @Nested
    @DisplayName("T-127: alicuota de la fila y tipo 'Corrige'")
    class AlicuotaDeLaFila {

        private static final String IVA_19 =
            "El IVA 19 no es válido. Las alícuotas válidas son 0, 2,5, 5, 10,5, 21 y 27.";

        @Test
        @DisplayName("IVA_21, costo 10.000 y la fila con 10,5 % y la misma lista: corrige, el costo no cambia y el precio baja de 15.730 a 14.370")
        void corrigeElIva() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "10000").precio("15730.00"))
                .filas(filaDe(2, "A-1", "x", "10000", "", "", "", "10,5"))
                .planificar();

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.correcciones()).hasSize(1);
            CambioPlaneado corrige = plan.correcciones().get(0);
            assertThat(corrige.costoAnterior()).isEqualByComparingTo("10000");
            assertThat(corrige.costoNuevo()).isEqualByComparingTo("10000");
            assertThat(corrige.precioAnterior()).isEqualByComparingTo("15730");
            assertThat(corrige.precioNuevo()).isEqualByComparingTo("14370");
            assertThat(corrige.alicuotaAnterior()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(corrige.alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 0, 0, 1, 0));
        }

        @Test
        @DisplayName("una lista que incluye IVA, precio 1.105 y alicuota 10,5 sobre un articulo en IVA_21: el costo es 1.000,00 con la alicuota NUEVA")
        void elCostoUsaLaAlicuotaNueva() {
            PlanImportacion plan = escenario().incluyeIva().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "900"))
                .filas(filaDe(2, "A-1", "x", "1105", "", "", "", "10,5"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("1000.00");
            assertThat(plan.cambios().get(0).alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
        }

        @Test
        @DisplayName("sin la columna de IVA, ese mismo 1.105 se divide por la alicuota del articulo (1,21): 913,22")
        void sinColumnaUsaLaDelArticulo() {
            PlanImportacion plan = escenario().incluyeIva()
                .articulos(Art.art(1, "A-1", "900"))
                .filas(fila(2, "A-1", "x", "1105"))
                .planificar();

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("913.22");
            assertThat(plan.cambios().get(0).alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_21);
        }

        @Test
        @DisplayName("una fila que sube y ademas cambia la alicuota es 'sube' y muestra la alicuota anterior y la nueva")
        void subeYCambiaLaAlicuota() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "10000").precio("15730.00"))
                .filas(filaDe(2, "A-1", "x", "12000", "", "", "", "10,5"))
                .planificar();

            assertThat(plan.correcciones()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).alicuotaAnterior()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(plan.cambios().get(0).alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("costo, IVA y ajustes iguales: sin cambio, sin alicuota ni ajustes que corregir")
        void todoIgualEsSinCambio() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "10000").precio("15730.00"))
                .filas(filaDe(2, "A-1", "x", "10000", "", "", "", "21"))
                .planificar();

            assertThat(plan.correcciones()).isEmpty();
            assertThat(plan.sinCambio()).extracting(PrecioListaPlaneado::articuloId).containsExactly(1L);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 1, 0, 0));
        }

        @Test
        @DisplayName("decision 37: una correccion de IVA que no mueve el precio final (redondeo) tambien es 'Corrige', con la alicuota anterior y la nueva")
        void correccionQueNoMueveElPrecio() {
            // 1 x 1,30 x 1,21 = 1,573 y 1 x 1,30 x 1,105 = 1,4365: los dos suben a 10 con multiplo 10.
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "1").precio("10.00"))
                .filas(filaDe(2, "A-1", "x", "1", "", "", "", "10,5"))
                .planificar();

            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.correcciones()).hasSize(1);
            CambioPlaneado corrige = plan.correcciones().get(0);
            assertThat(corrige.alicuotaAnterior()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(corrige.alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(corrige.precioAnterior()).isEqualByComparingTo("10");
            assertThat(corrige.precioNuevo()).isEqualByComparingTo("10");
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 0, 0, 1, 0));
        }

        @Test
        @DisplayName("la misma correccion de IVA con otros articulos: la que mueve el precio y la que no son 'Corrige'; la de IVA igual es 'sin cambio'")
        void dosCorreccionesYUnSinCambio() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "1").precio("10.00"), Art.art(2, "A-2", "10000").precio("15730.00"),
                    Art.art(3, "A-3", "1").precio("10.00"))
                .filas(filaDe(2, "A-1", "x", "1", "", "", "", "10,5"), filaDe(3, "A-2", "x", "10000", "", "", "", "10,5"),
                    filaDe(4, "A-3", "x", "1", "", "", "", "21"))
                .planificar();

            assertThat(plan.correcciones()).extracting(CambioPlaneado::articuloId).containsExactly(1L, 2L);
            assertThat(plan.sinCambio()).extracting(PrecioListaPlaneado::articuloId).containsExactly(3L);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 1, 0, 0, 0, 2, 0));
        }

        @Test
        @DisplayName("sin datos de IVA en la fila (celda vacia) el existente conserva la suya y el nuevo entra con IVA_21")
        void sinDatoDeIva() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().parDeIva().build())
                .articulos(Art.art(1, "A-1", "100").iva(AlicuotaIva.IVA_10_5))
                .filas(filaDe(2, "A-1", "x", "120", "", "", "", ""), filaDe(3, "N-1", "Nuevo", "10", "", "", "", ""))
                .planificar();

            assertThat(plan.cambios().get(0).alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(plan.altas().get(0).alicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        }

        @Test
        @DisplayName("un articulo nuevo toma la alicuota de la fila: 10,5 es IVA_10_5 y el costo usa esa alicuota")
        void nuevoConAlicuota() {
            PlanImportacion plan = escenario().incluyeIva().mapeo(M.mapeo().iva().build())
                .filas(filaDe(2, "N-1", "Nuevo", "1105", "", "", "", "10,5"))
                .planificar();

            assertThat(plan.altas()).hasSize(1);
            assertThat(plan.altas().get(0).alicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(plan.altas().get(0).costo()).isEqualByComparingTo("1000.00");
            assertThat(plan.altas().get(0).precioNuevo()).isEqualByComparingTo("1440");
        }

        @Test
        @DisplayName("un IVA de 19 es un error con las validas y no se crea ni se actualiza nada de esa fila")
        void ivaInvalido() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "120", "", "", "", "19"), filaDe(3, "N-1", "Nuevo", "10", "", "", "", "19"),
                    filaDe(4, "N-2", "Nuevo", "10", "", "", "", "21"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, "A-1", IVA_19), new AvisoDeFila(3, "N-1", IVA_19));
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-2");
        }

        @Test
        @DisplayName("el IVA sale del par sin IVA / con IVA: 1.000 y 1.105 dan 10,5 sin advertencia; 1.000 y 1.190 dan 21 con advertencia")
        void ivaDelPar() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().parDeIva().build())
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(filaDe(2, "A-1", "x", "120", "", "1000", "1105"), filaDe(3, "A-2", "x", "120", "", "1000", "1190"))
                .planificar();

            assertThat(plan.cambios()).extracting(CambioPlaneado::alicuotaNueva)
                .containsExactly(AlicuotaIva.IVA_10_5, AlicuotaIva.IVA_21);
            assertThat(plan.advertencias()).hasSize(1);
            assertThat(plan.advertencias().get(0).fila()).isEqualTo(3);
            assertThat(plan.advertencias().get(0).mensaje()).contains("19").contains("21");
        }

        @Test
        @DisplayName("una fila con error de IVA no deja la advertencia del par")
        void filaConErrorNoAdvierte() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().parDeIva().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "0", "", "1000", "1190"))
                .planificar();

            assertThat(plan.errores()).hasSize(1);
            assertThat(plan.advertencias()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ ajustes por posicion (T-128)

    @Nested
    @DisplayName("T-128: descuentos y recargos por posicion (conservar o borrar)")
    class AjustesPorPosicion {

        @Test
        @DisplayName("sin columnas de ajuste el articulo conserva su ajuste 1 de -5 y el costo es 950,00")
        void sinColumnasSeConservan() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "900").ajustes("-5", null, null))
                .filas(fila(2, "A-1", "x", "1000"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("950.00");
            assertThat(plan.cambios().get(0).ajustesNuevos()).isEqualTo(new Ajustes(bd("-5"), null, null));
            assertThat(plan.cambios().get(0).ajustesAnteriores()).isEqualTo(new Ajustes(bd("-5"), null, null));
        }

        @Test
        @DisplayName("con la columna 1 mapeada y la celda vacia el ajuste se borra y el costo es 1.000,00")
        void celdaVaciaBorra() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .articulos(Art.art(1, "A-1", "950").ajustes("-5", null, null))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", ""))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("1000.00");
            assertThat(plan.cambios().get(0).ajustesNuevos()).isEqualTo(Ajustes.NINGUNO);
        }

        @Test
        @DisplayName("ajuste 1 de -5, ajuste 2 de +3 y solo la columna 2 mapeada con +4: el 1 se conserva y el 2 pasa a +4")
        void unaColumnaPorPosicion() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste2().build())
                .articulos(Art.art(1, "A-1", "900").ajustes("-5", "3", null))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", "", "+4"))
                .planificar();

            assertThat(plan.cambios().get(0).ajustesNuevos()).isEqualTo(new Ajustes(bd("-5"), bd("4"), null));
            // 1000 x 0,95 x 1,04
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("988.00");
        }

        @Test
        @DisplayName("la fila con -5 igual al del articulo y la misma lista es sin cambio")
        void mismoAjusteEsSinCambio() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .articulos(Art.art(1, "A-1", "950").ajustes("-5", null, null))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", "-5"))
                .planificar();

            assertThat(plan.sinCambio()).extracting(PrecioListaPlaneado::articuloId).containsExactly(1L);
            assertThat(plan.correcciones()).isEmpty();
        }

        @Test
        @DisplayName("un articulo sin ajustes y la celda vacia: sin cambio")
        void sinAjustesYCeldaVacia() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .articulos(Art.art(1, "A-1", "1000"))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", ""))
                .planificar();

            assertThat(plan.sinCambio()).hasSize(1);
            assertThat(plan.correcciones()).isEmpty();
        }

        @Test
        @DisplayName("mismo costo pero otro ajuste: corrige (el ajuste se guarda aunque el costo no cambie)")
        void mismoCostoOtroAjuste() {
            // La lista bajo a 950 y ya no trae el descuento: el costo es 950 en los dos casos.
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .articulos(Art.art(1, "A-1", "950").ajustes("-5", null, null))
                .filas(filaDe(2, "A-1", "x", "950", "", "", "", "", ""))
                .planificar();

            assertThat(plan.correcciones()).hasSize(1);
            assertThat(plan.correcciones().get(0).ajustesAnteriores()).isEqualTo(new Ajustes(bd("-5"), null, null));
            assertThat(plan.correcciones().get(0).ajustesNuevos()).isEqualTo(Ajustes.NINGUNO);
            assertThat(plan.conteos().corrigen()).isEqualTo(1);
        }

        @Test
        @DisplayName("un articulo nuevo con la celda vacia se crea sin ese ajuste; con -5 se crea con el ajuste y costo 950,00")
        void nuevoConAjustes() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .filas(filaDe(2, "N-1", "Nuevo", "1000", "", "", "", "", ""),
                    filaDe(3, "N-2", "Nuevo", "1000", "", "", "", "", "-5"))
                .planificar();

            assertThat(plan.altas()).hasSize(2);
            assertThat(plan.altas().get(0).ajustes()).isEqualTo(Ajustes.NINGUNO);
            assertThat(plan.altas().get(0).costo()).isEqualByComparingTo("1000.00");
            assertThat(plan.altas().get(1).ajustes()).isEqualTo(new Ajustes(bd("-5"), null, null));
            assertThat(plan.altas().get(1).costo()).isEqualByComparingTo("950.00");
        }

        @Test
        @DisplayName("un ajuste fuera de rango, que no es un numero o de -100 es un error de esa fila y las demas siguen")
        void ajusteInvalido() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"), Art.art(3, "A-3", "100"),
                    Art.art(4, "A-4", "100"), Art.art(5, "A-5", "100"))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", "abc"),
                    filaDe(3, "A-2", "x", "1000", "", "", "", "", "-100"),
                    filaDe(4, "A-3", "x", "1000", "", "", "", "", "1000,01"),
                    filaDe(5, "A-4", "x", "1000", "", "", "", "", "-150"),
                    filaDe(6, "A-5", "x", "1000", "", "", "", "", "-99,99"))
                .planificar();

            assertThat(mensajes(plan.errores())).containsExactly(
                "El descuento o recargo 1 no es un número.",
                "El descuento o recargo 1 tiene que estar entre -99,99 y 1000.",
                "El descuento o recargo 1 tiene que estar entre -99,99 y 1000.",
                "El descuento o recargo 1 tiene que estar entre -99,99 y 1000.");
            assertThat(plan.cambios()).extracting(CambioPlaneado::articuloId).containsExactly(5L);
        }

        @Test
        @DisplayName("una fila con error de ajuste no cambia al articulo ni lo marca como 'no esta'")
        void filaConErrorNoCambia() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().ajuste1().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", "abc"))
                .planificar();

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.correcciones()).isEmpty();
            assertThat(plan.noEstan()).isEmpty();
            assertThat(plan.conteos().conError()).isEqualTo(1);
        }

        @Test
        @DisplayName("bonificacion 10 %, ajustes -5 y +3 sobre 1.000: costo 880,65 y precio 1.390")
        void bonificacionYAjustes() {
            PlanImportacion plan = escenario().bonificaciones("10", null, null)
                .mapeo(M.mapeo().ajuste1().ajuste2().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "", "-5", "+3"))
                .planificar();

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("880.65");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("1390");
        }
    }

    // ------------------------------------------------------------------ repetidos y "no estan" en todo el libro (T-129)

    @Nested
    @DisplayName("T-129: repetidos y 'no estan' sobre todo el libro")
    class VariasHojas {

        private static final String HOJA_1 = "TORNILLOS";
        private static final String HOJA_2 = "TUERCAS";
        private static final String HOJA_3 = "ARANDELAS";

        @Test
        @DisplayName("el mismo codigo en dos hojas es error en las dos filas y el mensaje nombra hoja y fila de cada una")
        void repetidoEntreHojas() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2).mapeo(M.mapeo().hojas(0, 1).build())
                .filas(filaEnHoja(0, 5, "A-1", "x", "10"), filaEnHoja(1, 9, "a-1", "x", "10"))
                .planificar();

            String mensaje = "El código aparece repetido en la lista (TORNILLOS fila 5 y TUERCAS fila 9).";
            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(0, 5, "A-1", mensaje), new AvisoDeFila(1, 9, "a-1", mensaje));
            assertThat(plan.altas()).isEmpty();
            assertThat(plan.conteos().conError()).isEqualTo(2);
        }

        @Test
        @DisplayName("el mismo codigo dos veces dentro de una hoja da el error de siempre, aunque el libro tenga varias hojas")
        void repetidoDentroDeUnaHoja() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2).mapeo(M.mapeo().hojas(0, 1).build())
                .filas(filaEnHoja(1, 4, "A-1", "x", "10"), filaEnHoja(1, 9, "A-1", "x", "10"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(1, 4, "A-1", "El código aparece repetido en la lista (filas 4 y 9)."),
                new AvisoDeFila(1, 9, "A-1", "El código aparece repetido en la lista (filas 4 y 9)."));
        }

        @Test
        @DisplayName("tres apariciones en dos hojas se listan todas con su hoja")
        void tresApariciones() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2).mapeo(M.mapeo().hojas(0, 1).build())
                .filas(filaEnHoja(0, 3, "A-1", "x", "10"), filaEnHoja(0, 7, "A-1", "x", "10"),
                    filaEnHoja(1, 2, "A-1", "x", "10"))
                .planificar();

            assertThat(mensajes(plan.errores())).containsOnly(
                "El código aparece repetido en la lista (TORNILLOS fila 3, TORNILLOS fila 7 y TUERCAS fila 2).");
        }

        @Test
        @DisplayName("un activo del proveedor presente en una hoja intermedia no esta en 'no estan'; el que no figura en ninguna sale una vez")
        void noEstanSobreTodoElLibro() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2, HOJA_3).mapeo(M.mapeo().hojas(0, 1, 2).build())
                .articulos(Art.art(1, "T-100", "100"), Art.art(2, "Z-999", "100"))
                .filas(filaEnHoja(0, 2, "A-1", "x", "10"), filaEnHoja(1, 2, "T-100", "x", "100"),
                    filaEnHoja(2, 2, "A-2", "x", "10"))
                .planificar();

            assertThat(plan.noEstan()).extracting(ArticuloVigente::codigoProveedor).containsExactly("Z-999");
            assertThat(plan.conteos().noEstan()).isEqualTo(1);
        }

        @Test
        @DisplayName("con una sola hoja elegida los articulos de las otras hojas si salen en 'no estan'")
        void noEstanSobreLasHojasElegidas() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2).mapeo(M.mapeo().hojas(0).build())
                .articulos(Art.art(1, "T-100", "100"))
                .filas(filaEnHoja(0, 2, "A-1", "x", "10"), filaEnHoja(1, 2, "T-100", "x", "100"))
                .planificar();

            assertThat(plan.noEstan()).extracting(ArticuloVigente::codigoProveedor).containsExactly("T-100");
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("A-1");
        }

        @Test
        @DisplayName("las filas de una hoja no elegida no son errores ni altas ni repetidos")
        void hojaNoElegidaNoCuenta() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2).mapeo(M.mapeo().hojas(1).build())
                .filas(filaEnHoja(0, 2, "A-1", "x", "10"), filaEnHoja(1, 2, "A-1", "x", "10"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.altas()).extracting(AltaPlaneada::hoja).containsExactly(1);
        }

        @Test
        @DisplayName("las filas de titulo se cuentan como salteadas y no aportan codigos ni errores")
        void salteadasNoAportanCodigos() {
            PlanImportacion plan = escenario().hojas(HOJA_1)
                .articulos(Art.art(1, "Familia: 001 - Pasadores", "100"))
                .filas(filaDe(2, "Familia: 001 - Pasadores"), filaDe(3, "A-1", "Tornillo", "10"),
                    filaDe(4, "Familia: 002 - Bulones"))
                .planificar();

            assertThat(plan.salteadas()).isEqualTo(2);
            assertThat(plan.conteos().salteadas()).isEqualTo(2);
            assertThat(plan.errores()).isEmpty();
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("A-1");
            // Un titulo con el mismo texto que el codigo de un articulo no lo marca como presente.
            assertThat(plan.noEstan()).extracting(ArticuloVigente::id).containsExactly(1L);
        }

        @Test
        @DisplayName("los errores y las advertencias salen ordenados por hoja y fila")
        void ordenadosPorHojaYFila() {
            PlanImportacion plan = escenario().hojas(HOJA_1, HOJA_2).mapeo(M.mapeo().hojas(0, 1).build())
                .filas(filaEnHoja(0, 8, "", "x", "10"), filaEnHoja(0, 3, "", "x", "10"),
                    filaEnHoja(1, 2, "", "x", "10"))
                .planificar();

            assertThat(plan.errores()).extracting(AvisoDeFila::hoja, AvisoDeFila::fila)
                .containsExactly(org.assertj.core.api.Assertions.tuple(0, 3), org.assertj.core.api.Assertions.tuple(0, 8),
                    org.assertj.core.api.Assertions.tuple(1, 2));
        }
    }

    // ------------------------------------------------------------------ altas con moneda, IVA, unidad, barras y rubro (T-130)

    @Nested
    @DisplayName("T-130: altas con moneda, unidad, codigo de barras y rubro por nombre")
    class AltasConCamposDeLaLista {

        @Test
        @DisplayName("un nuevo con Mon. = ARG y lista 1.000 se crea en pesos con costo 1.000; con USD y lista 10 y dolar 1.000, en dolares con costo 10,00 y precio 15.730")
        void monedaDelAlta() {
            PlanImportacion plan = escenario().dolar("1000").mapeo(M.mapeo().moneda().build())
                .filas(filaDe(2, "N-1", "Nuevo", "1000", "ARG"), filaDe(3, "N-2", "Importado", "10", "USD"))
                .planificar();

            assertThat(plan.altas()).extracting(AltaPlaneada::moneda).containsExactly(Moneda.ARS, Moneda.USD);
            assertThat(plan.altas().get(0).costo()).isEqualByComparingTo("1000");
            assertThat(plan.altas().get(0).precioNuevo()).isEqualByComparingTo("1580");
            assertThat(plan.altas().get(1).costo()).isEqualByComparingTo("10.00");
            assertThat(plan.altas().get(1).precioNuevo()).isEqualByComparingTo("15730");
        }

        @Test
        @DisplayName("sin columna ni moneda fija el alta es en pesos; con moneda fija USD, en dolares")
        void sinColumnaYMonedaFija() {
            PlanImportacion enPesos = escenario().dolar("1000").filas(fila(2, "N-1", "Nuevo", "10")).planificar();
            PlanImportacion enDolares = escenario().dolar("1000").mapeo(M.mapeo().monedaFija(Moneda.USD).build())
                .filas(fila(2, "N-1", "Nuevo", "10")).planificar();

            assertThat(enPesos.altas().get(0).moneda()).isEqualTo(Moneda.ARS);
            assertThat(enDolares.altas().get(0).moneda()).isEqualTo(Moneda.USD);
            assertThat(enDolares.altas().get(0).precioNuevo()).isEqualByComparingTo("15730");
        }

        @Test
        @DisplayName("barras, unidad y un rubro existente van al alta, y el precio usa el margen de ese rubro")
        void barrasUnidadYRubro() {
            PlanImportacion plan = escenario().contextoRubro(4, "35.00").rubroPorNombre("Tornillería", 4)
                .mapeo(M.mapeo().barras().unidad().rubro().build())
                .filas(filaDe(2, "N-1", "Tornillo", "1000", "", "", "", "", "", "", "", " 7791234567890 ", "metro",
                    "tornilleria"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            AltaPlaneada alta = plan.altas().get(0);
            assertThat(alta.codigoBarras()).isEqualTo("7791234567890");
            assertThat(alta.unidad()).isEqualTo(Unidad.METRO);
            assertThat(alta.rubroId()).isEqualTo(4L);
            // 1.000 x 1,35 x 1,21 = 1.633,50 sube a 1.640
            assertThat(alta.precioNuevo()).isEqualByComparingTo("1640");
        }

        @Test
        @DisplayName("un rubro que no existe da una advertencia, el alta queda sin rubro de la columna (usa el elegido) y no se crea ningun rubro")
        void rubroInexistente() {
            PlanImportacion plan = escenario().rubroPorNombre("Tornillería", 4).mapeo(M.mapeo().rubro().build())
                .filas(filaDe(2, "N-1", "Pintura", "1000", "", "", "", "", "", "", "", "", "", "Pinturas"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.altas()).hasSize(1);
            assertThat(plan.altas().get(0).rubroId()).isNull();
            assertThat(mensajes(plan.advertencias())).hasSize(1);
            assertThat(plan.advertencias().get(0).mensaje()).contains("El rubro «Pinturas» no existe");
            assertThat(plan.advertencias().get(0).fila()).isEqualTo(2);
        }

        @Test
        @DisplayName("una celda de rubro vacia no advierte ni asigna rubro")
        void rubroVacio() {
            PlanImportacion plan = escenario().rubroPorNombre("Tornillería", 4).mapeo(M.mapeo().rubro().build())
                .filas(filaDe(2, "N-1", "Pintura", "1000", "", "", "", "", "", "", "", "", "", ""))
                .planificar();

            assertThat(plan.altas().get(0).rubroId()).isNull();
            assertThat(plan.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("el nombre de la hoja nunca es el rubro: sin columna de rubro el alta no lleva rubro aunque exista uno con ese nombre")
        void laHojaNoEsRubro() {
            PlanImportacion plan = escenario().hojas("TORNILLERIA").rubroPorNombre("Tornilleria", 4)
                .filas(filaDe(2, "N-1", "Tornillo", "1000"))
                .planificar();

            assertThat(plan.altas().get(0).rubroId()).isNull();
            assertThat(plan.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("una unidad que no se reconoce (caja) es un error de la fila y las demas siguen")
        void unidadInvalida() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().unidad().build())
                .filas(filaDe(2, "N-1", "Caja de tornillos", "100", "", "", "", "", "", "", "", "", "caja"),
                    filaDe(3, "N-2", "Tornillo", "100", "", "", "", "", "", "", "", "", "kg"))
                .planificar();

            assertThat(plan.errores()).hasSize(1);
            assertThat(plan.errores().get(0).fila()).isEqualTo(2);
            assertThat(plan.errores().get(0).mensaje()).contains("Unidad no reconocida");
            assertThat(plan.altas()).extracting(AltaPlaneada::unidad).containsExactly(Unidad.KILO);
        }

        @Test
        @DisplayName("un codigo de barras de mas de 30 caracteres es un error de la fila")
        void barrasMuyLargo() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().barras().build())
                .filas(filaDe(2, "N-1", "Tornillo", "100", "", "", "", "", "", "", "", "9".repeat(31)))
                .planificar();

            assertThat(mensajes(plan.errores())).containsExactly("El código de barras no puede superar 30 caracteres");
            assertThat(plan.altas()).isEmpty();
        }

        @Test
        @DisplayName("un codigo de barras de otro articulo activo no se puede crear")
        void barrasOcupadas() {
            PlanImportacion plan = escenario().barrasOcupadas("7791234567890").mapeo(M.mapeo().barras().build())
                .filas(filaDe(2, "N-1", "Tornillo", "100", "", "", "", "", "", "", "", "7791234567890"),
                    filaDe(3, "N-2", "Tuerca", "100", "", "", "", "", "", "", "", "7790000000001"))
                .planificar();

            assertThat(plan.errores()).containsExactly(new AvisoDeFila(2, "N-1",
                "Ya existe un artículo activo con el código de barras 7791234567890."));
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-2");
        }

        @Test
        @DisplayName("un codigo de barras repetido dentro del archivo: se crea el primero y el segundo no")
        void barrasRepetidasEnElArchivo() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().barras().build())
                .filas(filaDe(2, "N-1", "Tornillo", "100", "", "", "", "", "", "", "", "7791234567890"),
                    filaDe(3, "N-2", "Tuerca", "100", "", "", "", "", "", "", "", "7791234567890"))
                .planificar();

            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-1");
            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(3);
            assertThat(plan.errores().get(0).mensaje()).contains("Ya existe un artículo activo con el código de barras");
        }

        @Test
        @DisplayName("los existentes no cambian de barras, unidad ni rubro: una unidad mala o unas barras ocupadas no son error")
        void losExistentesNoCambian() {
            PlanImportacion plan = escenario().barrasOcupadas("7791234567890").rubroPorNombre("Tornillería", 4)
                .mapeo(M.mapeo().barras().unidad().rubro().build())
                .articulos(Art.art(1, "A-1", "100"))
                .filas(filaDe(2, "A-1", "x", "120", "", "", "", "", "", "", "", "7791234567890", "caja", "Pinturas"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.advertencias()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
        }

        @Test
        @DisplayName("sin columnas de barras, unidad ni rubro los nuevos entran con UNIDAD, sin barras y sin rubro")
        void sinColumnas() {
            PlanImportacion plan = escenario().filas(fila(2, "N-1", "Tornillo", "100")).planificar();

            AltaPlaneada alta = plan.altas().get(0);
            assertThat(alta.unidad()).isEqualTo(Unidad.UNIDAD);
            assertThat(alta.codigoBarras()).isNull();
            assertThat(alta.rubroId()).isNull();
            assertThat(alta.alicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(alta.ajustes()).isEqualTo(Ajustes.NINGUNO);
        }
    }

    // ------------------------------------------------------------------ T-16

    @Nested
    @DisplayName("T-16: filas ignoradas y errores por fila")
    class ErroresPorFila {

        @Test
        @DisplayName("las filas anteriores a filaDesde se ignoran: ni cuentan ni se listan")
        void ignoraFilasAnteriores() {
            PlanImportacion plan = escenario()
                .filas(fila(1, "Codigo", "Detalle", "Precio"), fila(2, "", "", "abc"), fila(3, "X-1", "x", "abc"))
                .mapeo(new MapeoLista(0, 3, 0, 1, 2, null))
                .planificar();

            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(3);
            assertThat(plan.conteos().conError()).isEqualTo(1);
        }

        @Test
        @DisplayName("una fila con codigo y costo en blanco se ignora (pie, separador, titulo de categoria)")
        void ignoraFilasEnBlanco() {
            PlanImportacion plan = escenario()
                .filas(fila(2, null, null, null), fila(3, "  ", "Tornillos", ""), fila(4, " ", null, " "),
                    new FilaLeida(5, List.of()))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("falta el codigo y hay costo: error con el numero de fila y sin codigo")
        void faltaElCodigo() {
            PlanImportacion plan = escenario().filas(fila(7, "  ", "Algo", "100")).planificar();

            assertThat(plan.errores()).containsExactly(new AvisoDeFila(7, null, "Falta el código del proveedor."));
            assertThat(plan.conteos().conError()).isEqualTo(1);
        }

        @Test
        @DisplayName("un codigo de mas de 40 caracteres es error; uno de exactamente 40 no")
        void codigoDeMasDe40() {
            String de40 = "A".repeat(40);
            String de41 = "A".repeat(41);

            // El articulo existente con el codigo de 40 hace que esa fila sea valida (coincide).
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, de40, "50"))
                .filas(fila(2, de41, "x", "100"), fila(3, de40, "x", "100"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, de41, "El código tiene más de 40 caracteres."));
            assertThat(plan.cambios()).extracting(CambioPlaneado::fila).containsExactly(3);
        }

        @Test
        @DisplayName("el largo del codigo se cuenta en code points, no en unidades UTF-16")
        void codigoSeCuentaEnCodePoints() {
            String de40CodePoints = "😀".repeat(40);
            String de41CodePoints = "😀".repeat(41);

            PlanImportacion plan = escenario()
                .articulos(Art.art(1, de40CodePoints, "50"))
                .filas(fila(2, de40CodePoints, "x", "100"), fila(3, de41CodePoints, "x", "100"))
                .planificar();

            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(3);
            assertThat(plan.cambios()).extracting(CambioPlaneado::fila).containsExactly(2);
        }

        @Test
        @DisplayName("el codigo con espacios alrededor se recorta antes de contar y de informar")
        void codigoSeRecorta() {
            PlanImportacion plan = escenario().filas(fila(2, "  " + "A".repeat(41) + "  ", "x", "100")).planificar();

            assertThat(plan.errores().get(0).codigo()).isEqualTo("A".repeat(41));
        }

        @Test
        @DisplayName("costo vacio o que no es un numero")
        void costoVacioONoNumerico() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "A-1", "x", null), fila(3, "A-2", "x", "abc"), fila(4, "A-3", "x", "$"),
                    fila(5, "A-4", "x", "1e5"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, "A-1", "El costo está vacío o no es un número."),
                new AvisoDeFila(3, "A-2", "El costo está vacío o no es un número."),
                new AvisoDeFila(4, "A-3", "El costo está vacío o no es un número."),
                new AvisoDeFila(5, "A-4", "El costo está vacío o no es un número."));
        }

        @Test
        @DisplayName("costo negativo")
        void costoNegativo() {
            PlanImportacion plan = escenario().filas(fila(2, "A-1", "x", "-10"), fila(3, "A-2", "x", "-0,01")).planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, "A-1", "El costo no puede ser negativo."),
                new AvisoDeFila(3, "A-2", "El costo no puede ser negativo."));
        }

        @Test
        @DisplayName("precio de lista 0, 0,00 y 0,004 dan costo calculado 0: error")
        void costoCalculadoCero() {
            String mensaje = "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.";

            PlanImportacion plan = escenario()
                .filas(fila(2, "A-1", "x", "0"), fila(3, "A-2", "x", "0,00"), fila(4, "A-3", "x", "0,004"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, "A-1", mensaje), new AvisoDeFila(3, "A-2", mensaje),
                new AvisoDeFila(4, "A-3", mensaje));
        }

        @Test
        @DisplayName("una celda numerica de costo 0 tambien es error")
        void celdaNumericaCero() {
            FilaLeida fila = new FilaLeida(2, List.of(celda("A-1"), celda("x"), new Celda("0", bd("0"))));

            PlanImportacion plan = escenario().filas(fila).planificar();

            assertThat(mensajes(plan.errores()))
                .containsExactly("El costo calculado es 0. Revisá el precio de lista y las bonificaciones.");
        }

        @Test
        @DisplayName("una bonificacion de 100 deja todas las filas con costo calculado 0")
        void bonificacionCien() {
            PlanImportacion plan = escenario()
                .bonificaciones("100", null, null)
                .filas(fila(2, "A-1", "x", "100"), fila(3, "A-2", "x", "2500"))
                .planificar();

            assertThat(plan.conteos().conError()).isEqualTo(2);
            assertThat(mensajes(plan.errores())).containsOnly(
                "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.");
        }

        @Test
        @DisplayName("mas de 10 digitos enteros es demasiado grande; exactamente 10 pasa")
        void costoDemasiadoGrande() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "A-1", "x", "12345678901"), fila(3, "A-2", "x", "1234567890"),
                    fila(4, "A-3", "x", "99.999.999.999,99"))
                .planificar();

            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(2, "A-1", "El costo es demasiado grande."),
                new AvisoDeFila(4, "A-3", "El costo es demasiado grande."));
        }

        @Test
        @DisplayName("una celda numerica enorme (1E+15) es demasiado grande y no revienta")
        void celdaNumericaEnorme() {
            FilaLeida fila = new FilaLeida(2, List.of(celda("A-1"), celda("x"), new Celda("1E+15", bd("1E+15"))));

            PlanImportacion plan = escenario().filas(fila).planificar();

            assertThat(mensajes(plan.errores())).containsExactly("El costo es demasiado grande.");
        }

        @Test
        @DisplayName("el codigo es lo primero que se valida: sin codigo y con costo malo informa el codigo")
        void primeroElCodigo() {
            PlanImportacion plan = escenario().filas(fila(2, "", "x", "abc")).planificar();

            assertThat(mensajes(plan.errores())).containsExactly("Falta el código del proveedor.");
        }

        @Test
        @DisplayName("cada fila cae en una sola categoria: una fila con error aparece una sola vez")
        void unaCategoriaPorFila() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "", "x", "-5"), fila(3, "A-1", "x", "0"), fila(4, "A-2", "x", null))
                .planificar();

            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(2, 3, 4);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 3));
        }

        @Test
        @DisplayName("el plan de entradas validas sin articulos no tiene errores")
        void sinErroresConFilasValidas() {
            PlanImportacion plan = escenario().filas(fila(2, "A-1", "x", "100")).planificar();

            assertThat(plan.errores()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ T-17

    @Nested
    @DisplayName("T-17: duplicados y coincidencia")
    class DuplicadosYCoincidencia {

        @Test
        @DisplayName("un codigo repetido (sin importar mayusculas) marca TODAS las ocurrencias y no aplica ni crea nada")
        void duplicadosMarcanTodas() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "ABC-1", "100"))
                .filas(fila(4, "ABC-1", "x", "120"), fila(9, "abc-1", "x", "130"))
                .planificar();

            String mensaje = "El código aparece repetido en la lista (filas 4 y 9).";
            assertThat(plan.errores()).containsExactly(
                new AvisoDeFila(4, "ABC-1", mensaje), new AvisoDeFila(9, "abc-1", mensaje));
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 2));
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.altas()).isEmpty();
        }

        @Test
        @DisplayName("el articulo existente con codigo repetido cuenta como presente (no suma a noEstan)")
        void duplicadoCuentaComoPresente() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "ABC-1", "100"))
                .filas(fila(4, "ABC-1", "x", "120"), fila(9, "abc-1", "x", "130"))
                .planificar();

            assertThat(plan.noEstan()).isEmpty();
            assertThat(plan.conteos().noEstan()).isZero();
        }

        @Test
        @DisplayName("un codigo repetido que no existe en el catalogo no genera un articulo nuevo")
        void duplicadoNoCreaNuevo() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "NUE-1", "Nuevo", "50"), fila(3, "nue-1", "Nuevo", "50"))
                .planificar();

            assertThat(plan.altas()).isEmpty();
            assertThat(plan.conteos().conError()).isEqualTo(2);
        }

        @Test
        @DisplayName("tres ocurrencias: el mensaje las lista a todas con coma y y")
        void tresOcurrencias() {
            PlanImportacion plan = escenario()
                .filas(fila(3, "A-1", "x", "10"), fila(5, "a-1", "x", "10"), fila(8, " A-1 ", "x", "10"))
                .planificar();

            assertThat(mensajes(plan.errores())).containsOnly(
                "El código aparece repetido en la lista (filas 3, 5 y 8).");
            assertThat(plan.errores()).hasSize(3);
        }

        @Test
        @DisplayName("el duplicado ignora acentos y espacios no separables")
        void duplicadoConAcentos() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "TÉE-1", "x", "10"), fila(3, "tee-1 ", "x", "10"))
                .planificar();

            assertThat(plan.conteos().conError()).isEqualTo(2);
        }

        @Test
        @DisplayName("el duplicado se informa como duplicado aunque una de las filas tenga mal el costo")
        void duplicadoGanaSobreCosto() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "A-1", "x", "abc"), fila(3, "A-1", "x", "100"))
                .planificar();

            assertThat(mensajes(plan.errores())).containsOnly(
                "El código aparece repetido en la lista (filas 2 y 3).");
        }

        @Test
        @DisplayName("filas con codigos distintos no son duplicados")
        void distintosNoSonDuplicados() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(fila(2, "A-1", "x", "110"), fila(3, "A-2", "x", "110"))
                .planificar();

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(2);
        }

        @Test
        @DisplayName("coincide por codigo normalizado: mayusculas, acentos y espacios")
        void coincidenciaNormalizada() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "ABC-1", "100"), Art.art(2, "TEE-10", "100"), Art.art(3, "X-5", "100"))
                .filas(fila(2, "abc-1", "x", "120"), fila(3, "TÉE-10", "x", "120"), fila(4, " X-5 ", "x", "120"))
                .planificar();

            assertThat(plan.cambios()).extracting(CambioPlaneado::articuloId).containsExactly(1L, 2L, 3L);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(3, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("sube: trae los datos del cambio")
        void sube() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(7, "ABC-1", "100"))
                .filas(fila(5, "abc-1 ", "x", "1.234,56"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            CambioPlaneado cambio = plan.cambios().get(0);
            assertThat(cambio.fila()).isEqualTo(5);
            assertThat(cambio.articuloId()).isEqualTo(7L);
            assertThat(cambio.codigoProveedor()).isEqualTo("abc-1");
            assertThat(cambio.descripcion()).isEqualTo("Articulo 7");
            assertThat(cambio.precioLista()).isEqualByComparingTo("1234.56");
            assertThat(cambio.costoAnterior()).isEqualByComparingTo("100");
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("1234.56");
            assertThat(cambio.costoNuevo().scale()).isEqualTo(2);
            assertThat(cambio.activo()).isTrue();
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("baja: el costo nuevo es menor")
        void baja() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "80"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("80");
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 1, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("sin cambio se decide por valor numerico: 100 y 100,00 son iguales")
        void sinCambioPorValor() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100.00"))
                .filas(fila(2, "A-1", "x", "100,00"), fila(3, "A-2", "x", "100"))
                .planificar();

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).extracting(PrecioListaPlaneado::articuloId).containsExactly(1L, 2L);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 2, 0, 0));
        }

        @Test
        @DisplayName("sin cambio genera el precio de lista a registrar, con el valor crudo del archivo")
        void sinCambioGeneraPrecioLista() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(4, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "100"))
                .planificar();

            assertThat(plan.sinCambio()).hasSize(1);
            assertThat(plan.sinCambio().get(0).articuloId()).isEqualTo(4L);
            assertThat(plan.sinCambio().get(0).precioLista()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("un costo que difiere por un centavo ya es un cambio")
        void diferenciaDeUnCentavo() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100.00"), Art.art(2, "A-2", "100.00"))
                .filas(fila(2, "A-1", "x", "100,01"), fila(3, "A-2", "x", "99,99"))
                .planificar();

            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 1, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("un articulo inactivo coincidente se clasifica igual y se informa como inactivo")
        void inactivoCoincidente() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100").inactivo())
                .filas(fila(2, "A-1", "x", "120"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).activo()).isFalse();
            assertThat(plan.conteos().suben()).isEqualTo(1);
            assertThat(plan.noEstan()).isEmpty();
        }

        @Test
        @DisplayName("costo actual 0 y lista 50: sube")
        void costoActualCero() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "0.00"))
                .filas(fila(2, "A-1", "x", "50"))
                .planificar();

            assertThat(plan.conteos().suben()).isEqualTo(1);
        }

        @Test
        @DisplayName("noEstan: activos del proveedor (incluso sin codigo) que no figuran; los inactivos no cuentan")
        void noEstan() {
            PlanImportacion plan = escenario()
                .articulos(
                    Art.art(1, "A-1", "100"),
                    Art.art(2, "A-2", "100"),
                    Art.art(3, null, "100"),
                    Art.art(4, "A-4", "100").inactivo())
                .filas(fila(2, "A-1", "x", "100"))
                .planificar();

            assertThat(plan.noEstan()).extracting(ArticuloVigente::id).containsExactly(2L, 3L);
            assertThat(plan.conteos().noEstan()).isEqualTo(2);
        }

        @Test
        @DisplayName("una fila con error de costo igual marca su codigo como presente y no toca el articulo")
        void errorDeCostoMarcaPresente() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(fila(2, "A-1", "x", "abc"), fila(3, "A-2", "x", "0"))
                .planificar();

            assertThat(plan.noEstan()).isEmpty();
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.conteos().conError()).isEqualTo(2);
        }

        @Test
        @DisplayName("un codigo vacio o de mas de 40 caracteres no marca presente a ningun articulo")
        void codigoInvalidoNoMarcaPresente() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "", "x", "100"), fila(3, "A".repeat(41), "x", "100"))
                .planificar();

            assertThat(plan.noEstan()).extracting(ArticuloVigente::id).containsExactly(1L);
        }

        @Test
        @DisplayName("si dos articulos comparten clave gana el primero del snapshot (putIfAbsent)")
        void gananElPrimero() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "ABC-1", "100"), Art.art(2, "abc-1", "100"))
                .filas(fila(2, "ABC-1", "x", "120"))
                .planificar();

            assertThat(plan.cambios()).extracting(CambioPlaneado::articuloId).containsExactly(1L);
        }

        @Test
        @DisplayName("el plan mezcla las categorias y los conteos coinciden con el contenido")
        void conteosMezclados() {
            PlanImportacion plan = escenario()
                .articulos(
                    Art.art(1, "SUBE", "100"), Art.art(2, "SUBE2", "100"), Art.art(3, "BAJA", "100"),
                    Art.art(4, "IGUAL", "100"), Art.art(5, "AUSENTE", "100"))
                .filas(
                    fila(2, "SUBE", "x", "110"), fila(3, "SUBE2", "x", "120"), fila(4, "BAJA", "x", "90"),
                    fila(5, "IGUAL", "x", "100"), fila(6, "", "x", "100"))
                .planificar();

            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(2, 1, 0, 1, 1, 1));
            assertThat(plan.cambios()).hasSize(3);
            assertThat(plan.sinCambio()).hasSize(1);
            assertThat(plan.errores()).hasSize(1);
            assertThat(plan.noEstan()).hasSize(1);
        }

        @Test
        @DisplayName("los errores quedan ordenados por numero de fila")
        void erroresOrdenados() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "A-1", "x", "abc"), fila(3, "B-1", "x", "1"), fila(4, "B-1", "x", "1"),
                    fila(5, "", "x", "9"))
                .planificar();

            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(2, 3, 4, 5);
        }
    }

    // ------------------------------------------------------------------ T-18

    @Nested
    @DisplayName("T-18: IVA, bonificaciones, precio nuevo y variacion")
    class IvaBonificacionesYVariacion {

        @Test
        @DisplayName("lista con IVA incluido y bonificacion: 1210 con 40 % da costo 600.00; el precio de lista queda crudo")
        void ivaIncluidoConBonificacion() {
            PlanImportacion plan = escenario()
                .incluyeIva()
                .bonificaciones("40", null, null)
                .articulos(Art.art(1, "A-1", "500"))
                .filas(fila(2, "A-1", "x", "1210"))
                .planificar();

            CambioPlaneado cambio = plan.cambios().get(0);
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("600.00");
            assertThat(cambio.costoNuevo().scale()).isEqualTo(2);
            assertThat(cambio.precioLista()).isEqualByComparingTo("1210");
        }

        @Test
        @DisplayName("usa la alicuota de CADA articulo: 121 da 100.00 con IVA_21 y 109.50 con IVA_10_5")
        void alicuotaDeCadaArticulo() {
            PlanImportacion plan = escenario()
                .incluyeIva()
                .articulos(Art.art(1, "A-1", "50"), Art.art(2, "A-2", "50").iva(AlicuotaIva.IVA_10_5))
                .filas(fila(2, "A-1", "x", "121"), fila(3, "A-2", "x", "121"))
                .planificar();

            assertThat(plan.cambios()).extracting(CambioPlaneado::costoNuevo)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("100.00"), bd("109.50"));
        }

        @Test
        @DisplayName("1105 con IVA_10_5 incluido y bonificacion 10 da 900.00")
        void iva105ConBonificacion() {
            PlanImportacion plan = escenario()
                .incluyeIva()
                .bonificaciones("10", null, null)
                .articulos(Art.art(1, "A-1", "500").iva(AlicuotaIva.IVA_10_5))
                .filas(fila(2, "A-1", "x", "1105"))
                .planificar();

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("900.00");
        }

        @Test
        @DisplayName("sin incluyeIva el precio de lista se usa tal cual (121 queda 121.00)")
        void sinIncluirIva() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "50"))
                .filas(fila(2, "A-1", "x", "121"))
                .planificar();

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("121.00");
        }

        @Test
        @DisplayName("costo actual 540 y lista 1000 con 40+10: el costo calculado es igual, sin cambio")
        void bonificacionesDejanSinCambio() {
            PlanImportacion plan = escenario()
                .bonificaciones("40", "10", null)
                .articulos(Art.art(1, "A-1", "540.00"))
                .filas(fila(2, "A-1", "x", "1000"))
                .planificar();

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.sinCambio()).hasSize(1);
            assertThat(plan.sinCambio().get(0).precioLista()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("redondeo unico: 10,05 con 10 y 10 da 8.14 y no 8.15")
        void redondeoUnico() {
            PlanImportacion plan = escenario()
                .bonificaciones("10", "10", null)
                .articulos(Art.art(1, "A-1", "1"))
                .filas(fila(2, "A-1", "x", "10,05"))
                .planificar();

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("8.14");
        }

        @Test
        @DisplayName("el IVA de cada articulo tambien decide si el costo calculado es cero")
        void ceroDependeDeLaAlicuota() {
            // 0,005 con IVA_0 incluido da 0.01 (HALF_UP); con IVA_21 daria 0.00.
            PlanImportacion plan = escenario()
                .incluyeIva()
                .articulos(Art.art(1, "A-1", "5").iva(AlicuotaIva.IVA_0), Art.art(2, "A-2", "5"))
                .filas(fila(2, "A-1", "x", "0,005"), fila(3, "A-2", "x", "0,005"))
                .planificar();

            assertThat(plan.cambios()).extracting(CambioPlaneado::articuloId).containsExactly(1L);
            assertThat(plan.errores()).extracting(AvisoDeFila::fila).containsExactly(3);
        }

        @Test
        @DisplayName("precioNuevo con el margen del rubro: costo 100 a 120, margen 35 % y multiplo 10 da 200.00")
        void precioNuevoConRubro() {
            PlanImportacion plan = escenario()
                .contextoRubro(5, "35.00")
                .articulos(Art.art(1, "A-1", "100").rubro(5).precio("170.00"))
                .filas(fila(2, "A-1", "x", "120"))
                .planificar();

            CambioPlaneado cambio = plan.cambios().get(0);
            assertThat(cambio.precioAnterior()).isEqualByComparingTo("170.00");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("el margen propio del articulo gana: 50 % da 220.00")
        void precioNuevoConMargenPropio() {
            PlanImportacion plan = escenario()
                .contextoRubro(5, "35.00")
                .articulos(Art.art(1, "A-1", "100").rubro(5).margen("50.00"))
                .filas(fila(2, "A-1", "x", "120"))
                .planificar();

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("220.00");
        }

        @Test
        @DisplayName("sin rubro usa el margen por defecto de la ferreteria: 120 * 1,30 * 1,21 = 188,76 sube a 190.00")
        void precioNuevoSinRubro() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "120"))
                .planificar();

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("190.00");
        }

        @Test
        @DisplayName("un cambio de costo que deja el mismo precio sigue siendo un cambio: 100,50 da 170.00")
        void costoCambiaPrecioIgual() {
            PlanImportacion plan = escenario()
                .contextoRubro(5, "35.00")
                .articulos(Art.art(1, "A-1", "100").rubro(5).precio("170.00"))
                .filas(fila(2, "A-1", "x", "100,50"))
                .planificar();

            CambioPlaneado cambio = plan.cambios().get(0);
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("100.50");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("170.00");
            assertThat(plan.conteos().suben()).isEqualTo(1);
        }

        @Test
        @DisplayName("precio nuevo con bonificaciones: 1000 con 40+10 sobre costo 400 da costo 540.00 y precio 890.00")
        void precioNuevoConBonificaciones() {
            PlanImportacion plan = escenario()
                .bonificaciones("40", "10", null)
                .contextoRubro(5, "35.00")
                .articulos(Art.art(1, "A-1", "400").rubro(5))
                .filas(fila(2, "A-1", "x", "1000"))
                .planificar();

            CambioPlaneado cambio = plan.cambios().get(0);
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("540.00");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("890.00");
        }

        @Test
        @DisplayName("baja: 80 con margen 35 % da 140.00")
        void precioNuevoBaja() {
            PlanImportacion plan = escenario()
                .contextoRubro(5, "35.00")
                .articulos(Art.art(1, "A-1", "100").rubro(5))
                .filas(fila(2, "A-1", "x", "80"))
                .planificar();

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("140.00");
        }

        @Test
        @DisplayName("un articulo con un rubro sin contexto es un error de programacion: falla en voz alta")
        void rubroSinContexto() {
            Escenario escenario = escenario()
                .articulos(Art.art(1, "A-1", "100").rubro(99))
                .filas(fila(2, "A-1", "x", "120"));

            org.assertj.core.api.Assertions.assertThatThrownBy(escenario::planificar)
                .isInstanceOf(IllegalStateException.class);
        }

        // ---- variacion mayor al umbral

        @Test
        @DisplayName("el umbral es una constante del planificador: 50")
        void umbral() {
            assertThat(PlanificadorImportacion.UMBRAL_VARIACION_PORCENTAJE).isEqualByComparingTo("50");
        }

        @Test
        @DisplayName("sube 60 %: advertencia, variacionFuerte y la fila sigue contando como sube")
        void suba60() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(5, "A-1", "x", "160"))
                .planificar();

            assertThat(plan.advertencias()).containsExactly(
                new AvisoDeFila(5, "A-1", "El costo sube un 60 % (de 100,00 a 160,00). Revisá que sea correcto."));
            assertThat(plan.cambios().get(0).variacionFuerte()).isTrue();
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("baja 60 %: advertencia y la fila sigue contando como baja")
        void baja60() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(5, "A-1", "x", "40"))
                .planificar();

            assertThat(plan.advertencias()).containsExactly(
                new AvisoDeFila(5, "A-1", "El costo baja un 60 % (de 100,00 a 40,00). Revisá que sea correcto."));
            assertThat(plan.cambios().get(0).variacionFuerte()).isTrue();
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 1, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("exactamente +50 % y -50 % no advierten")
        void exactamenteCincuenta() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(fila(2, "A-1", "x", "150"), fila(3, "A-2", "x", "50"))
                .planificar();

            assertThat(plan.advertencias()).isEmpty();
            assertThat(plan.cambios()).extracting(CambioPlaneado::variacionFuerte).containsOnly(false);
        }

        @Test
        @DisplayName("150,01 (+50,01 %) y 49,99 (-50,01 %) si advierten")
        void apenasPasaElUmbral() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(fila(2, "A-1", "x", "150,01"), fila(3, "A-2", "x", "49,99"))
                .planificar();

            assertThat(mensajes(plan.advertencias())).containsExactly(
                "El costo sube un 50,01 % (de 100,00 a 150,01). Revisá que sea correcto.",
                "El costo baja un 50,01 % (de 100,00 a 49,99). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("la variacion se mide sobre el costo calculado, no sobre la lista: 2000 con 90 % sobre 100 da +100 %")
        void variacionSobreCostoCalculado() {
            PlanImportacion plan = escenario()
                .bonificaciones("90", null, null)
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "2000"))
                .planificar();

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("200.00");
            assertThat(mensajes(plan.advertencias())).containsExactly(
                "El costo sube un 100 % (de 100,00 a 200,00). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("costo actual 0: no hay advertencia aunque sube")
        void costoActualCeroNoAdvierte() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "0.00"))
                .filas(fila(2, "A-1", "x", "50"))
                .planificar();

            assertThat(plan.advertencias()).isEmpty();
            assertThat(plan.cambios().get(0).variacionFuerte()).isFalse();
        }

        @Test
        @DisplayName("una fila con error o sin cambio no genera advertencia de variacion")
        void erroresYSinCambioNoAdvierten() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"), Art.art(3, "A-3", "100"))
                .filas(fila(2, "A-1", "x", "abc"), fila(3, "A-2", "x", "100"), fila(4, "A-3", "x", "0"))
                .planificar();

            assertThat(plan.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("el porcentaje se muestra con hasta 2 decimales: 100 a 33,33 baja un 66,67 %")
        void porcentajeConDecimales() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "33,33"))
                .planificar();

            assertThat(mensajes(plan.advertencias())).containsExactly(
                "El costo baja un 66,67 % (de 100,00 a 33,33). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("los importes usan punto de miles y coma decimal: 1000 a 2500,50 sube un 150,05 %")
        void importesConMiles() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "1000"))
                .filas(fila(2, "A-1", "x", "2500,50"))
                .planificar();

            assertThat(mensajes(plan.advertencias())).containsExactly(
                "El costo sube un 150,05 % (de 1.000,00 a 2.500,50). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("un articulo inactivo tambien advierte (se trata igual)")
        void inactivoAdvierte() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100").inactivo())
                .filas(fila(2, "A-1", "x", "300"))
                .planificar();

            assertThat(plan.advertencias()).hasSize(1);
        }

        @Test
        @DisplayName("las advertencias salen ordenadas por fila")
        void advertenciasOrdenadas() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(fila(2, "A-1", "x", "300"), fila(3, "A-2", "x", "300"))
                .planificar();

            assertThat(plan.advertencias()).extracting(AvisoDeFila::fila).containsExactly(2, 3);
        }
    }

    // ------------------------------------------------------------------ T-19

    @Nested
    @DisplayName("T-19: candidatas a articulo nuevo")
    class CandidatasANuevo {

        @Test
        @DisplayName("sin coincidencia es un alta con los datos de la fila y el precio de lista crudo")
        void altaBasica() {
            PlanImportacion plan = escenario()
                .filas(fila(6, " TOL-100 ", "  Tornillo 1/4 ", "1.234,56"))
                .planificar();

            assertThat(plan.altas()).hasSize(1);
            AltaPlaneada alta = plan.altas().get(0);
            assertThat(alta.fila()).isEqualTo(6);
            assertThat(alta.codigo()).isEqualTo("TOL-100");
            assertThat(alta.descripcion()).isEqualTo("Tornillo 1/4");
            assertThat(alta.precioLista()).isEqualByComparingTo("1234.56");
            assertThat(alta.costo()).isEqualByComparingTo("1234.56");
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 1, 0, 0, 0));
        }

        @Test
        @DisplayName("el costo del alta usa IVA_21: 1210 con IVA incluido y bonificacion 40 da 600.00")
        void altaConIvaYBonificacion() {
            PlanImportacion plan = escenario()
                .incluyeIva()
                .bonificaciones("40", null, null)
                .filas(fila(2, "N-1", "Nuevo", "1210"))
                .planificar();

            AltaPlaneada alta = plan.altas().get(0);
            assertThat(alta.costo()).isEqualByComparingTo("600.00");
            assertThat(alta.costo().scale()).isEqualTo(2);
            assertThat(alta.precioLista()).isEqualByComparingTo("1210");
        }

        @Test
        @DisplayName("lista 100 con bonificacion 10 da costo 90.00")
        void altaConBonificacion() {
            PlanImportacion plan = escenario()
                .bonificaciones("10", null, null)
                .filas(fila(2, "N-1", "Nuevo", "100"))
                .planificar();

            assertThat(plan.altas().get(0).costo()).isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("lista 121 con IVA incluido sin bonificaciones da costo 100.00")
        void altaConIva() {
            PlanImportacion plan = escenario().incluyeIva().filas(fila(2, "N-1", "Nuevo", "121")).planificar();

            assertThat(plan.altas().get(0).costo()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("un codigo de mas de 30 caracteres no se puede crear; uno de exactamente 30 si")
        void codigoDeMasDe30() {
            String de31 = "B".repeat(31);

            PlanImportacion plan = escenario()
                .filas(fila(2, de31, "Algo", "10"), fila(3, "B".repeat(30), "Algo", "10"))
                .planificar();

            assertThat(plan.errores()).containsExactly(new AvisoDeFila(2, de31,
                "No se puede crear como artículo nuevo: el código tiene más de 30 caracteres."));
            assertThat(plan.altas()).extracting(AltaPlaneada::fila).containsExactly(3);
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 1, 0, 0, 1));
        }

        @Test
        @DisplayName("un codigo interno ya ocupado (sin distinguir mayusculas) no se puede crear")
        void codigoInternoOcupado() {
            PlanImportacion plan = escenario()
                .ocupados("VALV-1")
                .filas(fila(2, "valv-1", "Valvula", "10"), fila(3, "VALV-2", "Valvula", "10"))
                .planificar();

            assertThat(plan.errores()).containsExactly(new AvisoDeFila(2, "valv-1",
                "No se puede crear como artículo nuevo: ya existe un artículo con el código interno valv-1."));
            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("VALV-2");
        }

        @Test
        @DisplayName("el codigo interno ocupado se compara con la clave: acentos y espacios no cuentan")
        void ocupadoConAcentos() {
            PlanImportacion plan = escenario()
                .ocupados("TEE-1")
                .filas(fila(2, "Tée-1", "Te", "10"))
                .planificar();

            assertThat(plan.altas()).isEmpty();
            assertThat(plan.conteos().conError()).isEqualTo(1);
        }

        @Test
        @DisplayName("sin descripcion (en blanco o sin columna mapeada) no se puede crear")
        void faltaLaDescripcion() {
            String mensaje = "No se puede crear como artículo nuevo: falta la descripción.";

            PlanImportacion enBlanco = escenario()
                .filas(fila(2, "N-1", "   ", "10"), fila(3, "N-2", null, "10"))
                .planificar();
            PlanImportacion sinColumna = escenario()
                .mapeo(new MapeoLista(0, 2, 0, null, 2, null))
                .filas(fila(2, "N-1", "Tiene descripcion", "10"))
                .planificar();

            assertThat(enBlanco.errores()).containsExactly(
                new AvisoDeFila(2, "N-1", mensaje), new AvisoDeFila(3, "N-2", mensaje));
            assertThat(sinColumna.errores()).containsExactly(new AvisoDeFila(2, "N-1", mensaje));
            assertThat(enBlanco.altas()).isEmpty();
            assertThat(sinColumna.altas()).isEmpty();
        }

        @Test
        @DisplayName("una descripcion de mas de 200 caracteres se corta a 200 con advertencia y la fila se crea")
        void descripcionLarga() {
            String descripcion = "d".repeat(250);

            PlanImportacion plan = escenario().filas(fila(4, "N-1", descripcion, "10")).planificar();

            assertThat(plan.altas().get(0).descripcion()).isEqualTo("d".repeat(200));
            assertThat(plan.advertencias()).containsExactly(new AvisoDeFila(4, "N-1",
                "La descripción tenía más de 200 caracteres y se cortó a 200."));
            assertThat(plan.errores()).isEmpty();
        }

        @Test
        @DisplayName("exactamente 200 caracteres no se corta ni advierte")
        void descripcionDe200() {
            PlanImportacion plan = escenario().filas(fila(4, "N-1", "d".repeat(200), "10")).planificar();

            assertThat(plan.altas().get(0).descripcion()).hasSize(200);
            assertThat(plan.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("el corte respeta el limite de code point: no parte un par sustituto")
        void corteEnCodePoint() {
            String emoji = "😀";

            PlanImportacion plan = escenario().filas(fila(2, "N-1", emoji.repeat(201), "10")).planificar();

            String cortada = plan.altas().get(0).descripcion();
            assertThat(cortada.codePointCount(0, cortada.length())).isEqualTo(200);
            assertThat(cortada).isEqualTo(emoji.repeat(200));
        }

        @Test
        @DisplayName("el error de costo tiene prioridad: una fila nueva con costo 0 se informa solo por el costo")
        void costoGanaSobreNoCreable() {
            PlanImportacion plan = escenario()
                .ocupados("N-1")
                .filas(fila(2, "N-1", "", "0"), fila(3, "C".repeat(35), null, "0"), fila(4, "N-3", "", "abc"))
                .planificar();

            assertThat(mensajes(plan.errores())).containsExactly(
                "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.",
                "El costo calculado es 0. Revisá el precio de lista y las bonificaciones.",
                "El costo está vacío o no es un número.");
            assertThat(plan.altas()).isEmpty();
        }

        @Test
        @DisplayName("una fila nueva no genera advertencia de variacion, tenga el costo que tenga")
        void nuevaNoAdvierteVariacion() {
            PlanImportacion plan = escenario().filas(fila(2, "N-1", "Nuevo", "9999999")).planificar();

            assertThat(plan.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("una fila duplicada no genera alta aunque sea creable")
        void duplicadaNoGeneraAlta() {
            PlanImportacion plan = escenario()
                .filas(fila(2, "N-1", "Nuevo", "10"), fila(3, "n-1", "Nuevo", "10"), fila(4, "N-2", "Otro", "10"))
                .planificar();

            assertThat(plan.altas()).extracting(AltaPlaneada::codigo).containsExactly("N-2");
        }

        @Test
        @DisplayName("un articulo nuevo no cambia ni los suben ni los noEstan")
        void conteoDeNuevos() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"))
                .filas(fila(2, "A-1", "x", "110"), fila(3, "N-1", "Nuevo", "10"), fila(4, "N-2", "Otro", "20"))
                .planificar();

            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(1, 0, 2, 0, 1, 0));
        }

        @Test
        @DisplayName("el mismo input da siempre el mismo plan (determinismo)")
        void determinismo() {
            Escenario escenario = escenario()
                .incluyeIva()
                .bonificaciones("40", "10", null)
                .ocupados("OCU-1")
                .articulos(Art.art(1, "A-1", "100"), Art.art(2, "A-2", "100"), Art.art(3, "A-3", "100").inactivo())
                .filas(
                    fila(2, "A-1", "x", "1210"), fila(3, "A-2", "x", "abc"), fila(4, "N-1", "Nuevo", "500"),
                    fila(5, "OCU-1", "Otro", "10"), fila(6, "N-1", "Nuevo", "500"), fila(7, "A-3", "x", "3000"),
                    fila(8, "N-9", "d".repeat(300), "100"));

            assertThat(escenario.planificar()).isEqualTo(escenario.planificar());
        }

        // ---- advertencias de encabezado

        @Test
        @DisplayName("las advertencias de encabezado salen de MapeoLista.advertenciasContra, sin fila ni codigo, primero")
        void advertenciasDeEncabezado() {
            MapeoLista guardado = new MapeoLista(0, 2, 0, 1, 2,
                new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));

            PlanImportacion plan = escenario()
                .guardado(guardado, fila(1, "Codigo", "Detalle", "Costo"))
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "300"))
                .planificar();

            assertThat(plan.advertencias()).hasSize(2);
            assertThat(plan.advertencias().get(0)).isEqualTo(new AvisoDeFila(null, null,
                "El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «Costo». Revisá el mapeo."));
            assertThat(plan.advertencias().get(1).fila()).isEqualTo(2);
        }

        @Test
        @DisplayName("con los nombres de hoja conocidos la advertencia de encabezado lleva la hoja del mapeo")
        void advertenciaDeEncabezadoConHoja() {
            MapeoLista guardado = new MapeoLista(0, 2, 0, 1, 2,
                new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));

            PlanImportacion plan = escenario().hojas("Lista")
                .guardado(guardado, fila(1, "Codigo", "Detalle", "Costo"))
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "100"))
                .planificar();

            assertThat(plan.advertencias()).containsExactly(new AvisoDeFila(0, null, null,
                "El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «Costo». Revisá el mapeo."));
        }

        @Test
        @DisplayName("sin mapeo guardado o sin fila de encabezado no hay advertencias de encabezado")
        void sinAdvertenciasDeEncabezado() {
            MapeoLista guardado = new MapeoLista(0, 2, 0, 1, 2,
                new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));

            PlanImportacion sinGuardado = escenario()
                .guardado(null, fila(1, "Codigo", "Detalle", "Costo"))
                .planificar();
            PlanImportacion sinEncabezado = escenario().guardado(guardado, null).planificar();

            assertThat(sinGuardado.advertencias()).isEmpty();
            assertThat(sinEncabezado.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("el cambio de encabezado no bloquea: las filas se procesan igual")
        void encabezadoNoBloquea() {
            MapeoLista guardado = new MapeoLista(0, 2, 0, 1, 2,
                new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));

            PlanImportacion plan = escenario()
                .guardado(guardado, fila(1, "Codigo", "Detalle", "Costo"))
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "110"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.errores()).isEmpty();
        }

        // ---- codigosCandidatos

        @Test
        @DisplayName("codigosCandidatos devuelve los codigos con trim de 30 caracteres o menos desde filaDesde")
        void codigosCandidatos() {
            List<FilaLeida> filas = List.of(
                fila(1, "Codigo", "Detalle", "Precio"),
                fila(2, "  A-1 ", "x", "10"),
                fila(3, "B".repeat(31), "x", "10"),
                fila(4, "C".repeat(30), "x", "10"),
                fila(5, "", "x", "10"),
                fila(6, "a-1", "x", "10"),
                fila(7, "D-4", "x", "abc"));

            List<String> codigos = PlanificadorImportacion.codigosCandidatos(filas, MAPEO);

            assertThat(codigos).containsExactly("A-1", "C".repeat(30), "D-4");
        }

        @Test
        @DisplayName("codigosCandidatos cuenta en code points")
        void codigosCandidatosCodePoints() {
            String de30 = "😀".repeat(30);
            String de31 = "😀".repeat(31);

            List<String> codigos = PlanificadorImportacion.codigosCandidatos(
                List.of(fila(2, de30, "x", "10"), fila(3, de31, "x", "10")), MAPEO);

            assertThat(codigos).containsExactly(de30);
        }
    }

    // ------------------------------------------------------------------ precio guardado desactualizado (revision lotes 4 y 5)

    @Nested
    @DisplayName("Revision 4-5, arreglo 1: el precio guardado desactualizado es 'Corrige', no 'sin cambio'")
    class PrecioDesactualizado {

        @Test
        @DisplayName("mismo costo, IVA y ajustes pero el precio guardado (1,00) difiere del que da la lista (1.580): corrige")
        void precioDesactualizadoCorrige() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "1000").precio("1.00"))
                .filas(fila(2, "A-1", "x", "1000"))
                .planificar();

            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.correcciones()).hasSize(1);
            CambioPlaneado corrige = plan.correcciones().get(0);
            assertThat(corrige.precioAnterior()).isEqualByComparingTo("1.00");
            assertThat(corrige.precioNuevo()).isEqualByComparingTo("1580.00");
            assertThat(plan.conteos()).isEqualTo(new ConteosImportacion(0, 0, 0, 0, 0, 0, 0, 1, 0));
        }

        @Test
        @DisplayName("mismo costo y precio guardado al dia (1.580): sigue siendo sin cambio")
        void precioAlDiaEsSinCambio() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "1000").precio("1580.00"))
                .filas(fila(2, "A-1", "x", "1000"))
                .planificar();

            assertThat(plan.correcciones()).isEmpty();
            assertThat(plan.sinCambio()).hasSize(1);
            assertThat(plan.conteos().corrigen()).isZero();
            assertThat(plan.conteos().sinCambio()).isEqualTo(1);
        }

        @Test
        @DisplayName("precio desactualizado y ademas otra alicuota: corrige, con la alicuota nueva")
        void precioDesactualizadoYOtroIva() {
            PlanImportacion plan = escenario().mapeo(M.mapeo().iva().build())
                .articulos(Art.art(1, "A-1", "1000").precio("1.00"))
                .filas(filaDe(2, "A-1", "x", "1000", "", "", "", "10,5"))
                .planificar();

            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.correcciones()).hasSize(1);
            assertThat(plan.correcciones().get(0).alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
        }

        @Test
        @DisplayName("articulo en dolares con el precio en pesos viejo: corrige con el precio del dolar vigente")
        void enDolaresDesactualizado() {
            // 10 x 1,30 x 1,21 = 15,73 USD x 1.000 = 15.730 pesos.
            PlanImportacion plan = escenario().dolar("1000")
                .articulos(Art.art(1, "U-1", "10").enDolares().precio("14000.00"))
                .filas(fila(2, "U-1", "x", "10"))
                .planificar();

            assertThat(plan.sinCambio()).isEmpty();
            assertThat(plan.correcciones()).hasSize(1);
            assertThat(plan.correcciones().get(0).precioNuevo()).isEqualByComparingTo("15730.00");
        }
    }

    // ------------------------------------------------------------------ rubro elegido (revision lotes 4 y 5)

    @Nested
    @DisplayName("Revision 4-5, arreglo 2: los nuevos sin rubro propio usan el rubro elegido para su precio")
    class RubroElegido {

        @Test
        @DisplayName("sin rubro elegido el nuevo se calcula con el margen por defecto (30): 100 da 160")
        void sinRubroElegido() {
            PlanImportacion plan = escenario().contextoRubro(5, "40")
                .filas(fila(2, "N-1", "Nuevo", "100"))
                .planificar();

            assertThat(plan.altas().get(0).precioNuevo()).isEqualByComparingTo("160");
            assertThat(plan.altas().get(0).rubroId()).isNull();
        }

        @Test
        @DisplayName("con el rubro 5 elegido (margen 40) el nuevo sin rubro propio da 170, y su rubroId sigue siendo el de la fila (null)")
        void conRubroElegido() {
            PlanImportacion plan = escenario().contextoRubro(5, "40").rubroElegido(5)
                .filas(fila(2, "N-1", "Nuevo", "100"))
                .planificar();

            assertThat(plan.altas().get(0).precioNuevo()).isEqualByComparingTo("170");
            assertThat(plan.altas().get(0).rubroId()).isNull();
        }

        @Test
        @DisplayName("el rubro propio de la fila gana sobre el elegido: rubro 6 (margen 50) da 190 aunque se eligio el 5")
        void ganaElDeLaFila() {
            PlanImportacion plan = escenario().contextoRubro(5, "40").contextoRubro(6, "50")
                .rubroPorNombre("Bulones", 6).rubroElegido(5).mapeo(M.mapeo().rubro().build())
                .filas(filaDe(2, "N-1", "Nuevo", "100", "", "", "", "", "", "", "", "", "", "Bulones"),
                    filaDe(3, "N-2", "Nuevo", "100"))
                .planificar();

            assertThat(plan.altas()).hasSize(2);
            assertThat(plan.altas().get(0).rubroId()).isEqualTo(6L);
            assertThat(plan.altas().get(0).precioNuevo()).isEqualByComparingTo("190");
            assertThat(plan.altas().get(1).precioNuevo()).isEqualByComparingTo("170");
        }

        @Test
        @DisplayName("un rubro elegido sin contexto cae al de los articulos sin rubro, como el rubro propio de la fila")
        void rubroElegidoSinContexto() {
            PlanImportacion plan = escenario().rubroElegido(99)
                .filas(fila(2, "N-1", "Nuevo", "100"))
                .planificar();

            assertThat(plan.altas().get(0).precioNuevo()).isEqualByComparingTo("160");
        }

        @Test
        @DisplayName("el rubro elegido no cambia la clasificacion ni el precio de los articulos existentes")
        void noTocaALosExistentes() {
            PlanImportacion plan = escenario().contextoRubro(5, "40").rubroElegido(5)
                .articulos(Art.art(1, "A-1", "100"))
                .filas(fila(2, "A-1", "x", "120"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            // Sin rubro propio: 120 x 1,30 x 1,21 = 188,76 -> 190.
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("190");
        }
    }

    // ------------------------------------------------------------------ candidatos para las consultas del servicio (T-134)

    @Nested
    @DisplayName("T-134: codigos candidatos de la zona de datos")
    class CandidatosDeLaZona {

        @Test
        @DisplayName("los codigos de la zona no miran filaDesde: la hoja que arranca antes tambien aporta los suyos")
        void sinFilaDesde() {
            List<FilaLeida> zona = List.of(
                filaEnHoja(0, 4, "A-1", "x", "10"), filaEnHoja(1, 2, "B-1", "x", "10"),
                filaEnHoja(1, 3, " a-1 ", "x", "10"), filaEnHoja(1, 4, "C".repeat(31), "x", "10"),
                filaEnHoja(1, 5, "", "x", "10"));

            List<String> codigos = PlanificadorImportacion.codigosCandidatosDeLaZona(zona, MAPEO);

            assertThat(codigos).containsExactly("A-1", "B-1");
        }

        @Test
        @DisplayName("los codigos de barras salen sin repetir, recortados y en el orden del archivo; sin columna no hay ninguno")
        void codigosDeBarras() {
            MapeoLista conBarras = M.mapeo().barras().build();
            List<FilaLeida> filas = List.of(
                filaDe(2, "N-1", "x", "10", "", "", "", "", "", "", "", " 7791234567890 "),
                filaDe(3, "N-2", "x", "10", "", "", "", "", "", "", "", ""),
                filaDe(4, "N-3", "x", "10", "", "", "", "", "", "", "", "7790000000001"),
                filaDe(5, "N-4", "x", "10", "", "", "", "", "", "", "", "7791234567890"),
                filaDe(6, "N-5", "x", "10", "", "", "", "", "", "", "", "9".repeat(31)));

            assertThat(PlanificadorImportacion.codigosDeBarras(filas, conBarras))
                .containsExactly("7791234567890", "7790000000001");
            assertThat(PlanificadorImportacion.codigosDeBarras(filas, MAPEO)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ precio manual y ajustes (CU-14)

    @Nested
    @DisplayName("CU-14: precio manual y ajustes sobre el precio final")
    class PrecioManualYAjustes {

        private static final String AJUSTES_EN_CERO = "Con los ajustes del precio final, el precio queda en 0 o menos.";

        @Test
        @DisplayName("costo 100 a 110 con precio manual: sube el costo y el precio queda el escrito (500)")
        void precioManualNoCambia() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100").precio("500.00").manual())
                .filas(fila(2, "A-1", "x", "110"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("110");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("mismo costo con precio manual distinto del calculado: sin cambio, no es una correccion")
        void precioManualMismoCostoEsSinCambio() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100").precio("500.00").manual())
                .filas(fila(2, "A-1", "x", "100"))
                .planificar();

            assertThat(plan.correcciones()).isEmpty();
            assertThat(plan.sinCambio()).hasSize(1);
        }

        @Test
        @DisplayName("costo 100 a 110 con un ajuste de +10 %: 110 x 1,3 x 1,21 = 180 (multiplo 10), +10 % = 200")
        void ajustesSeAplican() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100")
                    .ajustesPrecio(new AjustePrecio(null, TipoAjustePrecio.PORCENTAJE, bd("10"))))
                .filas(fila(2, "A-1", "x", "110"))
                .planificar();

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).precioAnterior()).isEqualByComparingTo("180");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("200");
        }

        @Test
        @DisplayName("ajustes que dejan el precio en 0 o menos: la fila va a errores")
        void ajustesQueDejanElPrecioEnCero() {
            PlanImportacion plan = escenario()
                .articulos(Art.art(1, "A-1", "100").precio("10.00")
                    .ajustesPrecio(new AjustePrecio(null, TipoAjustePrecio.MONTO, bd("-200"))))
                .filas(fila(2, "A-1", "x", "110"))
                .planificar();

            assertThat(plan.cambios()).isEmpty();
            assertThat(mensajes(plan.errores())).containsExactly(AJUSTES_EN_CERO);
        }
    }
}
