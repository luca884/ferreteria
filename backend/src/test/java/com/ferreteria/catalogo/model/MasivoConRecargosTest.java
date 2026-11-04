package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regresion de CU-13: CU-05 con recargos, la vista previa (planificador) = lo que aplica la entidad. */
class MasivoConRecargosTest {

    private static Articulo soldadora(ContextoPrecio ctx) {
        Articulo a = new Articulo(1L);
        a.actualizar(new DatosArticulo("SOL-1", null, null, "Soldadora", null, null, new BigDecimal("235971.56"), null,
            AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, Moneda.ARS), ctx);
        a.fijarRecargosDeCosto(new RecargosDeCosto(List.of(new RecargoCosto("Embalaje y carga", new BigDecimal("4.44")))),
            ctx);
        return a;
    }

    @Test
    void costoPorcentajeYMargenConRecargos() {
        ContextoPrecio ctx = new ContextoPrecio(null, new BigDecimal("30"), null);
        ContextosDePrecio contextos = ContextosDePrecio.armar(List.of(), new BigDecimal("30"), null);
        Articulo a = soldadora(ctx);
        assertThat(a.costoReal()).isEqualByComparingTo("246448.70");
        assertThat(a.getPrecioFinal()).isEqualByComparingTo("387663.81");

        PlanMasivo costo = PlanificadorMasivo.planificar(List.of(ArticuloVigente.de(a)),
            new FiltroArticulos(null, null, null, false), new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE,
                new BigDecimal("10")), contextos);
        Articulo copia = soldadora(ctx);
        copia.ajustarCosto(costo.cambios().get(0).costoNuevo(), ctx);
        assertThat(costo.cambios().get(0).precioNuevo()).isEqualTo(copia.getPrecioFinal());

        PlanMasivo margen = PlanificadorMasivo.planificar(List.of(ArticuloVigente.de(a)),
            new FiltroArticulos(null, null, null, false), new OperacionMasiva(ModoMasivo.MARGEN, new BigDecimal("40")),
            contextos);
        Articulo otra = soldadora(ctx);
        otra.fijarMargen(new BigDecimal("40"), ctx);
        assertThat(margen.cambios().get(0).precioNuevo()).isEqualTo(otra.getPrecioFinal());
        // 246448,70 x 1,40 x 1,21 = 417484,0978 hacia arriba al centavo.
        assertThat(otra.getPrecioFinal()).isEqualByComparingTo("417484.10");
    }

    @Test
    void ajustesQueDejanElPrecioEnCeroConElCostoRealSonErrorEnElPlan() {
        ContextoPrecio ctx = new ContextoPrecio(null, new BigDecimal("30"), null);
        ContextosDePrecio contextos = ContextosDePrecio.armar(List.of(), new BigDecimal("30"), null);
        Articulo a = new Articulo(1L);
        a.actualizar(new DatosArticulo("X", null, null, "X", null, null, new BigDecimal("100.00"), null,
            AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, Moneda.ARS),
            new PedidoDePrecio(null, null, new AjustesDePrecio(List.of(
                new AjustePrecio(null, TipoAjustePrecio.MONTO, new BigDecimal("-150"))))),
            new RecargosDeCosto(List.of(new RecargoCosto("Flete", new BigDecimal("20")))), ctx);
        // costo real 120 -> 188,76 - 150 = 38,76.
        assertThat(a.getPrecioFinal()).isEqualByComparingTo("38.76");
        // Costo -20 %: 80 -> real 96 -> 151,01 - 150 = 1,01 (entra). Costo -30 %: 70 -> 84 -> 132,13 - 150 < 0: error.
        PlanMasivo plan = PlanificadorMasivo.planificar(List.of(ArticuloVigente.de(a)),
            new FiltroArticulos(null, null, null, false), new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE,
                new BigDecimal("-30")), contextos);
        assertThat(plan.errores()).hasSize(1);
        PlanMasivo entra = PlanificadorMasivo.planificar(List.of(ArticuloVigente.de(a)),
            new FiltroArticulos(null, null, null, false), new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE,
                new BigDecimal("-20")), contextos);
        assertThat(entra.errores()).isEmpty();
        assertThat(entra.cambios().get(0).precioNuevo()).isEqualByComparingTo("1.01");
    }
}
