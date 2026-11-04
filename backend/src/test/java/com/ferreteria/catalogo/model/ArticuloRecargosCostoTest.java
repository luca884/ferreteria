package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los recargos del costo (CU-13, S4) entran al precio por las tres entradas (Articulo, ArticuloVigente y el alta de la
 * importacion), al margen negativo y al costo que copia la venta. Dominio puro.
 */
class ArticuloRecargosCostoTest {

    /** Sin multiplo: el precio queda al centavo. */
    private static final ContextoPrecio CTX = new ContextoPrecio(null, bd("30"), BigDecimal.ZERO);

    private static final RecargosDeCosto EMBALAJE = new RecargosDeCosto(List.of(new RecargoCosto("Embalaje", bd("10"))));

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static DatosArticulo datos(String costo) {
        return new DatosArticulo("TORN-001", null, null, "Tornillo", null, null, bd(costo), bd("35"),
                AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true);
    }

    /** Costo 100, margen 35, IVA 21: 163,35 sin recargos; con 10 % el costo real es 110 y el precio 179,69. */
    private static Articulo articulo() {
        Articulo articulo = new Articulo(1L);
        articulo.actualizar(datos("100"), CTX);
        return articulo;
    }

    @Test
    @DisplayName("un articulo nuevo no tiene recargos y el precio es el de siempre")
    void sinRecargos() {
        Articulo articulo = articulo();

        assertThat(articulo.getRecargosCosto().estaVacia()).isTrue();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("163.35"));
        assertThat(articulo.costoReal()).isEqualTo(bd("100"));
    }

    @Test
    @DisplayName("fijar recargos recalcula el precio con el costo real y devuelve el cambio")
    void fijarRecalcula() {
        Articulo articulo = articulo();

        Optional<CambioPrecio> cambio = articulo.fijarRecargosDeCosto(EMBALAJE, CTX);

        // 110.00 x 1.35 x 1.21 = 179.685 -> 179.69
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("179.69"));
        assertThat(articulo.getCosto()).isEqualTo(bd("100"));
        assertThat(articulo.costoReal()).isEqualTo(bd("110.00"));
        assertThat(articulo.getRecargosCosto()).isEqualTo(EMBALAJE);
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualTo(bd("163.35"));
        assertThat(cambio.get().precioNuevo()).isEqualTo(bd("179.69"));
    }

    @Test
    @DisplayName("los recargos siguen aplicandose cuando cambia el costo y al vaciarlos se vuelve al precio anterior")
    void seReaplicanYSeVacian() {
        Articulo articulo = articulo();
        articulo.fijarRecargosDeCosto(EMBALAJE, CTX);

        articulo.ajustarCosto(bd("200"), CTX);
        // 220.00 x 1.35 x 1.21 = 359.37
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("359.37"));

        articulo.fijarRecargosDeCosto(RecargosDeCosto.NINGUNO, CTX);
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("326.70"));
    }

    @Test
    @DisplayName("con precio manual se guardan los recargos pero el precio no cambia")
    void precioManualGuardaSinCambiarElPrecio() {
        Articulo articulo = articulo();
        articulo.fijarPrecioManual(bd("500.00"));

        Optional<CambioPrecio> cambio = articulo.fijarRecargosDeCosto(EMBALAJE, CTX);

        assertThat(cambio).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("500.00"));
        assertThat(articulo.getRecargosCosto()).isEqualTo(EMBALAJE);
    }

    @Test
    @DisplayName("la version automatica (compras) guarda los recargos y recalcula igual")
    void automatico() {
        Articulo articulo = articulo();

        RecalculoAutomatico resultado = articulo.fijarRecargosDeCostoAutomatico(EMBALAJE, CTX);

        assertThat(resultado.aviso()).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("179.69"));
        assertThat(articulo.precioConCostoAutomatico(bd("200"), RecargosDeCosto.NINGUNO, CTX).precio())
            .isEqualTo(bd("326.70"));
    }

    @Test
    @DisplayName("los ajustes de CU-14 se aplican sobre el precio calculado con el costo real")
    void ajustesSobreElCostoReal() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(new AjustesDePrecio(List.of(
            new AjustePrecio("Flete", TipoAjustePrecio.MONTO, bd("10")))), CTX);

        articulo.fijarRecargosDeCosto(EMBALAJE, CTX);

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("189.69"));
    }

    @Test
    @DisplayName("el aviso de margen negativo usa el costo real")
    void margenNegativoConCostoReal() {
        Articulo articulo = articulo();
        articulo.fijarPrecioManual(bd("121.00"));
        // 100 contra 100 sin IVA: margen 0, no avisa; con 10 % el costo real es 110 y avisa.
        assertThat(articulo.margenNegativoCon(bd("100"), CTX)).isEmpty();

        articulo.fijarRecargosDeCosto(EMBALAJE, CTX);

        assertThat(articulo.margenNegativoCon(bd("100"), CTX)).hasValueSatisfying(
            m -> assertThat(m).contains("el costo ($ 110,00) supera el precio sin IVA ($ 100,00)"));
    }

    @Test
    @DisplayName("MargenNegativo con recargos compara el costo real")
    void margenNegativoDirecto() {
        assertThat(MargenNegativo.mensaje(bd("100.00"), EMBALAJE, BigDecimal.ONE, AlicuotaIva.IVA_21, bd("121.00")))
            .isPresent();
        assertThat(MargenNegativo.mensaje(bd("100.00"), RecargosDeCosto.NINGUNO, BigDecimal.ONE, AlicuotaIva.IVA_21,
            bd("121.00"))).isEmpty();
    }

    @Test
    @DisplayName("ArticuloVigente (CU-05): el precio sale del costo real y el aviso tambien")
    void vigente() {
        ArticuloVigente vigente = new ArticuloVigente(1L, "A", "P", "Art", bd("100"), bd("121.00"), bd("35"), null,
            AlicuotaIva.IVA_21, true, Moneda.ARS, Ajustes.NINGUNO, false, AjustesDePrecio.NINGUNO, EMBALAJE);

        assertThat(vigente.precioCon(bd("100"), bd("35"), AlicuotaIva.IVA_21, BigDecimal.ZERO, BigDecimal.ONE))
            .isEqualTo(bd("179.69"));
        assertThat(vigente.costoReal(bd("200"))).isEqualTo(bd("220.00"));
        assertThat(ArticuloVigente.de(articuloConRecargos()).recargosCosto()).isEqualTo(EMBALAJE);
    }

    private static Articulo articuloConRecargos() {
        Articulo articulo = articulo();
        articulo.fijarRecargosDeCosto(EMBALAJE, CTX);
        return articulo;
    }

    /** El alta toma el margen del contexto (30), no el del articulo: 110 x 1.30 x 1.21 = 173.03 y 100 x 1.573 = 157.30. */
    @Test
    @DisplayName("el alta de la importacion acepta recargos: el precio sale del costo real")
    void altaDeLaImportacion() {
        assertThat(PlanificadorImportacion.precioDelAlta(bd("100"), EMBALAJE, AlicuotaIva.IVA_21, Moneda.ARS, CTX))
            .isEqualTo(bd("173.03"));
        assertThat(PlanificadorImportacion.precioDelAlta(bd("100"), RecargosDeCosto.NINGUNO, AlicuotaIva.IVA_21,
            Moneda.ARS, CTX)).isEqualTo(bd("157.30"));
    }
}
