package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.DesglosePrecioResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloSinListaException;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenMargen;
import com.ferreteria.catalogo.model.PasoDePrecio;
import com.ferreteria.catalogo.model.RecargoCosto;
import com.ferreteria.catalogo.model.RecargosDeCosto;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.TipoPaso;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DesglosePrecioServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long ID = 11L;

    @Mock
    ArticuloRepository articuloRepository;

    @Mock
    RubroRepository rubroRepository;

    @Mock
    ConfiguracionPreciosService configuracionPreciosService;

    @Mock
    DolarDePrecios dolarDePrecios;

    DesglosePrecioService service;

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        service = new DesglosePrecioService(
                articuloRepository, rubroRepository, configuracionPreciosService, dolarDePrecios);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- helpers ----

    private static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    private void configuracion() {
        when(configuracionPreciosService.actual()).thenReturn(new ConfiguracionPrecios(bd("30.00"), bd("10.00")));
    }

    /** Articulo guardado con el costo y el margen dados (null = sin margen propio), IVA 21 y multiplo 10. */
    private static Articulo articulo(String costo, String margen, Long rubroId, Long proveedorId, Moneda moneda,
                                     String margenRubro, String dolar) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo("TORN-001", null, null, "Tornillo", rubroId, proveedorId, bd(costo),
                bd(margen), AlicuotaIva.IVA_21, Unidad.UNIDAD, null, null, true, moneda),
                new ContextoPrecio(bd(margenRubro), bd("30.00"), bd("10.00"), bd(dolar)));
        var id = Articulo.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(articulo, ID);
        return articulo;
    }

    /** Lista 1.000, bonificacion 10, ajustes -5 y +3: costo 880,65 y precio 1.390 (margen 30 propio). */
    private static Articulo importado() throws Exception {
        Articulo articulo = articulo("880.65", "30.00", null, 7L, Moneda.ARS, null, null);
        articulo.registrarPrecioLista(bd("1000.00"));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);
        articulo.fijarAjustes(new Ajustes(bd("-5"), bd("3"), null));
        return articulo;
    }

    private void encontrar(Articulo articulo) {
        when(articuloRepository.findScoped(ID)).thenReturn(Optional.of(articulo));
    }

    private static List<TipoPaso> tipos(DesglosePrecioResponse respuesta) {
        return respuesta.pasos().stream().map(PasoDePrecio::tipo).toList();
    }

    private static PasoDePrecio paso(DesglosePrecioResponse respuesta, TipoPaso tipo) {
        return respuesta.pasos().stream().filter(p -> p.tipo() == tipo).findFirst().orElseThrow();
    }

    // ---- con lista ----

    @Test
    @DisplayName("con lista: arma lista, bonificacion, ajustes, costo, margen, IVA y precio guardado con los importes del ejemplo")
    void conListaArmaLosPasos() throws Exception {
        configuracion();
        encontrar(importado());

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.tieneLista()).isTrue();
        assertThat(respuesta.motivoSinLista()).isNull();
        assertThat(tipos(respuesta)).containsExactly(TipoPaso.LISTA, TipoPaso.BONIFICACION, TipoPaso.AJUSTE,
                TipoPaso.AJUSTE, TipoPaso.COSTO, TipoPaso.MARGEN, TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
        assertThat(respuesta.pasos().get(1).importe()).isEqualByComparingTo("900.0000");
        assertThat(respuesta.pasos().get(2).importe()).isEqualByComparingTo("855.0000");
        assertThat(respuesta.pasos().get(3).importe()).isEqualByComparingTo("880.6500");
        assertThat(paso(respuesta, TipoPaso.MARGEN).importe()).isEqualByComparingTo("1144.8450");
        assertThat(paso(respuesta, TipoPaso.IVA).importe()).isEqualByComparingTo("1385.2625");
        assertThat(paso(respuesta, TipoPaso.PRECIO_FINAL).importe()).isEqualByComparingTo("1390");
        assertThat(respuesta.precioLista()).isEqualByComparingTo("1000.00");
        assertThat(respuesta.bonificacion1()).isEqualByComparingTo("10");
        assertThat(respuesta.ajuste1()).isEqualByComparingTo("-5");
        assertThat(respuesta.ajuste2()).isEqualByComparingTo("3");
        assertThat(respuesta.costoGuardado()).isEqualByComparingTo("880.65");
        assertThat(respuesta.costoDeLista()).isEqualByComparingTo("880.65");
        assertThat(respuesta.coincide()).isTrue();
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1390");
        assertThat(respuesta.margen()).isEqualByComparingTo("30");
        assertThat(respuesta.origenMargen()).isEqualTo(OrigenMargen.PROPIO);
    }

    @Test
    @DisplayName("usa la base guardada en el articulo: lista con IVA incluido y sin bonificaciones aunque el proveedor haya cambiado")
    void usaLaBaseGuardada() throws Exception {
        configuracion();
        Articulo articulo = articulo("1100.00", "30.00", null, 7L, Moneda.ARS, null, null);
        articulo.registrarPrecioLista(bd("1210.00"));
        articulo.registrarBaseDeLista(Bonificaciones.NINGUNA, true);
        articulo.fijarAjustes(new Ajustes(bd("10"), null, null));
        encontrar(articulo);

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(tipos(respuesta)).startsWith(TipoPaso.LISTA, TipoPaso.SIN_IVA, TipoPaso.AJUSTE, TipoPaso.COSTO);
        assertThat(paso(respuesta, TipoPaso.SIN_IVA).importe()).isEqualByComparingTo("1000.0000");
        assertThat(respuesta.costoDeLista()).isEqualByComparingTo("1100.00");
        assertThat(respuesta.coincide()).isTrue();
        assertThat(respuesta.listaIncluyeIva()).isTrue();
        // El servicio solo conoce al articulo y al rubro: no hay forma de leer un proveedor.
        verifyNoMoreInteractions(articuloRepository, rubroRepository);
    }

    @Test
    @DisplayName("el costo guardado distinto del de la lista no coincide y muestra las dos cifras")
    void noCoincide() throws Exception {
        configuracion();
        Articulo articulo = articulo("990.00", "30.00", null, 7L, Moneda.ARS, null, null);
        articulo.registrarPrecioLista(bd("1000.00"));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);
        encontrar(articulo);

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.coincide()).isFalse();
        assertThat(respuesta.costoDeLista()).isEqualByComparingTo("900.00");
        assertThat(respuesta.costoGuardado()).isEqualByComparingTo("990.00");
    }

    @Test
    @DisplayName("el margen del rubro se lee con findScoped y el origen es RUBRO")
    void margenDelRubro() throws Exception {
        configuracion();
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(new Rubro(FERRETERIA, "Buloneria", bd("35.00"))));
        encontrar(articulo("100.00", null, 4L, null, Moneda.ARS, "35.00", null));

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.margen()).isEqualByComparingTo("35");
        assertThat(respuesta.origenMargen()).isEqualTo(OrigenMargen.RUBRO);
    }

    @Test
    @DisplayName("un rubro que no se encuentra (o es de otra ferreteria) da 404 Rubro no encontrado y no se usa margen null")
    void rubroInexistenteDa404() throws Exception {
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.empty());
        encontrar(articulo("100.00", null, 4L, null, Moneda.ARS, "35.00", null));

        assertThatThrownBy(() -> service.obtener(ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Rubro no encontrado");
    }

    @Test
    @DisplayName("un margen propio de 0 se respeta y es PROPIO")
    void margenPropioCero() throws Exception {
        configuracion();
        encontrar(articulo("100.00", "0.00", null, null, Moneda.ARS, null, null));

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.margen()).isEqualByComparingTo("0");
        assertThat(respuesta.origenMargen()).isEqualTo(OrigenMargen.PROPIO);
    }

    @Test
    @DisplayName("sin margen propio ni de rubro el origen es FERRETERIA")
    void margenDeLaFerreteria() throws Exception {
        configuracion();
        encontrar(articulo("100.00", null, null, null, Moneda.ARS, null, null));

        assertThat(service.obtener(ID).origenMargen()).isEqualTo(OrigenMargen.FERRETERIA);
    }

    @Test
    @DisplayName("un articulo en pesos no tiene paso de cotizacion y no consulta el dolar")
    void enPesosNoConsultaElDolar() throws Exception {
        configuracion();
        encontrar(importado());

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(tipos(respuesta)).doesNotContain(TipoPaso.COTIZACION);
        assertThat(respuesta.cotizacion()).isNull();
        assertThat(respuesta.cotizacionCongelada()).isFalse();
        verifyNoInteractions(dolarDePrecios);
    }

    // ---- sin lista ----

    @Test
    @DisplayName("alta manual: sin lista, con el texto unico y la cadena arranca en el costo guardado")
    void altaManualSinLista() throws Exception {
        configuracion();
        encontrar(articulo("500.00", "30.00", null, null, Moneda.ARS, null, null));

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.tieneLista()).isFalse();
        assertThat(respuesta.motivoSinLista()).isEqualTo(ArticuloSinListaException.MENSAJE);
        assertThat(tipos(respuesta).get(0)).isEqualTo(TipoPaso.COSTO);
        assertThat(respuesta.costoDeLista()).isNull();
        assertThat(respuesta.coincide()).isNull();
        assertThat(respuesta.precioLista()).isNull();
        assertThat(respuesta.bonificacion1()).isNull();
        assertThat(respuesta.ajuste1()).isNull();
        assertThat(respuesta.costoGuardado()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("con precio de lista y base pero sin proveedor es sin lista (decision 46)")
    void sinProveedorEsSinLista() throws Exception {
        configuracion();
        Articulo articulo = articulo("900.00", "30.00", null, null, Moneda.ARS, null, null);
        articulo.registrarPrecioLista(bd("1000.00"));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);
        encontrar(articulo);

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.tieneLista()).isFalse();
        assertThat(respuesta.motivoSinLista()).isEqualTo(ArticuloSinListaException.MENSAJE);
        assertThat(tipos(respuesta)).doesNotContain(TipoPaso.LISTA, TipoPaso.BONIFICACION);
    }

    @Test
    @DisplayName("con proveedor y precio de lista pero sin base guardada es sin lista")
    void sinBaseEsSinLista() throws Exception {
        configuracion();
        Articulo articulo = articulo("900.00", "30.00", null, 7L, Moneda.ARS, null, null);
        articulo.registrarPrecioLista(bd("1000.00"));
        encontrar(articulo);

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.tieneLista()).isFalse();
        assertThat(respuesta.motivoSinLista()).isEqualTo(ArticuloSinListaException.MENSAJE);
    }

    // ---- USD ----

    /** Lista 10, bonificacion 10, ajustes -5 y +3: costo US$ 8,81; con dolar 1.000 el precio es 13.860. */
    private static Articulo importadoEnDolares() throws Exception {
        Articulo articulo = articulo("8.81", "30.00", null, 7L, Moneda.USD, null, "1000");
        articulo.registrarPrecioLista(bd("10.00"));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);
        articulo.fijarAjustes(new Ajustes(bd("-5"), bd("3"), null));
        return articulo;
    }

    @Test
    @DisplayName("USD: usa el dolar vigente, agrega el paso de cotizacion y el precio es el guardado")
    void enDolares() throws Exception {
        configuracion();
        when(dolarDePrecios.activa()).thenReturn(true);
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000.0000")));
        encontrar(importadoEnDolares());

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.USD);
        assertThat(respuesta.cotizacion()).isEqualByComparingTo("1000");
        assertThat(respuesta.cotizacionCongelada()).isFalse();
        assertThat(paso(respuesta, TipoPaso.COTIZACION).importe()).isEqualByComparingTo("8810.0000");
        assertThat(paso(respuesta, TipoPaso.MARGEN).importe()).isEqualByComparingTo("11453.0000");
        assertThat(paso(respuesta, TipoPaso.PRECIO_FINAL).importe()).isEqualByComparingTo("13860");
        assertThat(respuesta.costoDeLista()).isEqualByComparingTo("8.81");
    }

    @Test
    @DisplayName("USD con la cotizacion apagada: usa el dolar congelado y lo marca")
    void cotizacionApagada() throws Exception {
        configuracion();
        when(dolarDePrecios.activa()).thenReturn(false);
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000.0000")));
        encontrar(importadoEnDolares());

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.cotizacion()).isEqualByComparingTo("1000");
        assertThat(respuesta.cotizacionCongelada()).isTrue();
    }

    @Test
    @DisplayName("USD sin ninguna cotizacion guardada: 200 con cotizacion null y sin pasos despues del costo")
    void sinNingunaCotizacion() throws Exception {
        configuracion();
        when(dolarDePrecios.activa()).thenReturn(true);
        when(dolarDePrecios.vigente()).thenReturn(Optional.empty());
        encontrar(importadoEnDolares());

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.cotizacion()).isNull();
        assertThat(tipos(respuesta).get(tipos(respuesta).size() - 1)).isEqualTo(TipoPaso.COSTO);
    }

    // ---- recargos del costo (CU-13) ----

    @Test
    @DisplayName("con recargos del costo: la respuesta los trae y los pasos suman un RECARGO y el costo real antes del margen")
    void conRecargosDelCosto() throws Exception {
        configuracion();
        Articulo articulo = articulo("235971.56", "30.00", null, 7L, Moneda.ARS, null, null);
        articulo.fijarRecargosDeCosto(new RecargosDeCosto(List.of(new RecargoCosto("Embalaje y carga", bd("4.44")))),
                new ContextoPrecio(null, bd("30.00"), bd("10.00")));
        encontrar(articulo);

        DesglosePrecioResponse respuesta = service.obtener(ID);

        assertThat(respuesta.recargosCosto()).containsExactly(new RecargoCosto("Embalaje y carga", bd("4.44")));
        assertThat(tipos(respuesta)).containsExactly(TipoPaso.COSTO, TipoPaso.RECARGO, TipoPaso.COSTO_REAL,
                TipoPaso.MARGEN, TipoPaso.IVA, TipoPaso.REDONDEO, TipoPaso.PRECIO_FINAL);
        assertThat(paso(respuesta, TipoPaso.RECARGO).importe()).isEqualByComparingTo("10477.14");
        assertThat(paso(respuesta, TipoPaso.COSTO_REAL).importe()).isEqualByComparingTo("246448.70");
        assertThat(respuesta.costoGuardado()).isEqualByComparingTo("235971.56");
    }

    // ---- 404 ----

    @Test
    @DisplayName("un articulo ajeno o inexistente da 404 Articulo no encontrado y no se lee nada mas")
    void ajenoOInexistente() {
        when(articuloRepository.findScoped(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");
        verifyNoInteractions(rubroRepository, configuracionPreciosService, dolarDePrecios);
    }
}
