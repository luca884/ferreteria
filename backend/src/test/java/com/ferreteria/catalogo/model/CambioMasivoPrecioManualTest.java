package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * La fila de la vista previa del masivo (CU-05) marca el precio manual cuando la operacion cambia el costo o el margen
 * pero no el precio (CU-14 punto 1), como GDS distingue el precio manual del calculado (G17). En los modos de precio
 * final no se marca: ahi todos quedan con precio manual al precio nuevo. Ferreteria con margen por defecto 35, sin
 * multiplo e IVA 21.
 */
class CambioMasivoPrecioManualTest {

    private static final FiltroArticulos FILTRO = new FiltroArticulos(null, null, null, false);
    private static final long RUBRO_DESTINO = 7L;

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static ContextosDePrecio contextos() {
        return new ContextosDePrecio(
            Map.of(RUBRO_DESTINO, new ContextoPrecio(bd("50"), bd("35"), null)),
            new ContextoPrecio(null, bd("35"), null));
    }

    private static ArticuloVigente articulo(String precio, boolean precioManual) {
        return new ArticuloVigente(1L, "TOR-001", "PROV-1", "Tornillo", bd("100.00"), bd(precio), null, null,
            AlicuotaIva.IVA_21, true, Moneda.ARS, Ajustes.NINGUNO, precioManual, AjustesDePrecio.NINGUNO,
            RecargosDeCosto.NINGUNO);
    }

    private static CambioMasivo unico(ArticuloVigente articulo, OperacionMasiva operacion) {
        PlanMasivo plan = PlanificadorMasivo.planificar(List.of(articulo), FILTRO, operacion, contextos());
        assertThat(plan.errores()).isEmpty();
        assertThat(plan.cambios()).hasSize(1);
        return plan.cambios().get(0);
    }

    @Test
    void conPrecioManualElCostoCambiaElPrecioNoYLaFilaLoMarca() {
        CambioMasivo cambio = unico(articulo("200.00", true), new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10")));

        assertThat(cambio.costoNuevo()).isEqualByComparingTo("110.00");
        assertThat(cambio.precioNuevo()).isEqualByComparingTo("200.00");
        assertThat(cambio.precioManual()).isTrue();
    }

    @Test
    void conPrecioManualTambienSeMarcaEnElMontoElMargenYElCambioDeRubro() {
        assertThat(unico(articulo("200.00", true), new OperacionMasiva(ModoMasivo.COSTO_MONTO, bd("5"))).precioManual())
            .isTrue();
        assertThat(unico(articulo("200.00", true), new OperacionMasiva(ModoMasivo.MARGEN, bd("40"))).precioManual())
            .isTrue();
        assertThat(unico(articulo("200.00", true), OperacionMasiva.cambiarRubro(RUBRO_DESTINO)).precioManual())
            .isTrue();
    }

    @Test
    void sinPrecioManualNoSeMarca() {
        // 100 x 1,35 x 1,21 = 163,35.
        CambioMasivo cambio = unico(articulo("163.35", false), new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10")));

        assertThat(cambio.precioManual()).isFalse();
    }

    /** Sonda del odd-verifier, como regresion: en todos los modos, con planes al azar (semilla fija). */
    @Test
    void propiedadEnTodosLosModos() {
        Random r = new Random(7);
        int manuales = 0;
        for (int i = 0; i < 2_000; i++) {
            List<ArticuloVigente> articulos = new ArrayList<>();
            for (long id = 1; id <= 5; id++) {
                BigDecimal costo = BigDecimal.valueOf(1 + r.nextInt(1_000_000)).movePointLeft(2);
                BigDecimal precio = BigDecimal.valueOf(1 + r.nextInt(2_000_000)).movePointLeft(2);
                articulos.add(new ArticuloVigente(id, "INT-" + id, "P-" + id, "Articulo " + id, costo, precio,
                    r.nextBoolean() ? null : BigDecimal.valueOf(r.nextInt(80)), r.nextBoolean() ? null : RUBRO_DESTINO,
                    AlicuotaIva.values()[r.nextInt(AlicuotaIva.values().length)], true, Moneda.ARS, Ajustes.NINGUNO,
                    r.nextBoolean(), AjustesDePrecio.NINGUNO, RecargosDeCosto.NINGUNO));
            }
            ModoMasivo modo = ModoMasivo.values()[r.nextInt(ModoMasivo.values().length)];
            OperacionMasiva operacion = switch (modo) {
                case CAMBIAR_RUBRO -> OperacionMasiva.cambiarRubro(RUBRO_DESTINO);
                case MARGEN -> new OperacionMasiva(modo, BigDecimal.valueOf(r.nextInt(100)));
                default -> new OperacionMasiva(modo, BigDecimal.valueOf(1 + r.nextInt(50)));
            };
            PlanMasivo plan = PlanificadorMasivo.planificar(articulos, FILTRO, operacion, contextos());

            for (CambioMasivo cambio : plan.cambios()) {
                ArticuloVigente articulo = articulos.get((int) (cambio.articuloId() - 1));
                assertThat(cambio.precioManual()).as(modo + " " + articulo)
                    .isEqualTo(articulo.precioManual() && !modo.esDePrecioFinal());
                if (cambio.precioManual()) {
                    assertThat(cambio.precioNuevo()).isEqualByComparingTo(articulo.precioFinal());
                    manuales++;
                }
            }
        }
        assertThat(manuales).isPositive();
    }

    @Test
    void enLosModosDePrecioFinalNoSeMarcaAunqueYaFueraManual() {
        assertThat(unico(articulo("200.00", true), new OperacionMasiva(ModoMasivo.PRECIO_PORCENTAJE, bd("10")))
            .precioManual()).isFalse();
        assertThat(unico(articulo("163.35", false), new OperacionMasiva(ModoMasivo.PRECIO_MONTO, bd("10")))
            .precioManual()).isFalse();
    }
}
