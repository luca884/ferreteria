package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

    @Test
    void enLosModosDePrecioFinalNoSeMarcaAunqueYaFueraManual() {
        assertThat(unico(articulo("200.00", true), new OperacionMasiva(ModoMasivo.PRECIO_PORCENTAJE, bd("10")))
            .precioManual()).isFalse();
        assertThat(unico(articulo("163.35", false), new OperacionMasiva(ModoMasivo.PRECIO_MONTO, bd("10")))
            .precioManual()).isFalse();
    }
}
