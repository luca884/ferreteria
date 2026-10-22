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

    // --- alta y edicion con el precio (PedidoDePrecio) ---

    @Test
    @DisplayName("editar con precio manual: guarda los datos, marca el precio y devuelve un solo cambio")
    void editarConPrecioManual() {
        Articulo articulo = articulo();

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("120"),
            new PedidoDePrecio(true, bd("999.99"), null), CTX);

        assertThat(articulo.getCosto()).isEqualTo(bd("120"));
        assertThat(articulo.isPrecioManual()).isTrue();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("999.99"));
        assertThat(cambio).contains(new CambioPrecio(bd("100"), bd("120"), bd("163.35"), bd("999.99")));
    }

    @Test
    @DisplayName("editar con precio manual sin precio o en 0: 400 en precioFinal y no cambia nada")
    void editarConPrecioManualInvalido() {
        Articulo articulo = articulo();

        assertThatThrownBy(() -> articulo.actualizar(datos("120"), new PedidoDePrecio(true, null, null), CTX))
            .isInstanceOf(DatosInvalidosException.class)
            .satisfies(e -> assertThat(((DatosInvalidosException) e).errors())
                .containsEntry("precioFinal", "El precio tiene que ser mayor a 0."));
        assertThatThrownBy(() -> articulo.actualizar(datos("120"), new PedidoDePrecio(true, bd("0"), null), CTX))
            .isInstanceOf(DatosInvalidosException.class);
        assertThat(articulo.getCosto()).isEqualTo(bd("100"));
        assertThat(articulo.isPrecioManual()).isFalse();
    }

    @Test
    @DisplayName("editar destildando el precio manual lo vuelve a calcular con el costo nuevo y los ajustes")
    void editarLiberando() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(porcentaje("10")), CTX);
        articulo.fijarPrecioManual(bd("1500"));

        articulo.actualizar(datos("200"), new PedidoDePrecio(false, bd("1500"), null), CTX);

        assertThat(articulo.isPrecioManual()).isFalse();
        // 200 x 1,35 x 1,21 = 326,70; +10 % = 359,37
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("359.37"));
    }

    @Test
    @DisplayName("los ajustes nuevos se aplican con el costo nuevo, aunque los viejos dejarian el precio en 0")
    void ajustesNuevosConCostoNuevo() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(monto("-150")), CTX);

        articulo.actualizar(datos("10"), new PedidoDePrecio(null, null, ajustes(monto("5"))), CTX);

        // 10 x 1,35 x 1,21 = 16,335 -> 16,34; +5 = 21,34
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("21.34"));
        assertThat(articulo.getAjustesPrecio()).isEqualTo(ajustes(monto("5")));
    }

    @Test
    @DisplayName("sin nada del precio en el pedido se conservan la marca y los ajustes")
    void conservar() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(porcentaje("10")), CTX);
        articulo.fijarPrecioManual(bd("1500"));

        articulo.actualizar(datos("200"), PedidoDePrecio.CONSERVAR, CTX);

        assertThat(articulo.isPrecioManual()).isTrue();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("1500.00"));
        assertThat(articulo.getAjustesPrecio()).isEqualTo(ajustes(porcentaje("10")));
    }

    @Test
    @DisplayName("ajustes que dejan el precio en 0 o menos: 400 en ajustesPrecio (la transaccion se deshace)")
    void ajustesEnCero() {
        Articulo articulo = articulo();

        assertThatThrownBy(() -> articulo.actualizar(datos("120"),
                new PedidoDePrecio(null, null, ajustes(monto("-1000"))), CTX))
            .isInstanceOf(DatosInvalidosException.class)
            .satisfies(e -> assertThat(((DatosInvalidosException) e).errors()).containsKey("ajustesPrecio"));
    }

    // --- recalculos automaticos: saltear y avisar (S7) ---

    private static final String AVISO = "No se cambió el precio de TORN-001 (Tornillo): con sus ajustes quedaría en 0 o menos."
        + " Revisá sus ajustes.";

    /** Costo 100 con margen de 35 % (precio 163,35) y un ajuste de -100: hoy da 63,35; con costo 5 daria 0 o menos. */
    private static Articulo conAjusteNegativo() {
        Articulo articulo = articulo();
        articulo.fijarAjustesDePrecio(ajustes(monto("-100")), CTX);
        return articulo;
    }

    /** Sin margen propio: hereda el del rubro. Con rubro al 100 % y ajuste de -150 el precio es 92,00. */
    private static Articulo heredandoElMargenDelRubro() {
        Articulo articulo = new Articulo(1L);
        articulo.actualizar(new DatosArticulo("TORN-001", null, null, "Tornillo", null, null, bd("100"), null,
            AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true), new ContextoPrecio(bd("100"), bd("30"), BigDecimal.ZERO));
        articulo.fijarAjustesDePrecio(ajustes(monto("-150")), new ContextoPrecio(bd("100"), bd("30"), BigDecimal.ZERO));
        return articulo;
    }

    @Test
    @DisplayName("recalculo automatico: si los ajustes dejan el precio en 0 o menos conserva el precio y avisa")
    void recalculoAutomaticoSaltea() {
        Articulo articulo = heredandoElMargenDelRubro();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("92.00"));

        RecalculoAutomatico resultado = articulo.recalcularPrecioAutomatico(
            new ContextoPrecio(BigDecimal.ZERO, bd("30"), BigDecimal.ZERO));

        assertThat(resultado.cambio()).isEmpty();
        assertThat(resultado.aviso()).contains(AVISO);
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("92.00"));
    }

    @Test
    @DisplayName("recalculo automatico sin problema: devuelve el cambio y no avisa")
    void recalculoAutomaticoNormal() {
        Articulo articulo = heredandoElMargenDelRubro();

        RecalculoAutomatico resultado = articulo.recalcularPrecioAutomatico(
            new ContextoPrecio(bd("200"), bd("30"), BigDecimal.ZERO));

        assertThat(resultado.aviso()).isEmpty();
        assertThat(resultado.cambio()).isPresent();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("213.00"));
    }

    @Test
    @DisplayName("ajustar el costo automaticamente: el costo cambia, el precio se conserva y avisa")
    void ajustarCostoAutomaticoSaltea() {
        Articulo articulo = conAjusteNegativo();
        BigDecimal precioDeHoy = articulo.getPrecioFinal();

        RecalculoAutomatico resultado = articulo.ajustarCostoAutomatico(bd("5"), CTX);

        assertThat(articulo.getCosto()).isEqualTo(bd("5"));
        assertThat(articulo.getPrecioFinal()).isEqualTo(precioDeHoy);
        assertThat(resultado.aviso()).contains(AVISO);
        assertThat(resultado.cambio()).isPresent();
        assertThat(resultado.cambio().get().costoAnterior()).isEqualTo(bd("100"));
        assertThat(resultado.cambio().get().costoNuevo()).isEqualTo(bd("5"));
        assertThat(resultado.cambio().get().precioNuevo()).isEqualTo(precioDeHoy);
    }

    @Test
    @DisplayName("precio planeado con un costo: si los ajustes lo dejan en 0 o menos es el de hoy y trae el aviso")
    void precioPlaneadoSaltea() {
        Articulo articulo = conAjusteNegativo();

        PrecioPlaneado planeado = articulo.precioConCostoAutomatico(bd("5"), CTX);

        assertThat(planeado.precio()).isEqualTo(articulo.getPrecioFinal());
        assertThat(planeado.aviso()).contains(AVISO);
        assertThat(articulo.getCosto()).isEqualTo(bd("100"));
    }

    @Test
    @DisplayName("las ediciones a mano siguen rechazando: un costo que deja el precio en 0 o menos da 400")
    void edicionAManoSigueRechazando() {
        Articulo articulo = conAjusteNegativo();

        assertThatThrownBy(() -> articulo.ajustarCosto(bd("5"), CTX)).isInstanceOf(DatosInvalidosException.class);
    }
}
