package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class ArticuloTest {

    private static final Long FERRETERIA = 1L;

    /** Ferreteria con margen por defecto 30 y multiplo 10; el rubro aporta su margen si hay. */
    private static ContextoPrecio ctx(String margenRubro) {
        return new ContextoPrecio(margenRubro == null ? null : bd(margenRubro), bd("30"), bd("10"));
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static DatosArticulo datos(String costo, String margen, AlicuotaIva iva) {
        return new DatosArticulo("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4",
                10L, 20L, bd(costo), margen == null ? null : bd(margen), iva, Unidad.UNIDAD,
                bd("5"), "Estante 3", true);
    }

    private static Articulo articuloCon(String costo, String margen) {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos(costo, margen, AlicuotaIva.IVA_21), ctx(null));
        return articulo;
    }

    private static void fijarStockActual(Articulo articulo, String valor) throws Exception {
        Field campo = Articulo.class.getDeclaredField("stockActual");
        campo.setAccessible(true);
        campo.set(articulo, bd(valor));
    }

    // --- alta ---

    @Test
    @DisplayName("alta: copia los datos, calcula el precio y devuelve el cambio con anteriores null")
    void altaCalculaPrecioYDevuelveCambioConAnterioresNull() {
        Articulo articulo = new Articulo(FERRETERIA);

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("100", "35", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(articulo.getCodigoInterno()).isEqualTo("TORN-001");
        assertThat(articulo.getCodigoBarras()).isEqualTo("7790001000012");
        assertThat(articulo.getCodigoProveedor()).isEqualTo("AB-12");
        assertThat(articulo.getDescripcion()).isEqualTo("Tornillo hexagonal 1/4");
        assertThat(articulo.getRubroId()).isEqualTo(10L);
        assertThat(articulo.getProveedorId()).isEqualTo(20L);
        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("35");
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(articulo.getUnidad()).isEqualTo(Unidad.UNIDAD);
        assertThat(articulo.getStockMinimo()).isEqualByComparingTo("5");
        assertThat(articulo.getUbicacion()).isEqualTo("Estante 3");
        assertThat(articulo.isActivo()).isTrue();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isNull();
        assertThat(cambio.get().precioAnterior()).isNull();
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("100");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("170");
    }

    @Test
    @DisplayName("alta: el stock actual arranca en 0")
    void altaConStockActualEnCero() {
        Articulo articulo = new Articulo(FERRETERIA);

        articulo.actualizar(datos("100", "35", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getStockActual()).isEqualByComparingTo("0");
    }

    // --- edicion: cambio de costo / margen ---

    @Test
    @DisplayName("costo 100 -> 200: precio 170 -> 330 (326,70 sube al multiplo de 10)")
    void cambioDeCosto() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("200", "35", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("330.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("200");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("330");
    }

    @Test
    @DisplayName("margen 35 -> 36: el precio redondeado sigue en 170 y no hay cambio")
    void margenQueNoMueveElPrecioNoDaCambio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("100", "36", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("36");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
        assertThat(cambio).isEmpty();
    }

    @Test
    @DisplayName("margen 35 -> 50: costo 100 -> 100 y precio 170 -> 190")
    void cambioSoloDelPrecio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("100", "50", AlicuotaIva.IVA_21), ctx(null));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("100");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("cambiar solo la descripcion no genera cambio de precio")
    void soloDescripcionNoDaCambio() {
        Articulo articulo = articuloCon("100", "35");
        DatosArticulo original = datos("100", "35", AlicuotaIva.IVA_21);
        DatosArticulo nuevaDescripcion = new DatosArticulo(original.codigoInterno(), original.codigoBarras(),
                original.codigoProveedor(), "Tornillo hexagonal 5/16", original.rubroId(), original.proveedorId(),
                original.costo(), original.margenPorcentaje(), original.alicuotaIva(), original.unidad(),
                original.stockMinimo(), original.ubicacion(), original.activo());

        Optional<CambioPrecio> cambio = articulo.actualizar(nuevaDescripcion, ctx(null));

        assertThat(articulo.getDescripcion()).isEqualTo("Tornillo hexagonal 5/16");
        assertThat(cambio).isEmpty();
    }

    @Test
    @DisplayName("la escala del costo no cuenta como cambio (100 y 100.00)")
    void escalaDelCostoNoCuentaComoCambio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("100.00", "35.00", AlicuotaIva.IVA_21), ctx(null));

        assertThat(cambio).isEmpty();
    }

    // --- herencia de margen por contexto ---

    @Test
    @DisplayName("sin margen propio hereda el del rubro del contexto: 35 -> 170")
    void heredaDelRubro() {
        Articulo articulo = new Articulo(FERRETERIA);

        articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx("35"));

        assertThat(articulo.getMargenPorcentaje()).isNull();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
    }

    @Test
    @DisplayName("sin margen propio ni rubro hereda el de la ferreteria: 30 -> 160")
    void heredaDeLaFerreteria() {
        Articulo articulo = new Articulo(FERRETERIA);

        articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("160.00"));
    }

    @Test
    @DisplayName("el margen propio gana sobre el del rubro: 50 -> 190")
    void margenPropioGanaSobreElRubro() {
        Articulo articulo = new Articulo(FERRETERIA);

        articulo.actualizar(datos("100", "50", AlicuotaIva.IVA_21), ctx("35"));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("190.00"));
    }

    @Test
    @DisplayName("un margen propio de 0 se respeta: 121 sube a 130")
    void margenPropioCeroSeRespeta() {
        Articulo articulo = new Articulo(FERRETERIA);

        articulo.actualizar(datos("100", "0", AlicuotaIva.IVA_21), ctx("35"));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("130.00"));
    }

    @Test
    @DisplayName("quitar el rubro recalcula con el margen de la ferreteria: 170 -> 160")
    void quitarElRubroRecalcula() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx("35"));

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx(null));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("160");
    }

    // --- IVA ---

    @Test
    @DisplayName("IVA 21 -> 10,5: el precio pasa de 170 a 150 (149,175 sube a 150)")
    void cambioDeIva() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizar(datos("100", "35", AlicuotaIva.IVA_10_5), ctx(null));

        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("150.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("150");
    }

    // --- stock actual ---

    @Test
    @DisplayName("actualizar no toca el stock actual")
    void actualizarNoTocaElStockActual() throws Exception {
        Articulo articulo = articuloCon("100", "35");
        fijarStockActual(articulo, "12");

        articulo.actualizar(datos("200", "50", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getStockActual()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("recalcularPrecio no toca el stock actual")
    void recalcularNoTocaElStockActual() throws Exception {
        Articulo articulo = articuloCon("100", null);
        fijarStockActual(articulo, "12");

        articulo.recalcularPrecio(ctx("50"));

        assertThat(articulo.getStockActual()).isEqualByComparingTo("12");
    }

    // --- recalcularPrecio (cascada de rubro / CU-05) ---

    @Test
    @DisplayName("recalcularPrecio con el rubro en 50: costo 100 -> 100 y precio 170 -> 190")
    void recalcularPrecioConOtroMargenDeRubro() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx("35"));

        Optional<CambioPrecio> cambio = articulo.recalcularPrecio(ctx("50"));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("190.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("100");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("recalcularPrecio con rubro 36: el precio redondeado sigue en 170 y no hay cambio")
    void recalcularPrecioQueNoCambia() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx("35"));

        Optional<CambioPrecio> cambio = articulo.recalcularPrecio(ctx("36"));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
        assertThat(cambio).isEmpty();
    }

    @Test
    @DisplayName("recalcularPrecio usa el multiplo del contexto: con 50 el 163,35 sube a 200")
    void recalcularPrecioUsaElMultiploDelContexto() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.recalcularPrecio(
                new ContextoPrecio(null, bd("30"), bd("50")));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("200.00"));
        assertThat(cambio).isPresent();
    }

    // --- baja logica ---

    @Test
    @DisplayName("desactivar pone activo en false y conserva los demas datos")
    void desactivar() {
        Articulo articulo = articuloCon("100", "35");

        articulo.desactivar();

        assertThat(articulo.isActivo()).isFalse();
        assertThat(articulo.getCodigoInterno()).isEqualTo("TORN-001");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
    }

    @Test
    @DisplayName("actualizar con activo = true reactiva un articulo dado de baja")
    void reactivarPorEdicion() {
        Articulo articulo = articuloCon("100", "35");
        articulo.desactivar();

        articulo.actualizar(datos("100", "35", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.isActivo()).isTrue();
    }

    // --- importacion de listas: actualizarCosto / registrarPrecioLista ---

    @Test
    @DisplayName("actualizarCosto 100 -> 120 con margen 35: el precio pasa de 170 a 200")
    void actualizarCostoRecalculaElPrecio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizarCosto(bd("120"), bd("150"), ctx(null));

        assertThat(articulo.getCosto()).isEqualByComparingTo("120");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("200.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("120");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("actualizarCosto con margen propio 50: costo 120 da precio 220")
    void actualizarCostoConMargenPropio() {
        Articulo articulo = articuloCon("100", "50");

        articulo.actualizarCosto(bd("120"), bd("150"), ctx(null));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("220.00"));
    }

    @Test
    @DisplayName("actualizarCosto usa el margen del rubro del contexto cuando no hay margen propio")
    void actualizarCostoHeredaMargenDelRubro() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos("100", null, AlicuotaIva.IVA_21), ctx("35"));

        articulo.actualizarCosto(bd("120"), bd("150"), ctx("35"));

        assertThat(articulo.getMargenPorcentaje()).isNull();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("200.00"));
    }

    @Test
    @DisplayName("actualizarCosto 100 -> 100,50: el precio sigue en 170 y devuelve el cambio de costo")
    void actualizarCostoQueNoMueveElPrecioDevuelveCambio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizarCosto(bd("100.50"), bd("120"), ctx(null));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("100.50");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("170");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("170");
    }

    @Test
    @DisplayName("actualizarCosto con el mismo costo (100 y 100.00) no devuelve cambio pero fija el precio de lista")
    void actualizarCostoIgualNoDaCambio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.actualizarCosto(bd("100.00"), bd("1210"), ctx(null));

        assertThat(cambio).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1210");
    }

    @Test
    @DisplayName("actualizarCosto sobre un articulo inactivo lo deja inactivo")
    void actualizarCostoConservaInactivo() {
        Articulo articulo = articuloCon("100", "35");
        articulo.desactivar();

        articulo.actualizarCosto(bd("120"), bd("150"), ctx(null));

        assertThat(articulo.isActivo()).isFalse();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("200.00"));
    }

    @Test
    @DisplayName("actualizarCosto no toca descripcion, rubro, margen, IVA, unidad, stock ni ubicacion")
    void actualizarCostoNoTocaLosDemasDatos() throws Exception {
        Articulo articulo = articuloCon("100", "35");
        fijarStockActual(articulo, "12");

        articulo.actualizarCosto(bd("120"), bd("150"), ctx(null));

        assertThat(articulo.getCodigoInterno()).isEqualTo("TORN-001");
        assertThat(articulo.getCodigoBarras()).isEqualTo("7790001000012");
        assertThat(articulo.getCodigoProveedor()).isEqualTo("AB-12");
        assertThat(articulo.getDescripcion()).isEqualTo("Tornillo hexagonal 1/4");
        assertThat(articulo.getRubroId()).isEqualTo(10L);
        assertThat(articulo.getProveedorId()).isEqualTo(20L);
        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("35");
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(articulo.getUnidad()).isEqualTo(Unidad.UNIDAD);
        assertThat(articulo.getStockMinimo()).isEqualByComparingTo("5");
        assertThat(articulo.getStockActual()).isEqualByComparingTo("12");
        assertThat(articulo.getUbicacion()).isEqualTo("Estante 3");
    }

    @Test
    @DisplayName("actualizarCosto guarda el precio de lista a escala 2 HALF_UP")
    void actualizarCostoGuardaPrecioListaRedondeado() {
        Articulo articulo = articuloCon("100", "35");

        articulo.actualizarCosto(bd("120"), bd("1210.005"), ctx(null));

        assertThat(articulo.getPrecioListaProveedor()).isEqualTo(bd("1210.01"));
    }

    @Test
    @DisplayName("actualizarCosto con costo negativo o null lanza IllegalArgumentException")
    void actualizarCostoInvalido() {
        Articulo articulo = articuloCon("100", "35");

        assertThatThrownBy(() -> articulo.actualizarCosto(bd("-1"), bd("150"), ctx(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> articulo.actualizarCosto(null, bd("150"), ctx(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("registrarPrecioLista guarda a escala 2 HALF_UP y no genera cambio de precio")
    void registrarPrecioListaSoloGuardaElPrecioDeLista() {
        Articulo articulo = articuloCon("100", "35");

        articulo.registrarPrecioLista(bd("99.995"));

        assertThat(articulo.getPrecioListaProveedor()).isEqualTo(bd("100.00"));
        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
    }

    @Test
    @DisplayName("un articulo nuevo tiene precioListaProveedor null")
    void precioListaNuloPorDefecto() {
        assertThat(articuloCon("100", "35").getPrecioListaProveedor()).isNull();
    }

    @Test
    @DisplayName("actualizar (edicion de CU-03) no toca el precio de lista del proveedor")
    void actualizarNoTocaElPrecioDeLista() {
        Articulo articulo = articuloCon("100", "35");
        articulo.registrarPrecioLista(bd("1210"));

        articulo.actualizar(datos("200", "50", AlicuotaIva.IVA_21), ctx(null));
        articulo.recalcularPrecio(ctx("40"));

        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1210");
    }

    // --- actualizacion masiva (CU-05): ajustarCosto ---

    private static void fijarCampo(Articulo articulo, String campo, Object valor) throws Exception {
        Field f = Articulo.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(articulo, valor);
    }

    @Test
    @DisplayName("ajustarCosto 100 -> 110 con margen por defecto 30: el precio sube de 160 a 180 (173,03 al multiplo)")
    void ajustarCostoRecalculaYRedondeaHaciaArriba() {
        Articulo articulo = articuloCon("100", null);

        Optional<CambioPrecio> cambio = articulo.ajustarCosto(bd("110.00"), ctx(null));

        assertThat(articulo.getCosto()).isEqualByComparingTo("110");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("180.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("110");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("160");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("ajustarCosto conserva el margen propio 50 y el margen propio nulo")
    void ajustarCostoConservaElMargenPropio() {
        Articulo conPropio = articuloCon("100", "50");
        Articulo sinPropio = articuloCon("100", null);

        conPropio.ajustarCosto(bd("110.00"), ctx(null));
        sinPropio.ajustarCosto(bd("110.00"), ctx(null));

        assertThat(conPropio.getMargenPorcentaje()).isEqualByComparingTo("50");
        assertThat(conPropio.getPrecioFinal()).isEqualTo(bd("200.00"));
        assertThat(sinPropio.getMargenPorcentaje()).isNull();
    }

    @Test
    @DisplayName("ajustarCosto usa el margen del rubro del contexto: 110 con margen 40 da 190")
    void ajustarCostoHeredaElMargenDelRubro() {
        Articulo articulo = articuloCon("100", null);

        articulo.ajustarCosto(bd("110.00"), ctx("40"));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("190.00"));
    }

    @Test
    @DisplayName("ajustarCosto devuelve vacio si ni el costo ni el precio cambian")
    void ajustarCostoSinCambio() {
        Articulo articulo = articuloCon("100", null);

        Optional<CambioPrecio> cambio = articulo.ajustarCosto(bd("100.00"), ctx(null));

        assertThat(cambio).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("160.00"));
    }

    @Test
    @DisplayName("ajustarCosto recalcula un precio desactualizado aunque el costo no cambie (0.04: 20.00 -> 10.00)")
    void ajustarCostoRecalculaPrecioDesactualizado() throws Exception {
        Articulo articulo = articuloCon("0.04", null);
        fijarCampo(articulo, "precioFinal", bd("20.00"));

        Optional<CambioPrecio> cambio = articulo.ajustarCosto(bd("0.04"), ctx(null));

        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("10.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("20");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("ajustarCosto no toca precio de lista, descripcion, IVA, stock, ubicacion ni activo")
    void ajustarCostoNoTocaLosDemasDatos() throws Exception {
        Articulo articulo = articuloCon("100", "35");
        articulo.registrarPrecioLista(bd("1210"));
        fijarStockActual(articulo, "12");

        articulo.ajustarCosto(bd("110.00"), ctx(null));

        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1210");
        assertThat(articulo.getDescripcion()).isEqualTo("Tornillo hexagonal 1/4");
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(articulo.getStockActual()).isEqualByComparingTo("12");
        assertThat(articulo.getStockMinimo()).isEqualByComparingTo("5");
        assertThat(articulo.getUbicacion()).isEqualTo("Estante 3");
        assertThat(articulo.getRubroId()).isEqualTo(10L);
        assertThat(articulo.getProveedorId()).isEqualTo(20L);
        assertThat(articulo.getUnidad()).isEqualTo(Unidad.UNIDAD);
        assertThat(articulo.isActivo()).isTrue();
    }

    @Test
    @DisplayName("ajustarCosto no necesita precio de lista: con precio de lista nulo sigue nulo")
    void ajustarCostoConPrecioListaNulo() {
        Articulo articulo = articuloCon("100", null);

        articulo.ajustarCosto(bd("110.00"), ctx(null));

        assertThat(articulo.getPrecioListaProveedor()).isNull();
    }

    @Test
    @DisplayName("ajustarCosto sobre un articulo inactivo lo deja inactivo")
    void ajustarCostoConservaInactivo() {
        Articulo articulo = articuloCon("100", null);
        articulo.desactivar();

        articulo.ajustarCosto(bd("110.00"), ctx(null));

        assertThat(articulo.isActivo()).isFalse();
    }

    @Test
    @DisplayName("ajustarCosto con costo negativo o null lanza IllegalArgumentException con el mensaje del costo")
    void ajustarCostoInvalido() {
        Articulo articulo = articuloCon("100", null);

        assertThatThrownBy(() -> articulo.ajustarCosto(bd("-0.01"), ctx(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El costo debe ser un valor mayor o igual a 0.");
        assertThatThrownBy(() -> articulo.ajustarCosto(null, ctx(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El costo debe ser un valor mayor o igual a 0.");
        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
    }

    // --- actualizacion masiva (CU-05): fijarMargen ---

    @Test
    @DisplayName("fijarMargen 50 sobre costo 100 que heredaba el 30: margen propio 50 y precio 160 -> 190")
    void fijarMargenFijaElMargenPropioYRecalcula() {
        Articulo articulo = articuloCon("100", null);

        Optional<CambioPrecio> cambio = articulo.fijarMargen(bd("50.00"), ctx(null));

        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("50");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("190.00"));
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoAnterior()).isEqualByComparingTo("100");
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("100");
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("160");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("190");
    }

    @Test
    @DisplayName("fijarMargen pisa el margen del rubro: con rubro al 40, margen 50 da 190 y queda propio")
    void fijarMargenPisaElMargenDelRubro() {
        Articulo articulo = articuloCon("100", null);

        articulo.fijarMargen(bd("50"), ctx("40"));

        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("50");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("190.00"));
    }

    @Test
    @DisplayName("fijarMargen 0 se respeta (no es null): costo 100 da precio 130")
    void fijarMargenCeroSeRespeta() {
        Articulo articulo = articuloCon("100", null);

        articulo.fijarMargen(bd("0"), ctx(null));

        assertThat(articulo.getMargenPorcentaje()).isNotNull();
        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("0");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("130.00"));
    }

    @Test
    @DisplayName("fijarMargen 1000 es valido")
    void fijarMargenMil() {
        Articulo articulo = articuloCon("100", null);

        articulo.fijarMargen(bd("1000"), ctx(null));

        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("fijarMargen fuera de 0..1000 o null lanza IllegalArgumentException y no cambia nada")
    void fijarMargenInvalido() {
        Articulo articulo = articuloCon("100", "35");

        assertThatThrownBy(() -> articulo.fijarMargen(bd("-0.01"), ctx(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El margen debe estar entre 0 y 1000.");
        assertThatThrownBy(() -> articulo.fijarMargen(bd("1000.01"), ctx(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El margen debe estar entre 0 y 1000.");
        assertThatThrownBy(() -> articulo.fijarMargen(null, ctx(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El margen debe estar entre 0 y 1000.");
        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("35");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
    }

    @Test
    @DisplayName("fijarMargen no cambia el costo")
    void fijarMargenNoCambiaElCosto() {
        Articulo articulo = articuloCon("123.45", null);

        articulo.fijarMargen(bd("50"), ctx(null));

        assertThat(articulo.getCosto()).isEqualTo(bd("123.45"));
    }

    @Test
    @DisplayName("fijarMargen que cambia solo el margen propio (35 -> 36, precio 170 igual) devuelve vacio")
    void fijarMargenQueNoMueveElPrecioDevuelveVacio() {
        Articulo articulo = articuloCon("100", "35");

        Optional<CambioPrecio> cambio = articulo.fijarMargen(bd("36"), ctx(null));

        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("36");
        assertThat(articulo.getPrecioFinal()).isEqualTo(bd("170.00"));
        assertThat(cambio).isEmpty();
    }

    @Test
    @DisplayName("fijarMargen no toca el precio de lista ni los demas datos")
    void fijarMargenNoTocaLosDemasDatos() throws Exception {
        Articulo articulo = articuloCon("100", null);
        articulo.registrarPrecioLista(bd("1210"));
        fijarStockActual(articulo, "12");
        articulo.desactivar();

        articulo.fijarMargen(bd("50"), ctx(null));

        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1210");
        assertThat(articulo.getDescripcion()).isEqualTo("Tornillo hexagonal 1/4");
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(articulo.getStockActual()).isEqualByComparingTo("12");
        assertThat(articulo.getUbicacion()).isEqualTo("Estante 3");
        assertThat(articulo.isActivo()).isFalse();
    }

    // --- moneda ---

    /** Contexto con margen por defecto 30, multiplo 10 y el dolar indicado. */
    private static ContextoPrecio ctxConDolar(String dolar) {
        return new ContextoPrecio(null, bd("30"), bd("10"), dolar == null ? null : bd(dolar));
    }

    private static DatosArticulo datosEn(Moneda moneda, String costo) {
        return new DatosArticulo("USD-001", null, null, "Articulo en dolares",
                null, null, bd(costo), null, AlicuotaIva.IVA_21, Unidad.UNIDAD,
                bd("0"), null, true, moneda);
    }

    @Test
    @DisplayName("moneda: un articulo nuevo esta en pesos")
    void articuloNuevoEsEnPesos() {
        assertThat(new Articulo(FERRETERIA).getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(articuloCon("100", "35").getMoneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("moneda: 10 USD con margen 30, IVA 21 y multiplo 10 a 1000 pesos por dolar da 15730")
    void articuloEnDolaresConDolarA1000() {
        Articulo articulo = new Articulo(FERRETERIA);

        Optional<CambioPrecio> cambio = articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
        assertThat(articulo.getCosto()).isEqualByComparingTo("10");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730.00");
        assertThat(cambio).isPresent();
        assertThat(cambio.get().costoNuevo()).isEqualByComparingTo("10");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("15730.00");
    }

    @Test
    @DisplayName("moneda: el mismo articulo con el dolar a 1100 recalcula a 17310 y el costo en dolares no se toca")
    void articuloEnDolaresConDolarA1100() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        Optional<CambioPrecio> cambio = articulo.recalcularPrecio(ctxConDolar("1100"));

        assertThat(articulo.getCosto()).isEqualByComparingTo("10");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("17310.00");
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("15730.00");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("17310.00");
    }

    @Test
    @DisplayName("moneda: con el dolar sin cambio el recalculo no devuelve cambio")
    void recalculoSinCambioDeDolar() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        assertThat(articulo.recalcularPrecio(ctxConDolar("1000"))).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730.00");
    }

    @Test
    @DisplayName("moneda: un articulo en dolares con un contexto sin dolar lanza IllegalStateException")
    void articuloEnDolaresSinDolar() {
        Articulo articulo = new Articulo(FERRETERIA);

        assertThatThrownBy(() -> articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("moneda: un articulo en pesos no usa la cotizacion: costo 1000 da 1580 con o sin dolar")
    void articuloEnPesosNoUsaElDolar() {
        Articulo conDolar = new Articulo(FERRETERIA);
        conDolar.actualizar(datosEn(Moneda.ARS, "1000"), ctxConDolar("1000"));
        Articulo sinDolar = new Articulo(FERRETERIA);
        sinDolar.actualizar(datosEn(Moneda.ARS, "1000"), ctxConDolar(null));

        assertThat(conDolar.getPrecioFinal()).isEqualByComparingTo("1580.00");
        assertThat(sinDolar.getPrecioFinal()).isEqualByComparingTo("1580.00");
    }

    @Test
    @DisplayName("moneda: pasar de pesos a dolares deja el costo tal cual, sin convertir, y recalcula")
    void cambiarLaMonedaNoConvierteElCosto() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datosEn(Moneda.ARS, "10"), ctxConDolar("1000"));
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("20.00");

        Optional<CambioPrecio> cambio = articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
        assertThat(articulo.getCosto()).isEqualByComparingTo("10");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730.00");
        assertThat(cambio).isPresent();
        assertThat(cambio.get().precioAnterior()).isEqualByComparingTo("20.00");
        assertThat(cambio.get().precioNuevo()).isEqualByComparingTo("15730.00");
    }

    @Test
    @DisplayName("moneda: el constructor de 13 campos de DatosArticulo deja la moneda en pesos")
    void datosArticuloSinMonedaEsEnPesos() {
        assertThat(datos("100", "35", AlicuotaIva.IVA_21).moneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("moneda: un DatosArticulo con moneda null queda en pesos")
    void datosArticuloConMonedaNull() {
        Articulo articulo = new Articulo(FERRETERIA);

        articulo.actualizar(datosEn(null, "100"), ctxConDolar(null));

        assertThat(articulo.getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("160.00");
    }

    @Test
    @DisplayName("moneda: ajustarCosto, fijarMargen y actualizarCosto de un USD usan el dolar del contexto")
    void ajustesDeUnArticuloEnDolares() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        articulo.ajustarCosto(bd("11"), ctxConDolar("1000"));
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("17310.00");
        articulo.fijarMargen(bd("40"), ctxConDolar("1000"));
        // 11 x 1000 x 1.4 x 1.21 = 18634 -> 18640
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("18640.00");
        articulo.actualizarCosto(bd("10"), bd("12.10"), ctxConDolar("1100"));
        // 10 x 1100 x 1.4 x 1.21 = 18634 -> 18640
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("18640.00");
    }
}
