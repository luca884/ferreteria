package com.ferreteria.catalogo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.ModoMasivo;
import com.ferreteria.catalogo.model.OperacionMasiva;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validacion de los pedidos de la actualizacion masiva (CU-05) con el Validator real y el grupo del
 * servicio, sin Spring. Las dos solicitudes (vista previa y aplicar) validan igual el modo y el valor.
 */
class ActualizacionMasivaRequestTest {

    private static final String MENSAJE_MODO =
            "Elegí qué querés hacer: cambiar el costo en porcentaje o fijar el margen.";
    private static final String MENSAJE_VALOR_AUSENTE = "Ingresá un valor.";
    private static final String MENSAJE_CERO = "El porcentaje no puede ser cero.";
    private static final String MENSAJE_RANGO_PORCENTAJE = "El porcentaje tiene que estar entre -99,99 y 1.000.";
    private static final String MENSAJE_RANGO_MARGEN = "El margen tiene que estar entre 0 y 1.000.";
    private static final String MENSAJE_DECIMALES = "El valor puede tener como máximo 2 decimales.";
    private static final String MENSAJE_HUELLA = "Falta la vista previa. Generala de nuevo antes de confirmar.";

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void iniciar() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    private static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    private static ActualizacionMasivaRequest vistaPrevia(String modo, String valor) {
        return new ActualizacionMasivaRequest(null, null, null, false, modo, bd(valor));
    }

    private static AplicarActualizacionMasivaRequest aplicar(String modo, String valor, String huella) {
        return new AplicarActualizacionMasivaRequest(null, null, null, false, modo, bd(valor), huella);
    }

    /** Campo (con ruta) a mensaje, del grupo del servicio. Si un campo tiene varios, queda el primero alfabetico. */
    private static Map<String, String> errores(Object pedido) {
        Set<ConstraintViolation<Object>> violaciones =
                validador.validate(pedido, ValidacionActualizacionMasiva.class);
        Map<String, String> errores = new TreeMap<>();
        for (ConstraintViolation<Object> v : violaciones) {
            errores.merge(v.getPropertyPath().toString(), v.getMessage(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return errores;
    }

    @Nested
    @DisplayName("modo COSTO_PORCENTAJE")
    class CostoPorcentaje {

        @ParameterizedTest(name = "{0} es valido")
        @ValueSource(strings = {"10", "-20", "0.01", "-0.01", "-99.99", "1000", "1000.00", "12.5", "50.00"})
        void valoresValidos(String valor) {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", valor))).isEmpty();
            assertThat(errores(aplicar("COSTO_PORCENTAJE", valor, "abc"))).isEmpty();
        }

        @ParameterizedTest(name = "{0} es cero")
        @ValueSource(strings = {"0", "0.00", "0.0"})
        void ceroNoEsValido(String valor) {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", valor)))
                    .containsOnly(Map.entry("porcentajeDistintoDeCero", MENSAJE_CERO));
            assertThat(errores(aplicar("COSTO_PORCENTAJE", valor, "abc")))
                    .containsOnly(Map.entry("porcentajeDistintoDeCero", MENSAJE_CERO));
        }

        @ParameterizedTest(name = "{0} esta fuera de rango")
        @ValueSource(strings = {"-100", "-100.00", "1000.01", "5000", "12345", "-250"})
        void fueraDeRango(String valor) {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", valor)))
                    .containsOnly(Map.entry("porcentajeEnRango", MENSAJE_RANGO_PORCENTAJE));
            assertThat(errores(aplicar("COSTO_PORCENTAJE", valor, "abc")))
                    .containsOnly(Map.entry("porcentajeEnRango", MENSAJE_RANGO_PORCENTAJE));
        }

        @Test
        @DisplayName("el limite -99.99 es valido y -99.995 solo se queja de los decimales")
        void limiteInferior() {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", "-99.99"))).isEmpty();
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", "-99.995")))
                    .containsOnly(Map.entry("valor", MENSAJE_DECIMALES));
        }

        @Test
        @DisplayName("el rango del margen no aplica a este modo: 1000.01 da el error del porcentaje, no el del margen")
        void noMiraElRangoDelMargen() {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", "1000.01"))).containsOnlyKeys("porcentajeEnRango");
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", "-5"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("modo MARGEN")
    class Margen {

        @ParameterizedTest(name = "{0} es valido")
        @ValueSource(strings = {"0", "0.00", "30", "30.5", "1000", "1000.00", "0.01"})
        void valoresValidos(String valor) {
            assertThat(errores(vistaPrevia("MARGEN", valor))).isEmpty();
            assertThat(errores(aplicar("MARGEN", valor, "abc"))).isEmpty();
        }

        @ParameterizedTest(name = "{0} esta fuera de rango")
        @ValueSource(strings = {"-0.01", "-1", "1000.01", "5000", "12345"})
        void fueraDeRango(String valor) {
            assertThat(errores(vistaPrevia("MARGEN", valor)))
                    .containsOnly(Map.entry("margenEnRango", MENSAJE_RANGO_MARGEN));
            assertThat(errores(aplicar("MARGEN", valor, "abc")))
                    .containsOnly(Map.entry("margenEnRango", MENSAJE_RANGO_MARGEN));
        }

        @Test
        @DisplayName("el cero es valido en MARGEN y no dispara el error del porcentaje")
        void ceroEsValido() {
            assertThat(errores(vistaPrevia("MARGEN", "0"))).isEmpty();
        }

        @Test
        @DisplayName("un valor negativo del porcentaje no dispara el mensaje del porcentaje")
        void noMiraElRangoDelPorcentaje() {
            assertThat(errores(vistaPrevia("MARGEN", "-100"))).containsOnlyKeys("margenEnRango");
        }
    }

    @Nested
    @DisplayName("decimales")
    class Decimales {

        @ParameterizedTest(name = "{0} tiene mas de 2 decimales")
        @ValueSource(strings = {"10.555", "-99.995", "0.001", "30.123", "1000.001"})
        void masDeDosDecimales(String valor) {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", valor)))
                    .containsOnly(Map.entry("valor", MENSAJE_DECIMALES));
            assertThat(errores(vistaPrevia("MARGEN", valor)))
                    .containsOnly(Map.entry("valor", MENSAJE_DECIMALES));
            assertThat(errores(aplicar("MARGEN", valor, "abc")))
                    .containsOnly(Map.entry("valor", MENSAJE_DECIMALES));
        }

        @Test
        @DisplayName("la escala manda, como en ArticuloRequest: 10.500 tiene 3 decimales aunque el ultimo sea cero")
        void laEscalaManda() {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", "10.500")))
                    .containsOnly(Map.entry("valor", MENSAJE_DECIMALES));
            assertThat(errores(vistaPrevia("MARGEN", "30.000")))
                    .containsOnly(Map.entry("valor", MENSAJE_DECIMALES));
            assertThat(errores(vistaPrevia("MARGEN", "30.00"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("modo y valor ausentes o desconocidos")
    class ModoYValor {

        @ParameterizedTest(name = "modo [{0}] es invalido")
        @ValueSource(strings = {"OTRO", "costo_porcentaje", "COSTO", "MARGEN ", ""})
        void modoDesconocido(String modo) {
            assertThat(errores(vistaPrevia(modo, "10"))).containsOnly(Map.entry("modo", MENSAJE_MODO));
            assertThat(errores(aplicar(modo, "10", "abc"))).containsOnly(Map.entry("modo", MENSAJE_MODO));
        }

        @Test
        @DisplayName("modo ausente: el mismo mensaje y no se mira el valor contra ningun rango")
        void modoAusente() {
            assertThat(errores(vistaPrevia(null, "10"))).containsOnly(Map.entry("modo", MENSAJE_MODO));
            assertThat(errores(vistaPrevia(null, "0"))).containsOnly(Map.entry("modo", MENSAJE_MODO));
            assertThat(errores(vistaPrevia(null, "99999"))).containsOnly(Map.entry("modo", MENSAJE_MODO));
        }

        @Test
        @DisplayName("valor ausente: Ingresá un valor.")
        void valorAusente() {
            assertThat(errores(vistaPrevia("COSTO_PORCENTAJE", null)))
                    .containsOnly(Map.entry("valor", MENSAJE_VALOR_AUSENTE));
            assertThat(errores(vistaPrevia("MARGEN", null)))
                    .containsOnly(Map.entry("valor", MENSAJE_VALOR_AUSENTE));
            assertThat(errores(aplicar("MARGEN", null, "abc")))
                    .containsOnly(Map.entry("valor", MENSAJE_VALOR_AUSENTE));
        }

        @Test
        @DisplayName("sin modo ni valor: un mensaje por cada campo")
        void ninguno() {
            assertThat(errores(vistaPrevia(null, null))).containsOnly(
                    Map.entry("modo", MENSAJE_MODO), Map.entry("valor", MENSAJE_VALOR_AUSENTE));
        }
    }

    @Nested
    @DisplayName("huella de aplicar")
    class Huella {

        @ParameterizedTest(name = "huella [{0}] falta")
        @ValueSource(strings = {"", " ", "   "})
        void enBlanco(String huella) {
            assertThat(errores(aplicar("MARGEN", "30", huella))).containsOnly(Map.entry("huella", MENSAJE_HUELLA));
        }

        @Test
        @DisplayName("huella ausente")
        void ausente() {
            assertThat(errores(aplicar("MARGEN", "30", null))).containsOnly(Map.entry("huella", MENSAJE_HUELLA));
        }

        @Test
        @DisplayName("cualquier texto no vacio pasa la validacion (si corresponde, lo decide el 409)")
        void culquierTextoPasa() {
            assertThat(errores(aplicar("MARGEN", "30", "abc"))).isEmpty();
            assertThat(errores(aplicar("COSTO_PORCENTAJE", "10", "a".repeat(64)))).isEmpty();
        }

        @Test
        @DisplayName("la vista previa no pide huella")
        void vistaPreviaNoPideHuella() {
            assertThat(errores(vistaPrevia("MARGEN", "30"))).doesNotContainKey("huella");
        }

        @Test
        @DisplayName("huella en blanco y valor invalido: se informan los dos campos")
        void variosCampos() {
            assertThat(errores(aplicar("COSTO_PORCENTAJE", "0", " "))).containsOnly(
                    Map.entry("huella", MENSAJE_HUELLA), Map.entry("porcentajeDistintoDeCero", MENSAJE_CERO));
        }
    }

    @Nested
    @DisplayName("grupo de validacion")
    class Grupo {

        @Test
        @DisplayName("el grupo por defecto no valida nada: las valida el servicio despues de los 404")
        void elGrupoPorDefectoNoValida() {
            assertThat(validador.validate(vistaPrevia("OTRO", "0"), Default.class)).isEmpty();
            assertThat(validador.validate(aplicar(null, null, " "), Default.class)).isEmpty();
            assertThat(validador.validate(vistaPrevia("OTRO", "0"), ValidacionActualizacionMasiva.class)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("toFiltro y toOperacion")
    class Conversiones {

        @Test
        @DisplayName("toFiltro arma el filtro normalizado")
        void aFiltro() {
            ActualizacionMasivaRequest pedido =
                    new ActualizacionMasivaRequest(7L, 3L, "  cable ", true, "MARGEN", bd("30"));

            assertThat(pedido.toFiltro()).isEqualTo(new FiltroArticulos(7L, 3L, "cable", true));
            assertThat(vistaPrevia("MARGEN", "30").toFiltro()).isEqualTo(new FiltroArticulos(null, null, null, false));
            assertThat(new ActualizacionMasivaRequest(null, null, "   ", false, "MARGEN", bd("30")).toFiltro().vacio())
                    .isTrue();
        }

        @Test
        @DisplayName("toOperacion convierte el modo y deja el valor a 2 decimales")
        void aOperacion() {
            assertThat(vistaPrevia("COSTO_PORCENTAJE", "10").toOperacion())
                    .isEqualTo(new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10.00")));
            assertThat(vistaPrevia("MARGEN", "12.5").toOperacion())
                    .isEqualTo(new OperacionMasiva(ModoMasivo.MARGEN, bd("12.50")));
            assertThat(vistaPrevia("MARGEN", "30").toOperacion().valor()).isEqualTo(bd("30.00"));
        }

        @Test
        @DisplayName("aplicar convierte igual que la vista previa")
        void aplicarConvierte() {
            AplicarActualizacionMasivaRequest pedido = new AplicarActualizacionMasivaRequest(
                    7L, 3L, "tornillo", true, "COSTO_PORCENTAJE", bd("-20"), "abc");

            assertThat(pedido.toFiltro()).isEqualTo(new FiltroArticulos(7L, 3L, "tornillo", true));
            assertThat(pedido.toOperacion()).isEqualTo(new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("-20.00")));
            assertThat(pedido.huella()).isEqualTo("abc");
        }
    }
}
