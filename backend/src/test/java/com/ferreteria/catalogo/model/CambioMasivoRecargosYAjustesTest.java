package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    /**
     * Sonda del verificador (odd-verifier, 2026-11-07) como regresion: con planes al azar de semilla fija, en los seis
     * modos, los campos de recargos son null justo cuando no hay recargos y valen la suma y el costo real; el precio sin
     * ajustes es null sin ajustes, con precio manual o en los modos de precio final, y si no es el calculado nuevo, que
     * con los ajustes da el precio nuevo (en CAMBIAR_RUBRO, con el contexto del destino).
     */
    @Test
    void propiedadEnTodosLosModosConPlanesAlAzar() {
        Random r = new Random(20261107L);
        int conPrecioSinAjustes = 0;
        int conRecargos = 0;
        for (int p = 0; p < 3000; p++) {
            ModoMasivo modo = ModoMasivo.values()[p % ModoMasivo.values().length];
            BigDecimal[] multiplos = {null, BigDecimal.ZERO, bd("0.05"), bd("1"), bd("10"), bd("50")};
            BigDecimal multiplo = multiplos[r.nextInt(multiplos.length)];
            BigDecimal defecto = azar(r, 0, 120);
            BigDecimal cotizacion = azar(r, 800, 2000);
            Map<Long, ContextoPrecio> porRubro = new java.util.HashMap<>();
            for (long id = 1; id <= 3; id++) {
                porRubro.put(id, new ContextoPrecio(azar(r, 0, 150), defecto, multiplo, cotizacion));
            }
            ContextosDePrecio ctx = new ContextosDePrecio(porRubro, new ContextoPrecio(null, defecto, multiplo, cotizacion));
            List<ArticuloVigente> articulos = new java.util.ArrayList<>();
            for (long id = 1; id <= 1 + r.nextInt(8); id++) {
                articulos.add(articuloAlAzar(r, id, ctx));
            }
            OperacionMasiva operacion = switch (modo) {
                case COSTO_PORCENTAJE -> new OperacionMasiva(modo, azar(r, -60, 200));
                case COSTO_MONTO -> new OperacionMasiva(modo, distintoDeCero(azar(r, -500, 5000)));
                case MARGEN -> new OperacionMasiva(modo, azar(r, 0, 150));
                case PRECIO_PORCENTAJE -> new OperacionMasiva(modo, azar(r, -90, 200));
                case PRECIO_MONTO -> new OperacionMasiva(modo, distintoDeCero(azar(r, -2000, 5000)));
                case CAMBIAR_RUBRO -> new OperacionMasiva(modo, null, (long) (1 + r.nextInt(3)));
            };

            PlanMasivo plan = PlanificadorMasivo.planificar(articulos, FILTRO, operacion, ctx);

            for (CambioMasivo cambio : plan.cambios()) {
                ArticuloVigente a = articulos.stream().filter(x -> x.id().equals(cambio.articuloId())).findFirst()
                    .orElseThrow();
                String caso = "plan " + p + " " + operacion + " " + cambio;
                boolean sinRecargos = a.recargosCosto().estaVacia();
                assertThat(cambio.recargosPorcentaje() == null).as(caso).isEqualTo(sinRecargos);
                assertThat(cambio.costoRealAnterior() == null).as(caso).isEqualTo(sinRecargos);
                assertThat(cambio.costoRealNuevo() == null).as(caso).isEqualTo(sinRecargos);
                if (!sinRecargos) {
                    conRecargos++;
                    assertThat(cambio.recargosPorcentaje()).as(caso)
                        .isEqualByComparingTo(a.recargosCosto().porcentajeTotal());
                    assertThat(cambio.costoRealAnterior()).as(caso)
                        .isEqualTo(a.recargosCosto().aplicar(cambio.costoAnterior()));
                    assertThat(cambio.costoRealNuevo()).as(caso).isEqualTo(a.recargosCosto().aplicar(cambio.costoNuevo()));
                }
                boolean debeSerNull = a.ajustesPrecio().estaVacia() || a.precioManual() || modo.esDePrecioFinal();
                assertThat(cambio.precioSinAjustes() == null).as(caso).isEqualTo(debeSerNull);
                if (cambio.precioSinAjustes() != null) {
                    conPrecioSinAjustes++;
                    ContextoPrecio c = modo == ModoMasivo.CAMBIAR_RUBRO
                        ? ctx.de(operacion.rubroDestinoId()) : ctx.de(a.rubroId());
                    assertThat(a.ajustesPrecio().aplicar(cambio.precioSinAjustes(), c.redondeoMultiplo())).as(caso)
                        .isEqualByComparingTo(cambio.precioNuevo());
                    assertThat(cambio.precioSinAjustes()).as(caso).isEqualByComparingTo(PrecioCalculator.precioFinal(
                        a.costoReal(cambio.costoNuevo()), cambio.margenNuevo(), a.alicuotaIva(), c.redondeoMultiplo(),
                        c.cotizacionPara(a.moneda())));
                }
            }
        }
        // Que la semilla de verdad pase por los dos caminos.
        assertThat(conRecargos).isGreaterThan(500);
        assertThat(conPrecioSinAjustes).isGreaterThan(500);
    }

    private static BigDecimal azar(Random r, double min, double max) {
        return BigDecimal.valueOf(min + r.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal distintoDeCero(BigDecimal valor) {
        return valor.signum() == 0 ? bd("1.00") : valor;
    }

    /** Un articulo con o sin rubro, margen propio, dolares, precio manual, ajustes y recargos, al azar. */
    private static ArticuloVigente articuloAlAzar(Random r, long id, ContextosDePrecio ctx) {
        BigDecimal costo = r.nextInt(20) == 0 ? bd("0.00") : azar(r, 0.01, 50000);
        Long rubro = r.nextInt(4) == 0 ? null : (long) (1 + r.nextInt(3));
        BigDecimal margen = r.nextInt(3) == 0 ? azar(r, 0, 200) : null;
        AlicuotaIva iva = AlicuotaIva.values()[r.nextInt(AlicuotaIva.values().length)];
        Moneda moneda = r.nextInt(4) == 0 ? Moneda.USD : Moneda.ARS;
        boolean manual = r.nextInt(4) == 0;
        List<AjustePrecio> ajustes = new java.util.ArrayList<>();
        for (int i = 0; i < (r.nextInt(2) == 0 ? 1 + r.nextInt(3) : 0); i++) {
            ajustes.add(r.nextBoolean()
                ? new AjustePrecio("A" + i, TipoAjustePrecio.PORCENTAJE, azar(r, -30, 80))
                : new AjustePrecio("M" + i, TipoAjustePrecio.MONTO, azar(r, -100, 2000)));
        }
        List<RecargoCosto> recargos = new java.util.ArrayList<>();
        for (int i = 0; i < (r.nextInt(2) == 0 ? 1 + r.nextInt(5) : 0); i++) {
            BigDecimal porcentaje = azar(r, -15, 99.99);
            recargos.add(new RecargoCosto("R" + i, porcentaje.signum() == 0 ? bd("4.44") : porcentaje));
        }
        RecargosDeCosto rec = new RecargosDeCosto(recargos);
        AjustesDePrecio aj = new AjustesDePrecio(ajustes);
        ContextoPrecio c = ctx.de(rubro);
        BigDecimal calculado = PrecioCalculator.precioFinal(costo, rec,
            PrecioCalculator.margenEfectivo(margen, c.margenRubro(), c.margenDefecto()), iva, c.redondeoMultiplo(),
            c.cotizacionPara(moneda), aj);
        BigDecimal precio = manual || calculado.signum() <= 0 ? azar(r, 1, 100000) : calculado;
        return new ArticuloVigente(id, "INT-" + id, "P-" + id, "Art " + id, costo, precio, margen, rubro, iva, true,
            moneda, Ajustes.NINGUNO, manual, aj, rec);
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
