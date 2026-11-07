package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #97: la fila de la vista previa del masivo (CU-05) trae los recargos del costo (su suma y el costo real antes y
 * despues) y el precio calculado antes de los ajustes, para que la cuenta costo x margen x IVA cierre en pantalla.
 * Ferreteria con margen por defecto 35, sin multiplo (hacia arriba al centavo) e IVA 21.
 */
class CambioMasivoRecargosYAjustesTest {

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

    private static ArticuloVigente articulo(String costo, String precio, boolean precioManual,
                                            List<AjustePrecio> ajustes, List<RecargoCosto> recargos) {
        return new ArticuloVigente(1L, "TOR-001", "PROV-1", "Tornillo", bd(costo), bd(precio), null, null,
            AlicuotaIva.IVA_21, true, Moneda.ARS, Ajustes.NINGUNO, precioManual, new AjustesDePrecio(ajustes),
            new RecargosDeCosto(recargos));
    }

    private static CambioMasivo unico(ArticuloVigente articulo, OperacionMasiva operacion) {
        PlanMasivo plan = PlanificadorMasivo.planificar(List.of(articulo), FILTRO, operacion, contextos());
        assertThat(plan.errores()).isEmpty();
        assertThat(plan.cambios()).hasSize(1);
        return plan.cambios().get(0);
    }

    private static final List<RecargoCosto> EMBALAJE = List.of(new RecargoCosto("EMBALAJE Y CARGA", bd("4.44")));
    private static final List<AjustePrecio> MAS_DIEZ_POR_CIENTO =
        List.of(new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, bd("10")));

    @Test
    void conRecargosTraeSuSumaYElCostoRealAntesYDespues() {
        // TOR-001 del recorrido: 115 x 1,0444 = 120,106 -> 120,11; x 1,35 x 1,21 = 196,1997 -> 196,20.
        ArticuloVigente tor = articulo("115.00", "196.20", false, List.of(), EMBALAJE);

        CambioMasivo cambio = unico(tor, new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10")));

        assertThat(cambio.recargosPorcentaje()).isEqualByComparingTo("4.44");
        assertThat(cambio.costoRealAnterior()).isEqualByComparingTo("120.11");
        // 126,50 x 1,0444 = 132,1166 -> 132,12.
        assertThat(cambio.costoRealNuevo()).isEqualByComparingTo("132.12");
        assertThat(cambio.precioSinAjustes()).isNull();
    }

    @Test
    void variosRecargosSeSumanYConElCostoSinCambiarElCostoRealQuedaIgual() {
        ArticuloVigente a = articulo("100.00", "186.93", false, List.of(),
            List.of(new RecargoCosto("Embalaje", bd("4.44")), new RecargoCosto("Flete", bd("10"))));

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.MARGEN, bd("40")));

        assertThat(cambio.recargosPorcentaje()).isEqualByComparingTo("14.44");
        assertThat(cambio.costoRealAnterior()).isEqualByComparingTo("114.44");
        assertThat(cambio.costoRealNuevo()).isEqualByComparingTo("114.44");
    }

    @Test
    void conAjustesTraeElPrecioCalculadoAntesDeLosAjustes() {
        // Hoy: 100 x 1,35 x 1,21 = 163,35 + 10 % = 179,69 (hacia arriba al centavo).
        ArticuloVigente a = articulo("100.00", "179.69", false, MAS_DIEZ_POR_CIENTO, List.of());

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.MARGEN, bd("40")));

        // 100 x 1,40 x 1,21 = 169,40; mas el 10 % = 186,34.
        assertThat(cambio.precioSinAjustes()).isEqualByComparingTo("169.40");
        assertThat(cambio.precioNuevo()).isEqualByComparingTo("186.34");
        assertThat(cambio.recargosPorcentaje()).isNull();
        assertThat(cambio.costoRealAnterior()).isNull();
        assertThat(cambio.costoRealNuevo()).isNull();
    }

    @Test
    void conAjustesYRecargosElPrecioSinAjustesSaleDelCostoReal() {
        // 120,11 x 1,35 x 1,21 = 196,20; mas el 10 % = 215,82.
        ArticuloVigente a = articulo("115.00", "215.82", false, MAS_DIEZ_POR_CIENTO, EMBALAJE);

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10")));

        // 132,12 x 1,35 x 1,21 = 215,8180 -> 215,82; mas el 10 % = 237,41 (237,402 hacia arriba).
        assertThat(cambio.precioSinAjustes()).isEqualByComparingTo("215.82");
        assertThat(cambio.precioNuevo()).isEqualByComparingTo("237.41");
        assertThat(cambio.costoRealNuevo()).isEqualByComparingTo("132.12");
    }

    @Test
    void conPrecioManualNoHayPrecioSinAjustes() {
        ArticuloVigente a = articulo("100.00", "200.00", true, MAS_DIEZ_POR_CIENTO, EMBALAJE);

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10")));

        assertThat(cambio.precioNuevo()).isEqualByComparingTo("200.00");
        assertThat(cambio.precioSinAjustes()).isNull();
        assertThat(cambio.recargosPorcentaje()).isEqualByComparingTo("4.44");
    }

    @Test
    void elPrecioFinalDejaElPrecioManualYNoHayPrecioSinAjustes() {
        ArticuloVigente a = articulo("100.00", "179.69", false, MAS_DIEZ_POR_CIENTO, EMBALAJE);

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.PRECIO_PORCENTAJE, bd("10")));

        assertThat(cambio.precioSinAjustes()).isNull();
        // El costo no cambia: el costo real tampoco.
        assertThat(cambio.costoRealAnterior()).isEqualByComparingTo("104.44");
        assertThat(cambio.costoRealNuevo()).isEqualByComparingTo("104.44");
    }

    @Test
    void cambiarElRubroTraeElPrecioSinAjustesConElMargenDelDestino() {
        ArticuloVigente a = articulo("100.00", "179.69", false, MAS_DIEZ_POR_CIENTO, List.of());

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.CAMBIAR_RUBRO, null, RUBRO_DESTINO));

        // 100 x 1,50 x 1,21 = 181,50; mas el 10 % = 199,65.
        assertThat(cambio.precioSinAjustes()).isEqualByComparingTo("181.50");
        assertThat(cambio.precioNuevo()).isEqualByComparingTo("199.65");
    }

    @Test
    void sinRecargosNiAjustesLosCuatroCamposVanVacios() {
        ArticuloVigente a = articulo("100.00", "163.35", false, List.of(), List.of());

        CambioMasivo cambio = unico(a, new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10")));

        assertThat(cambio.recargosPorcentaje()).isNull();
        assertThat(cambio.costoRealAnterior()).isNull();
        assertThat(cambio.costoRealNuevo()).isNull();
        assertThat(cambio.precioSinAjustes()).isNull();
    }
}
