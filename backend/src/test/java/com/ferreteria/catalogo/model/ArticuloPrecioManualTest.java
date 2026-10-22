package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Precio manual y ajustes sobre el precio final del articulo (CU-14). Dominio puro. */
class ArticuloPrecioManualTest {

    /** Sin multiplo: el precio queda al centavo. Costo 100, margen 35, IVA 21 da 163,35. */
    private static final ContextoPrecio CTX = new ContextoPrecio(null, bd("30"), BigDecimal.ZERO);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static DatosArticulo datos(String costo) {
        return new DatosArticulo("TORN-001", null, null, "Tornillo", null, null, bd(costo), bd("35"),
                AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true);
    }

    private static Articulo articulo() {
        Articulo articulo = new Articulo(1L);
        articulo.actualizar(datos("100"), CTX);
        return articulo;
    }

    private static AjustesDePrecio ajustes(AjustePrecio... lista) {
        return new AjustesDePrecio(List.of(lista));
    }

    private static AjustePrecio porcentaje(String valor) {
        return new AjustePrecio("Flete", TipoAjustePrecio.PORCENTAJE, bd(valor));
    }

    private static AjustePrecio monto(String valor) {
        return new AjustePrecio(null, TipoAjustePrecio.MONTO, bd(valor));
    }

    // --- ajustes sobre el precio final ---

    @Test
    @DisplayName("un articulo nuevo no tiene precio manual ni ajustes")
    void nuevoSinPrecioManualNiAjustes() {
        Articulo articulo = articulo();

        assertThat(articulo.isPrecioManual()).isFalse();
        assertThat(articulo.getAjustesPrecio().estaVacia()).isTrue();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("163.35"));
    }

    @Test
    @DisplayName("fijar ajustes recalcula el precio sobre el precio calculado y devuelve el cambio")
    void fijarAjustesRecalcula() {
        Articulo articulo = articulo();

        Optional<CambioPrecio> cambio = articulo.fijarAjustesDePrecio(ajustes(porcentaje("10"), monto("-3.35")), CTX);

        // 163,35 + 16,335 - 3,35 = 176,335 -> hacia arriba al centavo
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("176.34"));
        assertThat(articulo.getAjustesPrecio().lista()).hasSize(2);
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualTo(bd("163.35"));
        assertThat(cambio.get().precioNuevo()).isEqualTo(bd("176.34"));
    }

    @Test
    @DisplayName("los ajustes se vuelven a aplicar cuando cambia el costo")
    void ajustesSeReaplican() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(porcentaje("10")), CTX);

        articulo.ajustarCosto(bd("200"), CTX);

        // 200 x 1,35 x 1,21 = 326,70; +10 % = 359,37
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("359.37"));
    }

    @Test
    @DisplayName("el precio de una compra (vista previa) tambien lleva los ajustes")
    void precioConCostoLlevaLosAjustes() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(monto("100")), CTX);

        assertThat(articulo.precioConCosto(bd("200"), CTX)).isEqualTo(bd("426.70"));
    }

    @Test
    @DisplayName("ajustes que dejan el precio en 0 o menos se rechazan y no cambian nada")
    void ajustesQueDejanElPrecioEnCero() {
        Articulo articulo = articulo();

        assertThatThrownBy(() -> articulo.fijarAjustesDePrecio(ajustes(monto("-163.35")), CTX))
            .isInstanceOf(DatosInvalidosException.class)
            .satisfies(e -> assertThat(((DatosInvalidosException) e).errors())
                .containsEntry("ajustesPrecio", Articulo.PRECIO_CERO_O_MENOS));
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("163.35"));
        assertThat(articulo.getAjustesPrecio().estaVacia()).isTrue();
    }

    @Test
    @DisplayName("un costo que baja y deja el precio con ajustes en 0 o menos se rechaza")
    void costoQueDejaElPrecioEnCero() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(monto("-150")), CTX);

        assertThatThrownBy(() -> articulo.ajustarCosto(bd("5"), CTX))
            .isInstanceOf(DatosInvalidosException.class);
    }

    // --- precio manual ---

    @Test
    @DisplayName("fijar el precio a mano lo marca como manual y devuelve el cambio")
    void fijarPrecioManual() {
        Articulo articulo = articulo();

        Optional<CambioPrecio> cambio = articulo.fijarPrecioManual(bd("1500"));

        assertThat(articulo.isPrecioManual()).isTrue();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("1500.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualTo(bd("163.35"));
        assertThat(cambio.get().costoNuevo()).isEqualTo(articulo.getCosto());
    }

    @Test
    @DisplayName("el precio manual tiene que ser mayor a 0 y entrar en el tope")
    void precioManualInvalido() {
        Articulo articulo = articulo();

        assertThatThrownBy(() -> articulo.fijarPrecioManual(BigDecimal.ZERO)).isInstanceOf(DatosInvalidosException.class);
        assertThatThrownBy(() -> articulo.fijarPrecioManual(bd("-1"))).isInstanceOf(DatosInvalidosException.class);
        assertThatThrownBy(() -> articulo.fijarPrecioManual(null)).isInstanceOf(DatosInvalidosException.class);
        assertThatThrownBy(() -> articulo.fijarPrecioManual(bd("10000000000000")))
            .isInstanceOf(DatosInvalidosException.class);
        assertThat(articulo.isPrecioManual()).isFalse();
    }

    @Test
    @DisplayName("con precio manual, los cambios de costo, margen y contexto no tocan el precio")
    void precioManualNoCambiaConNadaAutomatico() {
        Articulo articulo = articulo();
        articulo.fijarPrecioManual(bd("1500"));

        Optional<CambioPrecio> porCosto = articulo.ajustarCosto(bd("300"), CTX);
        articulo.actualizarCosto(bd("400"), bd("500"), CTX);
        articulo.fijarMargen(bd("80"), CTX);
        Optional<CambioPrecio> porContexto = articulo.recalcularPrecio(new ContextoPrecio(bd("90"), bd("30"), null));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("1500.00"));
        assertThat(articulo.getCosto()).isEqualByComparingTo("400");
        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("80");
        assertThat(porCosto).isPresent();
        assertThat(porCosto.get().precioNuevo()).isEqualTo(bd("1500.00"));
        assertThat(porContexto).isEmpty();
    }

    @Test
    @DisplayName("con precio manual, la edicion del articulo tampoco recalcula el precio")
    void edicionConPrecioManual() {
        Articulo articulo = articulo();
        articulo.fijarPrecioManual(bd("1500"));

        articulo.actualizar(datos("250"), CTX);

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("1500.00"));
        assertThat(articulo.precioConCosto(bd("999"), CTX)).isEqualTo(bd("1500.00"));
    }

    @Test
    @DisplayName("con precio manual, los ajustes se guardan pero no se aplican")
    void ajustesConPrecioManual() {
        Articulo articulo = articulo();
        articulo.fijarPrecioManual(bd("1500"));

        Optional<CambioPrecio> cambio = articulo.fijarAjustesDePrecio(ajustes(porcentaje("10")), CTX);

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("1500.00"));
        assertThat(articulo.getAjustesPrecio().lista()).hasSize(1);
        assertThat(cambio).isEmpty();
    }

    @Test
    @DisplayName("liberar el precio vuelve a calcularlo con la regla y los ajustes")
    void liberarPrecio() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(porcentaje("10")), CTX);
        articulo.fijarPrecioManual(bd("1500"));

        Optional<CambioPrecio> cambio = articulo.liberarPrecio(CTX);

        assertThat(articulo.isPrecioManual()).isFalse();
        // 163,35 + 10 % = 179,685 -> 179,69
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("179.69"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualTo(bd("1500.00"));
    }

    @Test
    @DisplayName("liberar un precio que no era manual no cambia nada")
    void liberarSinPrecioManual() {
        Articulo articulo = articulo();

        assertThat(articulo.liberarPrecio(CTX)).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("163.35"));
    }
}
