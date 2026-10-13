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

    /** Articulo vigente de prueba: IVA_21, activo, sin rubro ni margen propio. */
    private static final class Art {
        private final long id;
        private final String codigoProveedor;
        private final String costo;
        private AlicuotaIva iva = AlicuotaIva.IVA_21;
        private boolean activo = true;
        private String margen;
        private Long rubroId;
        private String precioFinal = "0.00";

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

        ArticuloVigente vigente() {
            return new ArticuloVigente(id, "INT-" + id, codigoProveedor, "Articulo " + id, bd(costo), bd(precioFinal),
                margen == null ? null : bd(margen), rubroId, iva, activo);
        }
    }

    /** Arma una EntradaPlan con valores por defecto razonables y permite cambiarlos. */
    private static final class Escenario {
        private final List<FilaLeida> filas = new ArrayList<>();
        private final List<ArticuloVigente> articulos = new ArrayList<>();
        private final Map<Long, ContextoPrecio> contextoPorRubro = new HashMap<>();
        private final Set<String> codigosInternosOcupados = new HashSet<>();
        private ContextoPrecio contextoSinRubro = new ContextoPrecio(null, bd("30.00"), bd("10"));
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
            for (Art valor : valores) {
                articulos.add(valor.vigente());
            }
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
            contextoPorRubro.put(rubroId, new ContextoPrecio(bd(margenRubro), bd("30.00"), bd("10")));
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

        PlanImportacion planificar() {
            return PlanificadorImportacion.planificar(new EntradaPlan(
                filas, mapeo, incluyeIva, bonificaciones, articulos, contextoPorRubro, contextoSinRubro,
                codigosInternosOcupados, mapeoGuardado, encabezado));
        }
    }

    private static Escenario escenario() {
        return new Escenario();
    }

    private static List<String> mensajes(List<AvisoDeFila> avisos) {
        return avisos.stream().map(AvisoDeFila::mensaje).toList();
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
}
