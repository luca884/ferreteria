package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Planificador de la actualizacion masiva: logica pura, con articulos armados a mano. Ferreteria con
 * margen por defecto 30, multiplo 10 y IVA 21 salvo que el test diga otra cosa.
 */
class PlanificadorMasivoTest {

    private static final FiltroArticulos FILTRO = new FiltroArticulos(null, null, null, false);
    private static final ContextoPrecio SIN_RUBRO = new ContextoPrecio(null, bd("30.00"), bd("10"));
    private static final String COSTO_CERO = "El costo nuevo da 0. Revisá el porcentaje.";
    private static final String DEMASIADO_GRANDE = "El costo o el precio nuevo es demasiado grande.";

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** Articulo vigente de prueba: IVA_21, activo, sin rubro ni margen propio. */
    private static final class Art {
        private final long id;
        private final String costo;
        private String precioFinal = "0.00";
        private String margen;
        private Long rubroId;
        private AlicuotaIva iva = AlicuotaIva.IVA_21;
        private boolean activo = true;
        private Moneda moneda = Moneda.ARS;

        private Art(long id, String costo) {
            this.id = id;
            this.costo = costo;
        }

        static Art art(long id, String costo) {
            return new Art(id, costo);
        }

        Art precio(String valor) {
            this.precioFinal = valor;
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

        Art inactivo() {
            this.activo = false;
            return this;
        }

        Art enDolares() {
            this.moneda = Moneda.USD;
            return this;
        }

        ArticuloVigente vigente() {
            return new ArticuloVigente(id, "INT-" + id, "PROV-" + id, "Articulo " + id, bd(costo), bd(precioFinal),
                margen == null ? null : bd(margen), rubroId, iva, activo, moneda);
        }
    }

    private static ContextosDePrecio contextos() {
        return new ContextosDePrecio(Map.of(), SIN_RUBRO);
    }

    private static ContextosDePrecio contextos(long rubroId, String margenRubro, String multiplo) {
        return new ContextosDePrecio(
            Map.of(rubroId, new ContextoPrecio(bd(margenRubro), bd("30.00"), bd(multiplo))),
            new ContextoPrecio(null, bd("30.00"), bd(multiplo)));
    }

    private static PlanMasivo costoPorcentaje(String valor, Art... articulos) {
        return planificar(ModoMasivo.COSTO_PORCENTAJE, valor, contextos(), articulos);
    }

    private static PlanMasivo margen(String valor, Art... articulos) {
        return planificar(ModoMasivo.MARGEN, valor, contextos(), articulos);
    }

    private static PlanMasivo planificar(ModoMasivo modo, String valor, ContextosDePrecio contextos, Art... articulos) {
        List<ArticuloVigente> vigentes = java.util.Arrays.stream(articulos).map(Art::vigente).toList();
        return PlanificadorMasivo.planificar(vigentes, FILTRO, new OperacionMasiva(modo, bd(valor)), contextos);
    }

    /** Ferreteria con el dolar vigente en 1000 pesos. */
    private static ContextosDePrecio contextosConDolar() {
        return new ContextosDePrecio(Map.of(), new ContextoPrecio(null, bd("30.00"), bd("10"), bd("1000")));
    }

    // ------------------------------------------------------------------ articulos en dolares

    @Nested
    @DisplayName("articulos en dolares")
    class EnDolares {

        @Test
        @DisplayName("costo +10 %: un USD de 10 pasa a 11 USD y su precio en pesos usa el dolar (17310); el ARS sigue igual (180)")
        void costoPorcentajeUsaElDolarSoloParaLosUsd() {
            PlanMasivo plan = planificar(ModoMasivo.COSTO_PORCENTAJE, "10", contextosConDolar(),
                Art.art(1, "10.00").enDolares().precio("15730.00"), Art.art(2, "100.00").precio("170.00"));

            assertThat(plan.cambios()).hasSize(2);
            CambioMasivo usd = plan.cambios().get(0);
            assertThat(usd.costoNuevo()).isEqualTo(bd("11.00"));
            assertThat(usd.precioNuevo()).isEqualTo(bd("17310.00"));
            CambioMasivo ars = plan.cambios().get(1);
            assertThat(ars.costoNuevo()).isEqualTo(bd("110.00"));
            assertThat(ars.precioNuevo()).isEqualTo(bd("180.00"));
        }

        @Test
        @DisplayName("margen 40: un USD de 10 da 10 x 1000 x 1,4 x 1,21 = 16940")
        void margenUsaElDolar() {
            PlanMasivo plan = planificar(ModoMasivo.MARGEN, "40", contextosConDolar(),
                Art.art(1, "10.00").enDolares().precio("15730.00"));

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("16940.00"));
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("un USD con el dolar en 1100 en lugar de 1000 da otro precio: 17310 en vez de 15730")
        void elPrecioDependeDelDolarDelContexto() {
            ContextosDePrecio con1100 = new ContextosDePrecio(Map.of(),
                new ContextoPrecio(null, bd("30.00"), bd("10"), bd("1100")));

            PlanMasivo plan = planificar(ModoMasivo.MARGEN, "30", con1100,
                Art.art(1, "10.00").enDolares().precio("15730.00"));

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("17310.00"));
        }

        @Test
        @DisplayName("un USD en un contexto sin dolar es un error de programacion: IllegalStateException")
        void sinDolarEnElContexto() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> planificar(ModoMasivo.MARGEN, "40", contextos(),
                Art.art(1, "10.00").enDolares().precio("15730.00")))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    // ------------------------------------------------------------------ COSTO_PORCENTAJE

    @Nested
    @DisplayName("modo COSTO_PORCENTAJE")
    class CostoPorcentaje {

        @ParameterizedTest(name = "costo {0} con {1} % -> {2}")
        @CsvSource({
            "100.00, 10, 110.00",
            "100.00, -20, 80.00",
            "33.33, 10, 36.66",
            "10.05, 50, 15.08",
            "100.00, -99.99, 0.01",
            "100.00, 1000, 1100.00",
            "100, 0.01, 100.01",
            "1.00, 10, 1.10"
        })
        @DisplayName("el costo nuevo es costo x (100 + valor) / 100, con un solo redondeo HALF_UP a 2 decimales")
        void costoNuevo(String costo, String valor, String esperado) {
            PlanMasivo plan = costoPorcentaje(valor, Art.art(1, costo).precio("1.00"));

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualTo(bd(esperado));
            assertThat(plan.cambios().get(0).costoAnterior()).isEqualByComparingTo(costo);
        }

        @ParameterizedTest(name = "costo {0} con {1} % -> precio {2}")
        @CsvSource({
            "100.00, 10, 180.00",
            "100.00, -20, 130.00",
            "33.33, 10, 60.00"
        })
        @DisplayName("el precio nuevo es la formula de catalogo sobre el costo nuevo (margen 30, IVA 21, multiplo 10)")
        void precioNuevoConMargenPorDefecto(String costo, String valor, String precioEsperado) {
            PlanMasivo plan = costoPorcentaje(valor, Art.art(1, costo).precio("1.00"));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd(precioEsperado));
        }

        @Test
        @DisplayName("el margen del rubro (40) manda sobre el de la ferreteria: costo 110 da 190")
        void margenDelRubro() {
            PlanMasivo plan = planificar(ModoMasivo.COSTO_PORCENTAJE, "10", contextos(7, "40", "10"),
                Art.art(1, "100.00").precio("170.00").rubro(7));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("190.00"));
            assertThat(plan.cambios().get(0).margenAnterior()).isEqualByComparingTo("40");
            assertThat(plan.cambios().get(0).margenNuevo()).isEqualByComparingTo("40");
            assertThat(plan.cambios().get(0).margenHeredado()).isTrue();
        }

        @Test
        @DisplayName("el margen propio (50) manda sobre el del rubro (40): costo 110 da 200")
        void margenPropioSobreElRubro() {
            PlanMasivo plan = planificar(ModoMasivo.COSTO_PORCENTAJE, "10", contextos(7, "40", "10"),
                Art.art(1, "100.00").precio("190.00").rubro(7).margen("50"));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("200.00"));
            assertThat(plan.cambios().get(0).margenAnterior()).isEqualByComparingTo("50");
            assertThat(plan.cambios().get(0).margenHeredado()).isFalse();
        }

        @Test
        @DisplayName("con multiplo 50 el precio 173,03 sube a 200")
        void multiploDeLaFerreteria() {
            PlanMasivo plan = planificar(ModoMasivo.COSTO_PORCENTAJE, "10", contextos(7, "40", "50"),
                Art.art(1, "100.00").precio("150.00"));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("200.00"));
        }

        @Test
        @DisplayName("la fila lleva codigo, descripcion y los valores anteriores y nuevos")
        void camposDeLaFila() {
            PlanMasivo plan = costoPorcentaje("10", Art.art(1, "100.00").precio("160.00"));

            CambioMasivo cambio = plan.cambios().get(0);
            assertThat(cambio.articuloId()).isEqualTo(1L);
            assertThat(cambio.codigo()).isEqualTo("INT-1");
            assertThat(cambio.descripcion()).isEqualTo("Articulo 1");
            assertThat(cambio.costoAnterior()).isEqualByComparingTo("100");
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("110");
            assertThat(cambio.margenAnterior()).isEqualByComparingTo("30");
            assertThat(cambio.margenNuevo()).isEqualByComparingTo("30");
            assertThat(cambio.precioAnterior()).isEqualByComparingTo("160");
            assertThat(cambio.precioNuevo()).isEqualByComparingTo("180");
        }

        @ParameterizedTest(name = "costo {0} con {1} % -> error de costo cero")
        @CsvSource({
            "0.50, -99.99",
            "0.00, 10",
            "0.00, -50"
        })
        @DisplayName("un costo nuevo que redondea a 0.00 es un error de fila y no se escribe")
        void costoNuevoCero(String costo, String valor) {
            PlanMasivo plan = costoPorcentaje(valor, Art.art(1, costo).precio("10.00"));

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.errores()).hasSize(1);
            ErrorMasivo error = plan.errores().get(0);
            assertThat(error.articuloId()).isEqualTo(1L);
            assertThat(error.codigo()).isEqualTo("INT-1");
            assertThat(error.descripcion()).isEqualTo("Articulo 1");
            assertThat(error.motivo()).isEqualTo(COSTO_CERO);
        }

        @Test
        @DisplayName("costo 0.04 con +10 da 0.044 -> 0.04: no es error (el costo no es 0)")
        void costoChicoQueNoEsCero() {
            PlanMasivo plan = costoPorcentaje("10", Art.art(1, "0.04").precio("10.00"));

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).isEmpty();
        }

        @Test
        @DisplayName("costo 0.04 con el precio guardado desactualizado en 20.00: se recalcula y baja a 10.00")
        void precioDesactualizadoSeRecalculaIgual() {
            PlanMasivo plan = costoPorcentaje("10", Art.art(1, "0.04").precio("20.00"));

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
            CambioMasivo cambio = plan.cambios().get(0);
            assertThat(cambio.costoNuevo()).isEqualTo(bd("0.04"));
            assertThat(cambio.precioAnterior()).isEqualByComparingTo("20");
            assertThat(cambio.precioNuevo()).isEqualTo(bd("10.00"));
        }

        @Test
        @DisplayName("un costo nuevo con mas de 13 enteros es un error de fila por tamano")
        void costoDemasiadoGrande() {
            PlanMasivo plan = costoPorcentaje("10", Art.art(1, "9999999999999.99").precio("1.00"));

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.errores()).hasSize(1);
            assertThat(plan.errores().get(0).motivo()).isEqualTo(DEMASIADO_GRANDE);
        }

        @Test
        @DisplayName("un precio nuevo con mas de 13 enteros (costo nuevo valido) es un error de fila por tamano")
        void precioDemasiadoGrande() {
            // 8.8e12 entra en DECIMAL(15,2); por 1,573 da 1,38e13 (14 enteros).
            PlanMasivo plan = costoPorcentaje("10", Art.art(1, "8000000000000.00").precio("1.00"));

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.errores()).hasSize(1);
            assertThat(plan.errores().get(0).motivo()).isEqualTo(DEMASIADO_GRANDE);
        }

        @Test
        @DisplayName("un costo nuevo de exactamente 13 enteros no es error")
        void costoEnElLimite() {
            PlanMasivo plan = costoPorcentaje("-50", Art.art(1, "9999999999999.00").precio("1.00"));

            // 5e12 por 1,573 da 7,8e12: 13 enteros, entra.
            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
        }

        @Test
        @DisplayName("un error de fila no frena a los demas, que se planifican igual")
        void elErrorNoFrenaALosDemas() {
            PlanMasivo plan = costoPorcentaje("-99.99",
                Art.art(1, "100.00").precio("160.00"),
                Art.art(2, "0.50").precio("10.00"),
                Art.art(3, "200.00").precio("320.00"));

            assertThat(plan.cambios()).extracting(CambioMasivo::articuloId).containsExactly(1L, 3L);
            assertThat(plan.errores()).extracting(ErrorMasivo::articuloId).containsExactly(2L);
            assertThat(plan.cambios().get(0).costoNuevo()).isEqualTo(bd("0.01"));
        }

        @Test
        @DisplayName("COSTO_PORCENTAJE nunca cuenta articulos que pasan a margen propio")
        void noHayPasanAMargenPropio() {
            PlanMasivo plan = costoPorcentaje("10",
                Art.art(1, "100.00").precio("160.00"),
                Art.art(2, "200.00").precio("320.00"));

            assertThat(plan.conteos().pasanAMargenPropio()).isZero();
        }
    }

    // ------------------------------------------------------------------ MARGEN

    @Nested
    @DisplayName("modo MARGEN")
    class Margen {

        @Test
        @DisplayName("fija el margen: costo 100 que heredaba el 30 con MARGEN 50 pasa de 160 a 190 y el costo no cambia")
        void fijaElMargenYElCostoQuedaIgual() {
            PlanMasivo plan = margen("50", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios()).hasSize(1);
            CambioMasivo cambio = plan.cambios().get(0);
            assertThat(cambio.costoAnterior()).isEqualByComparingTo("100");
            assertThat(cambio.costoNuevo()).isEqualByComparingTo("100");
            assertThat(cambio.margenAnterior()).isEqualByComparingTo("30");
            assertThat(cambio.margenNuevo()).isEqualByComparingTo("50");
            assertThat(cambio.precioAnterior()).isEqualByComparingTo("160");
            assertThat(cambio.precioNuevo()).isEqualTo(bd("190.00"));
            assertThat(cambio.margenHeredado()).as("en modo MARGEN el margen nuevo ya es propio").isFalse();
        }

        @Test
        @DisplayName("el margen 0 se respeta (no es nulo): costo 100 da precio 130")
        void margenCero() {
            PlanMasivo plan = margen("0", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios().get(0).margenNuevo()).isEqualByComparingTo("0");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("130.00"));
        }

        @Test
        @DisplayName("margen 30.5 sobre costo 200: 315,81 sube a 320")
        void margenConDecimales() {
            PlanMasivo plan = margen("30.5", Art.art(1, "200.00").precio("1.00"));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("320.00"));
        }

        @Test
        @DisplayName("margen 1000 es valido y pisa el margen propio y el del rubro")
        void margenMil() {
            PlanMasivo plan = planificar(ModoMasivo.MARGEN, "1000", contextos(7, "40", "10"),
                Art.art(1, "100.00").precio("170.00").rubro(7).margen("20"));

            // 100 x 11 x 1,21 = 1331 -> 1340
            assertThat(plan.cambios().get(0).margenAnterior()).isEqualByComparingTo("20");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("1340.00"));
        }

        @Test
        @DisplayName("un costo 0.00 no es error en MARGEN: el costo no se toca y el precio sigue en 0.00")
        void costoCeroNoEsError() {
            PlanMasivo plan = margen("50", Art.art(1, "0.00").precio("0.00").margen("50"));

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.conteos().conError()).isZero();
        }

        @Test
        @DisplayName("costo 0.00 con margen propio nulo: pasa a margen propio, es modificado y su precio es 0.00")
        void costoCeroConMargenNulo() {
            PlanMasivo plan = margen("50", Art.art(1, "0.00").precio("0.00"));

            assertThat(plan.errores()).isEmpty();
            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("0.00"));
        }

        @Test
        @DisplayName("modificado cuenta un cambio solo del margen propio: nulo -> 30 con el precio igual")
        void cambioSoloDelMargenPropioDesdeNulo() {
            PlanMasivo plan = margen("30", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.cambios().get(0).precioAnterior()).isEqualByComparingTo("160");
            assertThat(plan.cambios().get(0).precioNuevo()).isEqualByComparingTo("160");
            assertThat(plan.conteos().modificados()).isEqualTo(1);
        }

        @Test
        @DisplayName("modificado cuenta un cambio solo del margen propio: 35 -> 36 con el precio igual (170)")
        void cambioSoloDelMargenPropioDesdeOtroValor() {
            PlanMasivo plan = margen("36", Art.art(1, "100.00").precio("170.00").margen("35"));

            assertThat(plan.cambios()).hasSize(1);
            assertThat(plan.conteos().modificados()).isEqualTo(1);
        }

        @Test
        @DisplayName("el margen propio se compara por valor: 50 y 50.00 no cambian nada")
        void margenPropioIgualPorValor() {
            PlanMasivo plan = margen("50.00", Art.art(1, "100.00").precio("190.00").margen("50"));

            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.conteos().modificados()).isZero();
        }

        @Test
        @DisplayName("tabla B (rubro al 40, MARGEN 50): B1 y B3 cambian el precio a 190 y B2 no cambia nada")
        void tablaB() {
            PlanMasivo plan = planificar(ModoMasivo.MARGEN, "50", contextos(7, "40", "10"),
                Art.art(1, "100.00").precio("170.00").rubro(7),
                Art.art(2, "100.00").precio("190.00").rubro(7).margen("50"),
                Art.art(3, "100.00").precio("160.00").rubro(7).margen("30"));

            assertThat(plan.cambios()).extracting(CambioMasivo::articuloId).containsExactly(1L, 3L);
            assertThat(plan.cambios()).extracting(CambioMasivo::precioNuevo)
                .containsExactly(bd("190.00"), bd("190.00"));
            assertThat(plan.cambios().get(0).margenAnterior()).isEqualByComparingTo("40");
            assertThat(plan.cambios().get(0).margenNuevo()).isEqualByComparingTo("50");
            assertThat(plan.cambios().get(1).margenAnterior()).isEqualByComparingTo("30");
            assertThat(plan.conteos().modificados()).isEqualTo(2);
        }

        @Test
        @DisplayName("pasanAMargenPropio cuenta solo los de margen propio nulo: B1 nulo, B2 = 50, B3 = 30 da 1")
        void pasanAMargenPropioTablaB() {
            PlanMasivo plan = planificar(ModoMasivo.MARGEN, "50", contextos(7, "40", "10"),
                Art.art(1, "100.00").precio("170.00").rubro(7),
                Art.art(2, "100.00").precio("190.00").rubro(7).margen("50"),
                Art.art(3, "100.00").precio("160.00").rubro(7).margen("30"));

            assertThat(plan.conteos().pasanAMargenPropio()).isEqualTo(1);
        }

        @Test
        @DisplayName("dos articulos sin rubro y con margen nulo con MARGEN 30 pasan a margen propio aunque el valor sea el mismo")
        void pasanAMargenPropioAunqueSeaElMismoValor() {
            PlanMasivo plan = margen("30",
                Art.art(1, "100.00").precio("160.00"),
                Art.art(2, "100.00").precio("160.00"));

            assertThat(plan.conteos().pasanAMargenPropio()).isEqualTo(2);
            assertThat(plan.conteos().modificados()).isEqualTo(2);
        }

        @Test
        @DisplayName("un articulo con error no cuenta en pasanAMargenPropio")
        void conErrorNoCuenta() {
            // 2e12 x 11 x 1,21 = 2,66e13 (14 enteros): error por tamano.
            PlanMasivo plan = margen("1000",
                Art.art(1, "2000000000000.00").precio("1.00"),
                Art.art(2, "100.00").precio("1.00"));

            assertThat(plan.errores()).extracting(ErrorMasivo::articuloId).containsExactly(1L);
            assertThat(plan.errores().get(0).motivo()).isEqualTo(DEMASIADO_GRANDE);
            assertThat(plan.conteos().pasanAMargenPropio()).isEqualTo(1);
        }

        @Test
        @DisplayName("los inactivos del alcance se planifican igual y no cambian su estado")
        void inactivosSePlanifican() {
            PlanMasivo plan = margen("50", Art.art(1, "100.00").precio("160.00").inactivo());

            assertThat(plan.cambios()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ contadores, advertencias, huella

    private static void assertTotalCuadra(ConteosMasivos c) {
        assertThat(c.total()).isEqualTo(c.suben() + c.bajan() + c.sinCambioDePrecio() + c.conError());
    }

    private static Art[] tablaA() {
        return new Art[] {
            Art.art(1, "100.00").precio("160.00"),
            Art.art(2, "200.00").precio("320.00"),
            Art.art(3, "1.00").precio("10.00"),
            Art.art(4, "0.00").precio("0.00"),
            Art.art(5, "0.04").precio("10.00")
        };
    }

    @Nested
    @DisplayName("contadores")
    class Contadores {

        @Test
        @DisplayName("tabla A con +10: 5 en total, 2 suben, 2 sin cambio de precio, 1 con error y 3 modificados")
        void tablaA() {
            PlanMasivo plan = costoPorcentaje("10", PlanificadorMasivoTest.tablaA());

            ConteosMasivos c = plan.conteos();
            assertThat(c.total()).isEqualTo(5);
            assertThat(c.suben()).isEqualTo(2);
            assertThat(c.bajan()).isZero();
            assertThat(c.sinCambioDePrecio()).isEqualTo(2);
            assertThat(c.conError()).isEqualTo(1);
            assertThat(c.pasanAMargenPropio()).isZero();
            assertThat(c.conAdvertencia()).isZero();
            assertThat(c.modificados()).isEqualTo(3);
            assertTotalCuadra(c);
        }

        @Test
        @DisplayName("tabla A: cambios son A1, A2 y A3 por id y errores es A4")
        void detalleDeLaTablaA() {
            PlanMasivo plan = costoPorcentaje("10", PlanificadorMasivoTest.tablaA());

            assertThat(plan.cambios()).extracting(CambioMasivo::articuloId).containsExactly(1L, 2L, 3L);
            assertThat(plan.errores()).extracting(ErrorMasivo::articuloId).containsExactly(4L);
            assertThat(plan.cambios()).extracting(CambioMasivo::costoNuevo)
                .containsExactly(bd("110.00"), bd("220.00"), bd("1.10"));
            assertThat(plan.cambios()).extracting(CambioMasivo::precioNuevo)
                .containsExactly(bd("180.00"), bd("350.00"), bd("10.00"));
        }

        @Test
        @DisplayName("tabla B (rubro al 40, MARGEN 50): 2 suben, 1 sin cambio, 1 pasa a margen propio y 2 modificados")
        void tablaB() {
            PlanMasivo plan = planificar(ModoMasivo.MARGEN, "50", contextos(7, "40", "10"),
                Art.art(1, "100.00").precio("170.00").rubro(7),
                Art.art(2, "100.00").precio("190.00").rubro(7).margen("50"),
                Art.art(3, "100.00").precio("160.00").rubro(7).margen("30"));

            ConteosMasivos c = plan.conteos();
            assertThat(c.total()).isEqualTo(3);
            assertThat(c.suben()).isEqualTo(2);
            assertThat(c.bajan()).isZero();
            assertThat(c.sinCambioDePrecio()).isEqualTo(1);
            assertThat(c.conError()).isZero();
            assertThat(c.pasanAMargenPropio()).isEqualTo(1);
            assertThat(c.modificados()).isEqualTo(2);
            assertTotalCuadra(c);
        }

        @Test
        @DisplayName("bajan cuenta los articulos cuyo precio nuevo es menor: costo -20 baja 160 a 130")
        void bajan() {
            PlanMasivo plan = costoPorcentaje("-20",
                Art.art(1, "100.00").precio("160.00"),
                Art.art(2, "100.00").precio("160.00"));

            assertThat(plan.conteos().bajan()).isEqualTo(2);
            assertThat(plan.conteos().suben()).isZero();
            assertTotalCuadra(plan.conteos());
        }

        @Test
        @DisplayName("un articulo con error cuenta en conError y no en suben, bajan ni sinCambioDePrecio")
        void errorNoCuentaEnPrecio() {
            PlanMasivo plan = costoPorcentaje("-99.99", Art.art(1, "0.50").precio("10.00"));

            assertThat(plan.conteos().total()).isEqualTo(1);
            assertThat(plan.conteos().conError()).isEqualTo(1);
            assertThat(plan.conteos().suben() + plan.conteos().bajan() + plan.conteos().sinCambioDePrecio()).isZero();
            assertThat(plan.conteos().modificados()).isZero();
        }

        @Test
        @DisplayName("un alcance vacio da todo en 0 y no revienta")
        void alcanceVacio() {
            PlanMasivo plan = costoPorcentaje("10");

            assertThat(plan.conteos()).isEqualTo(new ConteosMasivos(0, 0, 0, 0, 0, 0, 0, 0));
            assertThat(plan.cambios()).isEmpty();
            assertThat(plan.errores()).isEmpty();
            assertThat(plan.huella()).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("no importa el orden de entrada: los articulos se procesan por id")
        void independienteDelOrden() {
            Art[] enOrden = PlanificadorMasivoTest.tablaA();
            Art[] alReves = {enOrden[4], enOrden[2], enOrden[0], enOrden[3], enOrden[1]};

            PlanMasivo ordenado = costoPorcentaje("10", enOrden);
            PlanMasivo desordenado = costoPorcentaje("10", alReves);

            assertThat(desordenado).isEqualTo(ordenado);
            assertThat(desordenado.cambios()).extracting(CambioMasivo::articuloId).containsExactly(1L, 2L, 3L);
        }
    }

    @Nested
    @DisplayName("advertencias por variacion fuerte (mas de 50 %)")
    class Advertencias {

        @Test
        @DisplayName("COSTO +60 sobre 100 advierte que el costo sube un 60 %")
        void costoSube() {
            PlanMasivo plan = costoPorcentaje("60", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios().get(0).advertencia())
                .isEqualTo("El costo sube un 60 % (de 100,00 a 160,00). Revisá que sea correcto.");
            assertThat(plan.conteos().conAdvertencia()).isEqualTo(1);
        }

        @Test
        @DisplayName("COSTO -60 sobre 100 advierte que el costo baja un 60 %")
        void costoBaja() {
            PlanMasivo plan = costoPorcentaje("-60", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios().get(0).advertencia())
                .isEqualTo("El costo baja un 60 % (de 100,00 a 40,00). Revisá que sea correcto.");
        }

        @ParameterizedTest(name = "COSTO {0} % sobre 100: advierte = {1}")
        @CsvSource({
            "50, false",
            "-50, false",
            "50.01, true",
            "-50.01, true"
        })
        @DisplayName("el limite de 50 % exacto no advierte; un poco mas, si")
        void limite(String valor, boolean advierte) {
            PlanMasivo plan = costoPorcentaje(valor, Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios().get(0).advertencia() != null).isEqualTo(advierte);
            assertThat(plan.conteos().conAdvertencia()).isEqualTo(advierte ? 1 : 0);
        }

        @Test
        @DisplayName("la variacion se mide sobre el costo ya redondeado: 0.03 con +50 da 0.05 y sube un 66,67 %")
        void variacionSobreElCostoRedondeado() {
            PlanMasivo plan = costoPorcentaje("50", Art.art(1, "0.03").precio("10.00"));

            assertThat(plan.cambios().get(0).costoNuevo()).isEqualTo(bd("0.05"));
            assertThat(plan.cambios().get(0).advertencia())
                .isEqualTo("El costo sube un 66,67 % (de 0,03 a 0,05). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("MARGEN 300 sobre costo 100 y precio 160 advierte que el precio sube un 206,25 %")
        void margenSube() {
            PlanMasivo plan = margen("300", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("490.00"));
            assertThat(plan.cambios().get(0).advertencia())
                .isEqualTo("El precio sube un 206,25 % (de 160,00 a 490,00). Revisá que sea correcto.");
            assertThat(plan.conteos().conAdvertencia()).isEqualTo(1);
        }

        @Test
        @DisplayName("MARGEN 0 sobre margen propio 300 y precio 490 advierte que el precio baja un 73,47 %")
        void margenBaja() {
            PlanMasivo plan = margen("0", Art.art(1, "100.00").precio("490.00").margen("300"));

            assertThat(plan.cambios().get(0).precioNuevo()).isEqualTo(bd("130.00"));
            assertThat(plan.cambios().get(0).advertencia())
                .isEqualTo("El precio baja un 73,47 % (de 490,00 a 130,00). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("MARGEN 95 da 240 (+50 % exacto) y no advierte; MARGEN 100 da 250 (+56,25 %) y si")
        void margenEnElLimite() {
            PlanMasivo exacto = margen("95", Art.art(1, "100.00").precio("160.00"));
            PlanMasivo pasado = margen("100", Art.art(1, "100.00").precio("160.00"));

            assertThat(exacto.cambios().get(0).precioNuevo()).isEqualTo(bd("240.00"));
            assertThat(exacto.cambios().get(0).advertencia()).isNull();
            assertThat(pasado.cambios().get(0).precioNuevo()).isEqualTo(bd("250.00"));
            assertThat(pasado.cambios().get(0).advertencia())
                .isEqualTo("El precio sube un 56,25 % (de 160,00 a 250,00). Revisá que sea correcto.");
        }

        @Test
        @DisplayName("MARGEN no advierte por el costo: no varia")
        void margenNoMideElCosto() {
            PlanMasivo plan = margen("50", Art.art(1, "100.00").precio("160.00"));

            assertThat(plan.cambios().get(0).advertencia()).isNull();
        }

        @Test
        @DisplayName("con valor anterior 0 no se advierte")
        void anteriorCero() {
            PlanMasivo plan = margen("300", Art.art(1, "100.00").precio("0.00"));

            assertThat(plan.cambios().get(0).advertencia()).isNull();
            assertThat(plan.conteos().conAdvertencia()).isZero();
        }

        @Test
        @DisplayName("un articulo con error no advierte ni cuenta en conAdvertencia")
        void errorNoAdvierte() {
            PlanMasivo plan = costoPorcentaje("60",
                Art.art(1, "9999999999999.99").precio("1.00"),
                Art.art(2, "100.00").precio("160.00"));

            assertThat(plan.errores()).hasSize(1);
            assertThat(plan.conteos().conAdvertencia()).isEqualTo(1);
        }

        @Test
        @DisplayName("conAdvertencia cuenta exacto: 2 de 3 articulos")
        void conAdvertenciaExacto() {
            PlanMasivo plan = costoPorcentaje("60",
                Art.art(1, "100.00").precio("160.00"),
                Art.art(2, "0.00").precio("0.00"),
                Art.art(3, "10.00").precio("20.00"));

            assertThat(plan.conteos().conAdvertencia()).isEqualTo(2);
            assertThat(plan.cambios()).extracting(CambioMasivo::advertencia).doesNotContainNull();
        }
    }

    @Nested
    @DisplayName("huella")
    class Huella {

        private PlanMasivo plan(FiltroArticulos filtro, ModoMasivo modo, String valor, ArticuloVigente... articulos) {
            return PlanificadorMasivo.planificar(
                List.of(articulos), filtro, new OperacionMasiva(modo, bd(valor)), contextos());
        }

        private ArticuloVigente a(long id, String costo, String margen, String precio) {
            Art art = Art.art(id, costo).precio(precio);
            return (margen == null ? art : art.margen(margen)).vigente();
        }

        @Test
        @DisplayName("la misma entrada da la misma huella, de 64 hexadecimales")
        void determinista() {
            String primera = costoPorcentaje("10", PlanificadorMasivoTest.tablaA()).huella();
            String segunda = costoPorcentaje("10", PlanificadorMasivoTest.tablaA()).huella();

            assertThat(primera).isEqualTo(segunda).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("la huella alimenta cada articulo con sus valores nuevos y los errores con E")
        void formatoDeLaHuella() {
            ArticuloVigente modificado = a(1, "100.00", null, "160.00");
            ArticuloVigente conError = a(2, "0.50", "50.00", "10.00");
            PlanMasivo plan = plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "-99.99", modificado, conError);

            HuellaMasiva esperada = new HuellaMasiva(
                new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("-99.99")), FILTRO);
            esperada.agregar(modificado, bd("0.01"), null, bd("10.00"));
            esperada.agregarError(conError);

            assertThat(plan.huella()).isEqualTo(esperada.calcular());
        }

        @Test
        @DisplayName("en MARGEN la huella lleva el margen propio nuevo")
        void margenPropioNuevoEnLaHuella() {
            ArticuloVigente articulo = a(1, "100.00", null, "160.00");
            PlanMasivo plan = plan(FILTRO, ModoMasivo.MARGEN, "50", articulo);

            HuellaMasiva esperada = new HuellaMasiva(new OperacionMasiva(ModoMasivo.MARGEN, bd("50")), FILTRO);
            esperada.agregar(articulo, bd("100.00"), bd("50.00"), bd("190.00"));

            assertThat(plan.huella()).isEqualTo(esperada.calcular());
        }

        @Test
        @DisplayName("cambia si cambia la operacion o el filtro")
        void cambiaConLaOperacionYElFiltro() {
            ArticuloVigente articulo = a(1, "100.00", null, "160.00");
            String base = plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", articulo).huella();

            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "15", articulo).huella()).isNotEqualTo(base);
            assertThat(plan(FILTRO, ModoMasivo.MARGEN, "10", articulo).huella()).isNotEqualTo(base);
            assertThat(plan(new FiltroArticulos(1L, null, null, false), ModoMasivo.COSTO_PORCENTAJE, "10", articulo)
                .huella()).isNotEqualTo(base);
            assertThat(plan(new FiltroArticulos(null, null, null, true), ModoMasivo.COSTO_PORCENTAJE, "10", articulo)
                .huella()).isNotEqualTo(base);
        }

        @Test
        @DisplayName("cambia si cambia cualquier dato de un articulo: costo, margen propio, precio o activo")
        void cambiaConLosDatosDelArticulo() {
            String base = plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a(1, "100.00", null, "160.00")).huella();

            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a(1, "100.01", null, "160.00")).huella())
                .isNotEqualTo(base);
            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a(1, "100.00", "30.00", "160.00")).huella())
                .isNotEqualTo(base);
            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a(1, "100.00", null, "170.00")).huella())
                .isNotEqualTo(base);
            ArticuloVigente inactivo = Art.art(1, "100.00").precio("160.00").inactivo().vigente();
            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", inactivo).huella()).isNotEqualTo(base);
        }

        @Test
        @DisplayName("cambia si entra o sale un articulo del alcance, aunque no cambie nada")
        void cambiaConLaMembresia() {
            ArticuloVigente a1 = a(1, "100.00", null, "160.00");
            ArticuloVigente sinCambio = a(2, "0.04", null, "10.00");

            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a1, sinCambio).huella())
                .isNotEqualTo(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a1).huella());
        }

        @Test
        @DisplayName("170 y 170.00 dan la misma huella")
        void escalasEquivalentes() {
            assertThat(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a(1, "100", "30", "170")).huella())
                .isEqualTo(plan(FILTRO, ModoMasivo.COSTO_PORCENTAJE, "10", a(1, "100.00", "30.00", "170.00")).huella());
        }
    }
}
