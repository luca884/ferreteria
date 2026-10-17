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

    // --- corte 2: aplicarLista (moneda, IVA y ajustes desde la lista), ajustes y ultima importacion ---

    /** Articulo ARS con costo 1.000, margen 30 %, IVA 21 %, multiplo 10: precio $ 1.580. */
    private static Articulo articuloEnPesos(String costo) {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos(costo, null, AlicuotaIva.IVA_21), ctx(null));
        return articulo;
    }

    @Test
    @DisplayName("aplicarLista: ARS a USD con costo 10 y dolar 1.000 da precio 15.730 y monedaAnterior ARS")
    void aplicarListaCambiaDePesosADolares() {
        Articulo articulo = articuloEnPesos("1000");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1580");

        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("10"), bd("10"), Moneda.USD, null, Ajustes.NINGUNO,
            7L, ctxConDolar("1000"));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().monedaAnterior()).isEqualTo(Moneda.ARS);
        assertThat(cambio.get().cambio().costoAnterior()).isEqualByComparingTo("1000");
        assertThat(cambio.get().cambio().costoNuevo()).isEqualByComparingTo("10");
        assertThat(cambio.get().cambio().precioAnterior()).isEqualByComparingTo("1580");
        assertThat(cambio.get().cambio().precioNuevo()).isEqualByComparingTo("15730");
        assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
        assertThat(articulo.getCosto()).isEqualByComparingTo("10");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730");
        assertThat(articulo.getUltimaImportacionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("aplicarLista: de USD a ARS con lista 12.000 da precio 18.880 y monedaAnterior USD")
    void aplicarListaCambiaDeDolaresAPesos() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("12000"), bd("12000"), Moneda.ARS, null,
            Ajustes.NINGUNO, 7L, ctxConDolar("1000"));

        assertThat(cambio.get().monedaAnterior()).isEqualTo(Moneda.USD);
        assertThat(articulo.getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("18880");
    }

    @Test
    @DisplayName("aplicarLista: un cambio de moneda se fuerza aunque el costo y el precio queden iguales")
    void aplicarListaForzaElCambioDeMoneda() {
        Articulo articulo = articuloEnPesos("10");
        BigDecimal precioAntes = articulo.getPrecioFinal();

        // Con el dolar a 1 un costo de 10 USD da el mismo precio que 10 pesos: los numeros coinciden.
        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("10"), bd("10"), Moneda.USD, null, Ajustes.NINGUNO,
            7L, ctxConDolar("1"));

        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo(precioAntes);
        assertThat(cambio).isPresent();
        assertThat(cambio.get().monedaAnterior()).isEqualTo(Moneda.ARS);
        assertThat(cambio.get().cambio().costoAnterior()).isEqualByComparingTo("10");
        assertThat(cambio.get().cambio().costoNuevo()).isEqualByComparingTo("10");
        assertThat(cambio.get().cambio().precioAnterior()).isEqualByComparingTo(precioAntes);
        assertThat(cambio.get().cambio().precioNuevo()).isEqualByComparingTo(precioAntes);
    }

    @Test
    @DisplayName("aplicarLista: de IVA_21 a IVA_10_5 con costo 10.000 el precio pasa de 15.730 a 14.370")
    void aplicarListaCorrigeLaAlicuota() {
        Articulo articulo = articuloEnPesos("10000");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730");

        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("10000"), bd("10000"), null, AlicuotaIva.IVA_10_5,
            Ajustes.NINGUNO, 7L, ctx(null));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().monedaAnterior()).isNull();
        assertThat(cambio.get().cambio().costoAnterior()).isEqualByComparingTo("10000");
        assertThat(cambio.get().cambio().costoNuevo()).isEqualByComparingTo("10000");
        assertThat(cambio.get().cambio().precioAnterior()).isEqualByComparingTo("15730");
        assertThat(cambio.get().cambio().precioNuevo()).isEqualByComparingTo("14370");
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
    }

    @Test
    @DisplayName("aplicarLista: sin moneda ni alicuota nuevas conserva las del articulo")
    void aplicarListaConservaMonedaYAlicuota() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datosEn(Moneda.USD, "10"), ctxConDolar("1000"));

        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("12"), bd("12"), null, null, Ajustes.NINGUNO, 7L,
            ctxConDolar("1000"));

        assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(cambio.get().monedaAnterior()).isNull();
        // 12 x 1000 x 1,30 x 1,21 = 18.876 -> 18.880
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("18880");
    }

    @Test
    @DisplayName("aplicarLista: la misma moneda con otro costo es un cambio normal, sin monedaAnterior")
    void aplicarListaSube() {
        Articulo articulo = articuloEnPesos("1000");

        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("1200"), bd("1200"), Moneda.ARS, null,
            Ajustes.NINGUNO, 7L, ctx(null));

        assertThat(cambio).isPresent();
        assertThat(cambio.get().monedaAnterior()).isNull();
        assertThat(cambio.get().cambio().costoNuevo()).isEqualByComparingTo("1200");
        assertThat(cambio.get().cambio().precioNuevo()).isEqualByComparingTo("1890");
    }

    @Test
    @DisplayName("aplicarLista: si ni el costo ni el precio final cambian no hay cambio, pero igual marca la importacion")
    void aplicarListaSinCambio() {
        Articulo articulo = articuloEnPesos("1000");

        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("1000.00"), bd("1210"), Moneda.ARS,
            AlicuotaIva.IVA_21, Ajustes.NINGUNO, 7L, ctx(null));

        assertThat(cambio).isEmpty();
        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1210");
        assertThat(articulo.getUltimaImportacionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("aplicarLista: una correccion de IVA que no mueve el precio final redondeado no da cambio")
    void aplicarListaCorreccionQueNoMueveElPrecio() {
        Articulo articulo = articuloEnPesos("1");
        BigDecimal precioAntes = articulo.getPrecioFinal();

        // 1 x 1,30 x 1,21 = 1,573 y 1 x 1,30 x 1,105 = 1,4365: los dos suben a $ 10 con multiplo 10.
        Optional<CambioDeLista> cambio = articulo.aplicarLista(bd("1"), bd("1"), null, AlicuotaIva.IVA_10_5,
            Ajustes.NINGUNO, 7L, ctx(null));

        assertThat(cambio).isEmpty();
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo(precioAntes);
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
    }

    @Test
    @DisplayName("aplicarLista: fija los ajustes que le llegan (-5, +3, ninguno) y el precio de lista a escala 2")
    void aplicarListaFijaLosAjustes() {
        Articulo articulo = articuloEnPesos("1000");

        articulo.aplicarLista(bd("880.65"), bd("1000.005"), null, null, new Ajustes(bd("-5"), bd("3"), null), 7L,
            ctx(null));

        assertThat(articulo.getAjuste1()).isEqualByComparingTo("-5");
        assertThat(articulo.getAjuste2()).isEqualByComparingTo("3");
        assertThat(articulo.getAjuste3()).isNull();
        assertThat(articulo.getAjustes()).isEqualTo(new Ajustes(bd("-5"), bd("3"), null));
        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1000.01");
    }

    @Test
    @DisplayName("aplicarLista: Ajustes.NINGUNO borra los ajustes que el articulo tenia")
    void aplicarListaBorraLosAjustes() {
        Articulo articulo = articuloEnPesos("1000");
        articulo.fijarAjustes(new Ajustes(bd("-5"), null, bd("2")));

        articulo.aplicarLista(bd("1000"), bd("1000"), null, null, Ajustes.NINGUNO, 7L, ctx(null));

        assertThat(articulo.getAjustes()).isEqualTo(Ajustes.NINGUNO);
    }

    @Test
    @DisplayName("aplicarLista: costo negativo o null lanza IllegalArgumentException y no cambia nada")
    void aplicarListaInvalido() {
        Articulo articulo = articuloEnPesos("1000");

        assertThatThrownBy(() -> articulo.aplicarLista(bd("-1"), bd("1"), null, null, Ajustes.NINGUNO, 7L, ctx(null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> articulo.aplicarLista(null, bd("1"), null, null, Ajustes.NINGUNO, 7L, ctx(null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(articulo.getCosto()).isEqualByComparingTo("1000");
        assertThat(articulo.getUltimaImportacionId()).isNull();
    }

    @Test
    @DisplayName("marcarImportacion(7) fija la ultima importacion; un articulo creado a mano la tiene en null")
    void marcarImportacion() {
        Articulo aMano = articuloEnPesos("1000");
        assertThat(aMano.getUltimaImportacionId()).isNull();

        aMano.marcarImportacion(7L);
        assertThat(aMano.getUltimaImportacionId()).isEqualTo(7L);
        aMano.marcarImportacion(9L);
        assertThat(aMano.getUltimaImportacionId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("un articulo nuevo no tiene ajustes")
    void sinAjustesPorDefecto() {
        Articulo articulo = articuloEnPesos("1000");

        assertThat(articulo.getAjuste1()).isNull();
        assertThat(articulo.getAjuste2()).isNull();
        assertThat(articulo.getAjuste3()).isNull();
        assertThat(articulo.getAjustes()).isEqualTo(Ajustes.NINGUNO);
    }

    @Test
    @DisplayName("alta desde la lista: barras y unidad por DatosArticulo, ajustes con fijarAjustes")
    void altaConAjustesBarrasYUnidad() {
        Articulo articulo = new Articulo(FERRETERIA);
        DatosArticulo datos = new DatosArticulo("TORN-009", "7791234567890", "AB-12", "Tornillo por metro", null, 20L,
            bd("880.65"), null, AlicuotaIva.IVA_10_5, Unidad.METRO, bd("0"), null, true, Moneda.ARS);

        articulo.actualizar(datos, ctx(null));
        articulo.fijarAjustes(new Ajustes(bd("-5"), bd("3"), null));
        articulo.marcarImportacion(7L);

        assertThat(articulo.getCodigoBarras()).isEqualTo("7791234567890");
        assertThat(articulo.getUnidad()).isEqualTo(Unidad.METRO);
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(articulo.getAjuste1()).isEqualByComparingTo("-5");
        assertThat(articulo.getAjuste2()).isEqualByComparingTo("3");
        assertThat(articulo.getUltimaImportacionId()).isEqualTo(7L);
        // 880,65 x 1,30 x 1,105 = 1.265,0... -> 1.270
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1270");
    }

    @Test
    @DisplayName("actualizar (el PUT) no toca los ajustes ni la ultima importacion")
    void actualizarNoTocaLosAjustes() {
        Articulo articulo = articuloEnPesos("1000");
        articulo.fijarAjustes(new Ajustes(bd("-5"), bd("3"), bd("1.5")));
        articulo.marcarImportacion(7L);

        articulo.actualizar(datos("1200", "35", AlicuotaIva.IVA_21), ctx(null));

        assertThat(articulo.getAjustes()).isEqualTo(new Ajustes(bd("-5"), bd("3"), bd("1.5")));
        assertThat(articulo.getUltimaImportacionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("actualizarCosto sigue andando y tampoco toca los ajustes")
    void actualizarCostoNoTocaLosAjustes() {
        Articulo articulo = articuloEnPesos("1000");
        articulo.fijarAjustes(new Ajustes(bd("-5"), null, null));

        Optional<CambioPrecio> cambio = articulo.actualizarCosto(bd("1100"), bd("1100"), ctx(null));

        assertThat(cambio).isPresent();
        assertThat(articulo.getAjuste1()).isEqualByComparingTo("-5");
    }

    // --- base de lista guardada (corte 3, V7) ---

    @Test
    @DisplayName("registrarBaseDeLista: fija las tres bonificaciones (null cuando falta) y listaIncluyeIva")
    void registrarBaseDeLista() {
        Articulo articulo = articuloCon("100", null);
        assertThat(articulo.getBonificacion1()).isNull();
        assertThat(articulo.getListaIncluyeIva()).isNull();
        assertThat(articulo.bonificacionesGuardadas()).isEqualTo(Bonificaciones.NINGUNA);

        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, bd("2.5")), true);

        assertThat(articulo.getBonificacion1()).isEqualByComparingTo("10");
        assertThat(articulo.getBonificacion2()).isNull();
        assertThat(articulo.getBonificacion3()).isEqualByComparingTo("2.5");
        assertThat(articulo.getListaIncluyeIva()).isTrue();
        assertThat(articulo.bonificacionesGuardadas()).isEqualTo(new Bonificaciones(bd("10"), null, bd("2.5")));
    }

    @Test
    @DisplayName("registrarBaseDeLista: sin bonificaciones (null o NINGUNA) deja las tres en null y puede fijar false")
    void registrarBaseDeListaSinBonificaciones() {
        Articulo articulo = articuloCon("100", null);
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), bd("5"), bd("1")), true);

        articulo.registrarBaseDeLista(null, false);

        assertThat(articulo.bonificacionesGuardadas()).isEqualTo(Bonificaciones.NINGUNA);
        assertThat(articulo.getListaIncluyeIva()).isFalse();
    }

    @Test
    @DisplayName("actualizar (el PUT, tambien el que saca el proveedor), ajustarCosto y fijarMargen no tocan la base guardada")
    void laBaseGuardadaSobreviveALasOtrasEdiciones() {
        Articulo articulo = articuloCon("100", null);
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), bd("5"), null), true);

        articulo.actualizar(new DatosArticulo("TORN-001", "7790001000012", "AB-12", "Otra descripcion",
                10L, null, bd("120"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true), ctx(null));
        articulo.ajustarCosto(bd("130"), ctx(null));
        articulo.fijarMargen(bd("40"), ctx(null));

        assertThat(articulo.getProveedorId()).isNull();
        assertThat(articulo.bonificacionesGuardadas()).isEqualTo(new Bonificaciones(bd("10"), bd("5"), null));
        assertThat(articulo.getListaIncluyeIva()).isTrue();
    }

    @Test
    @DisplayName("aplicarLista conserva su firma y no toca la base guardada (la fija la importacion con registrarBaseDeLista)")
    void aplicarListaNoTocaLaBase() {
        Articulo articulo = articuloCon("100", null);
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);

        articulo.aplicarLista(bd("1200"), bd("1200"), Moneda.ARS, null, Ajustes.NINGUNO, 7L, ctx(null));

        assertThat(articulo.bonificacionesGuardadas()).isEqualTo(new Bonificaciones(bd("10"), null, null));
        assertThat(articulo.getListaIncluyeIva()).isFalse();
    }

    // --- tieneLista y costoDeLista (corte 3) ---

    /** Articulo que vino de una lista: proveedor 20, precio de lista, base guardada y costo ya calculado. */
    private static Articulo importado(String lista, Bonificaciones bonificaciones, boolean incluyeIva, Ajustes ajustes,
                                      AlicuotaIva iva) {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos("1", null, iva), ctx(null));
        BigDecimal costo = CostoDeLista.costo(bd(lista), iva, incluyeIva, bonificaciones, ajustes);
        articulo.aplicarLista(costo, bd(lista), null, null, ajustes, 7L, ctx(null));
        articulo.registrarBaseDeLista(bonificaciones, incluyeIva);
        return articulo;
    }

    private static Articulo importado(String lista, Bonificaciones bonificaciones, Ajustes ajustes) {
        return importado(lista, bonificaciones, false, ajustes, AlicuotaIva.IVA_21);
    }

    @Test
    @DisplayName("tieneLista: true con precio de lista, proveedor y base guardada")
    void tieneListaCompleta() {
        assertThat(importado("1000", Bonificaciones.NINGUNA, Ajustes.NINGUNO).tieneLista()).isTrue();
    }

    @Test
    @DisplayName("tieneLista: false en alta manual (sin precio de lista) aunque tenga proveedor")
    void tieneListaFalsaEnAltaManual() {
        Articulo articulo = articuloCon("100", null);
        assertThat(articulo.getProveedorId()).isNotNull();
        assertThat(articulo.tieneLista()).isFalse();
        assertThat(articulo.costoDeLista(Ajustes.NINGUNO)).isEmpty();
    }

    @Test
    @DisplayName("tieneLista (decision 46): false con precio de lista y base pero SIN proveedor")
    void tieneListaFalsaSinProveedor() {
        Articulo articulo = importado("1000", new Bonificaciones(bd("10"), null, null), Ajustes.NINGUNO);
        articulo.actualizar(new DatosArticulo("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4",
                10L, null, bd("900"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true), ctx(null));

        assertThat(articulo.getPrecioListaProveedor()).isNotNull();
        assertThat(articulo.tieneLista()).isFalse();
        assertThat(articulo.costoDeLista(Ajustes.NINGUNO)).isEmpty();
    }

    @Test
    @DisplayName("tieneLista: false con proveedor y precio de lista pero sin base guardada (anterior a V7 sin proveedor)")
    void tieneListaFalsaSinBase() {
        Articulo articulo = articuloCon("100", null);
        articulo.registrarPrecioLista(bd("100"));

        assertThat(articulo.getListaIncluyeIva()).isNull();
        assertThat(articulo.tieneLista()).isFalse();
    }

    @Test
    @DisplayName("tieneLista: al volver a poner el proveedor se habilita si hay base guardada y sigue en false si no")
    void tieneListaAlVolverALePonerProveedor() {
        Articulo conBase = importado("1000", new Bonificaciones(bd("10"), null, null), Ajustes.NINGUNO);
        Articulo sinBase = articuloCon("100", null);
        sinBase.registrarPrecioLista(bd("100"));
        DatosArticulo sinProveedor = new DatosArticulo("TORN-001", "7790001000012", "AB-12", "Tornillo", 10L, null,
                bd("900"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true);
        DatosArticulo conProveedor = new DatosArticulo("TORN-001", "7790001000012", "AB-12", "Tornillo", 10L, 20L,
                bd("900"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true);

        conBase.actualizar(sinProveedor, ctx(null));
        assertThat(conBase.tieneLista()).isFalse();
        conBase.actualizar(conProveedor, ctx(null));
        assertThat(conBase.tieneLista()).isTrue();

        sinBase.actualizar(sinProveedor, ctx(null));
        sinBase.actualizar(conProveedor, ctx(null));
        assertThat(sinBase.tieneLista()).isFalse();
    }

    @Test
    @DisplayName("costoDeLista: lista 1.000,00, bonificacion 10, ajustes -5 y +3 da 880,65")
    void costoDeListaConAjustes() {
        Articulo articulo = importado("1000", new Bonificaciones(bd("10"), null, null), Ajustes.NINGUNO);

        assertThat(articulo.costoDeLista(new Ajustes(bd("-5"), bd("3"), null))).get()
                .satisfies(c -> assertThat(c).isEqualByComparingTo("880.65"));
        assertThat(articulo.costoDeLista(Ajustes.NINGUNO)).get()
                .satisfies(c -> assertThat(c).isEqualByComparingTo("900.00"));
    }

    @Test
    @DisplayName("costoDeLista: usa listaIncluyeIva GUARDADO (1.210,00 con IVA 21 y +10 da 1.100,00)")
    void costoDeListaConIvaIncluidoGuardado() {
        Articulo articulo = importado("1210", Bonificaciones.NINGUNA, true, Ajustes.NINGUNO, AlicuotaIva.IVA_21);

        assertThat(articulo.costoDeLista(new Ajustes(bd("10"), null, null))).get()
                .satisfies(c -> assertThat(c).isEqualByComparingTo("1100.00"));
    }

    @Test
    @DisplayName("costoDeLista: usa las bonificaciones GUARDADAS (10) y no las del proveedor (12): 900,00")
    void costoDeListaConBonificacionGuardada() {
        Articulo articulo = importado("1000", new Bonificaciones(bd("10"), null, null), Ajustes.NINGUNO);

        assertThat(articulo.costoDeLista(Ajustes.NINGUNO)).get()
                .satisfies(c -> assertThat(c).isEqualByComparingTo("900.00"));
    }

    // --- editarAjustes (corte 3) ---

    private static final Bonificaciones BONIF_10 = new Bonificaciones(bd("10"), null, null);

    @Test
    @DisplayName("editarAjustes: quitar el +3 (880,65 / 1.390) da 855,00 / 1.350 y el cambio de costo y precio")
    void editarAjustesQuitarUno() {
        Articulo articulo = importado("1000", BONIF_10, new Ajustes(bd("-5"), bd("3"), null));
        assertThat(articulo.getCosto()).isEqualByComparingTo("880.65");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1390");

        Optional<CambioPrecio> cambio = articulo.editarAjustes(new Ajustes(bd("-5"), null, null), ctx(null));

        assertThat(articulo.getCosto()).isEqualByComparingTo("855.00");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1350");
        assertThat(articulo.getAjustes()).isEqualTo(new Ajustes(bd("-5"), null, null));
        assertThat(cambio).contains(new CambioPrecio(bd("880.65"), bd("855.00"), bd("1390.00"), bd("1350.00")));
    }

    @Test
    @DisplayName("editarAjustes: sin ajustes (900,00 / 1.420) agregar -5 da 855,00 / 1.350")
    void editarAjustesAgregar() {
        Articulo articulo = importado("1000", BONIF_10, Ajustes.NINGUNO);
        assertThat(articulo.getCosto()).isEqualByComparingTo("900.00");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1420");

        articulo.editarAjustes(new Ajustes(bd("-5"), null, null), ctx(null));

        assertThat(articulo.getCosto()).isEqualByComparingTo("855.00");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1350");
    }

    @Test
    @DisplayName("editarAjustes: con -5 y +3, cambiar +3 por +5 da 897,75 y el precio sigue en 1.420")
    void editarAjustesCambiarValor() {
        Articulo articulo = importado("1000", BONIF_10, new Ajustes(bd("-5"), bd("3"), null));

        Optional<CambioPrecio> cambio = articulo.editarAjustes(new Ajustes(bd("-5"), bd("5"), null), ctx(null));

        assertThat(articulo.getCosto()).isEqualByComparingTo("897.75");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1420");
        assertThat(cambio).isPresent();
    }

    @Test
    @DisplayName("editarAjustes: -5 y +3 guardados como +3 y -5 dejan el mismo costo, sin cambio, pero guardan las posiciones")
    void editarAjustesSoloPosicion() {
        Articulo articulo = importado("1000", BONIF_10, new Ajustes(bd("-5"), bd("3"), null));

        Optional<CambioPrecio> cambio = articulo.editarAjustes(new Ajustes(bd("3"), bd("-5"), null), ctx(null));

        assertThat(cambio).isEmpty();
        assertThat(articulo.getCosto()).isEqualByComparingTo("880.65");
        assertThat(articulo.getAjuste1()).isEqualByComparingTo("3");
        assertThat(articulo.getAjuste2()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("editarAjustes: USD con lista 10,00 y -10 con el dolar en 1.000 da 9,00 / 14.160")
    void editarAjustesEnDolares() {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(datos("1", null, AlicuotaIva.IVA_21), ctx(null));
        ContextoPrecio conDolar = new ContextoPrecio(null, bd("30"), bd("10"), bd("1000"));
        articulo.aplicarLista(bd("10.00"), bd("10.00"), Moneda.USD, null, Ajustes.NINGUNO, 7L, conDolar);
        articulo.registrarBaseDeLista(Bonificaciones.NINGUNA, false);

        articulo.editarAjustes(new Ajustes(bd("-10"), null, null), conDolar);

        assertThat(articulo.getCosto()).isEqualByComparingTo("9.00");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("14160");
        assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
    }

    @Test
    @DisplayName("editarAjustes: lista 0,01 con -99,99 da costo 0,00 y se guarda; -99,99 sobre 1.000,00 da 0,10 / 10")
    void editarAjustesEnElLimite() {
        Articulo centavo = importado("0.01", Bonificaciones.NINGUNA, Ajustes.NINGUNO);
        centavo.editarAjustes(new Ajustes(bd("-99.99"), null, null), ctx(null));
        assertThat(centavo.getCosto()).isEqualByComparingTo("0.00");
        assertThat(centavo.getAjuste1()).isEqualByComparingTo("-99.99");

        Articulo mil = importado("1000", Bonificaciones.NINGUNA, Ajustes.NINGUNO);
        mil.editarAjustes(new Ajustes(bd("-99.99"), null, null), ctx(null));
        assertThat(mil.getCosto()).isEqualByComparingTo("0.10");
        assertThat(mil.getPrecioFinal()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("editarAjustes: sin lista lanza IllegalStateException y no cambia nada")
    void editarAjustesSinLista() {
        Articulo articulo = articuloCon("100", null);

        assertThatThrownBy(() -> articulo.editarAjustes(new Ajustes(bd("-5"), null, null), ctx(null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
        assertThat(articulo.getAjustes()).isEqualTo(Ajustes.NINGUNO);
    }

    @Test
    @DisplayName("editarAjustes: no toca precio de lista, margen, IVA, moneda, importacion, stock, activo ni bonificaciones")
    void editarAjustesNoTocaLoDemas() {
        Articulo articulo = importado("1000", new Bonificaciones(bd("10"), bd("5"), null), true, Ajustes.NINGUNO,
                AlicuotaIva.IVA_10_5);
        articulo.fijarMargen(bd("40"), ctx(null));

        articulo.editarAjustes(new Ajustes(bd("-5"), null, null), ctx(null));

        assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1000");
        assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("40");
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(articulo.getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(articulo.getUltimaImportacionId()).isEqualTo(7L);
        assertThat(articulo.getStockMinimo()).isEqualByComparingTo("5");
        assertThat(articulo.isActivo()).isTrue();
        assertThat(articulo.bonificacionesGuardadas()).isEqualTo(new Bonificaciones(bd("10"), bd("5"), null));
        assertThat(articulo.getListaIncluyeIva()).isTrue();
    }

    @Test
    @DisplayName("editarAjustes: despues, un ajustarCosto (masivo) no cambia los ajustes")
    void ajustarCostoNoTocaLosAjustes() {
        Articulo articulo = importado("1000", BONIF_10, Ajustes.NINGUNO);
        articulo.editarAjustes(new Ajustes(bd("-5"), bd("3"), null), ctx(null));

        articulo.ajustarCosto(bd("990"), ctx(null));

        assertThat(articulo.getAjustes()).isEqualTo(new Ajustes(bd("-5"), bd("3"), null));
        assertThat(articulo.getCosto()).isEqualByComparingTo("990");
    }
}
