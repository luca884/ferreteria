package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

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
}
