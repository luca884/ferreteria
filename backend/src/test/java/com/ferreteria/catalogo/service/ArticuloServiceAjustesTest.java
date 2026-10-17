package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.AjustesRequest;
import com.ferreteria.catalogo.dto.ArticuloResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloSinListaException;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ArticuloService.editarAjustes (T-219), en el orden del design: 404, 400, 409 sin lista, sin cambio, 409 sin cotizacion, 409 por pisar y escritura. */
@ExtendWith(MockitoExtension.class)
class ArticuloServiceAjustesTest {

    private static final Long FERRETERIA = 3L;
    private static final Long USUARIO = 9L;
    private static final Long ID = 11L;
    private static final String RANGO = "Cada descuento o recargo tiene que estar entre -99,99 y 1.000.";
    private static final String DECIMALES = "Los descuentos y recargos admiten hasta 2 decimales.";

    @Mock
    ArticuloRepository articuloRepository;

    @Mock
    RubroRepository rubroRepository;

    @Mock
    ProveedorRepository proveedorRepository;

    @Mock
    HistorialPrecioRepository historialPrecioRepository;

    @Mock
    ConfiguracionPreciosService configuracionPreciosService;

    @Mock
    DolarDePrecios dolarDePrecios;

    private static ValidatorFactory fabricaDeValidadores;

    ArticuloService service;

    @BeforeAll
    static void abrirValidador() {
        fabricaDeValidadores = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void cerrarValidador() {
        fabricaDeValidadores.close();
    }

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        service = new ArticuloService(articuloRepository, rubroRepository, proveedorRepository,
                historialPrecioRepository, configuracionPreciosService, dolarDePrecios,
                fabricaDeValidadores.getValidator());
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

    private static AjustesRequest pedido(String a1, String a2, String a3, boolean confirma) {
        return new AjustesRequest(bd(a1), bd(a2), bd(a3), confirma);
    }

    private static AjustesRequest pedido(String a1, String a2, String a3) {
        return pedido(a1, a2, a3, false);
    }

    /** Articulo guardado con el costo dado, margen 30, IVA 21 y multiplo 10; en USD se calcula con el dolar 1.000. */
    private static Articulo articulo(String costo, Moneda moneda) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo("TORN-001", null, null, "Tornillo", null, 7L, bd(costo), bd("30.00"),
                AlicuotaIva.IVA_21, Unidad.UNIDAD, null, null, true, moneda),
                new ContextoPrecio(null, bd("30.00"), bd("10.00"), moneda == Moneda.USD ? bd("1000") : null));
        var id = Articulo.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(articulo, ID);
        return articulo;
    }

    /** Lista 1.000, bonificacion 10, ajustes -5 y +3: costo 880,65 y precio 1.390. */
    private static Articulo importado() throws Exception {
        Articulo articulo = articulo("880.65", Moneda.ARS);
        articulo.registrarPrecioLista(bd("1000.00"));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);
        articulo.fijarAjustes(new Ajustes(bd("-5"), bd("3"), null));
        return articulo;
    }

    /** Lista 1.000, bonificacion 10, sin ajustes y el costo dado (900 coincide con la lista). */
    private static Articulo sinAjustes(String costo) throws Exception {
        Articulo articulo = articulo(costo, Moneda.ARS);
        articulo.registrarPrecioLista(bd("1000.00"));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);
        return articulo;
    }

    /** Lista US$ 10, sin bonificaciones, sin ajustes y el costo dado. */
    private static Articulo enDolares(String costo) throws Exception {
        Articulo articulo = articulo(costo, Moneda.USD);
        articulo.registrarPrecioLista(bd("10.00"));
        articulo.registrarBaseDeLista(Bonificaciones.NINGUNA, false);
        return articulo;
    }

    /** El articulo se lee con lock para escribir; el 400 y el 409 sin lista salen antes de pedir el stock. */
    private void encontrarSinStock(Articulo articulo) {
        when(articuloRepository.findParaEditarScoped(ID)).thenReturn(Optional.of(articulo));
    }

    /** Como {@link #encontrarSinStock} y ademas el stock confirmado (stock 4) que lee antes de decidir. */
    private void encontrar(Articulo articulo) {
        encontrarSinStock(articulo);
        when(articuloRepository.stockVigenteScoped(ID)).thenReturn(Optional.of(bd("4")));
    }

    private HistorialPrecio historialGuardado() {
        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        return historial.getValue();
    }

    // ---- lock ----

    @Test
    @DisplayName("el articulo se lee con lock para escribir ANTES de leer costo, base o ajustes; nunca con findScoped")
    void elLockVaAntesDeCualquierLectura() throws Exception {
        configuracion();
        Articulo articulo = importado();
        encontrar(articulo);

        service.editarAjustes(ID, pedido("-5", null, null), USUARIO);

        InOrder orden = inOrder(articuloRepository, configuracionPreciosService, historialPrecioRepository);
        orden.verify(articuloRepository).findParaEditarScoped(ID);
        orden.verify(articuloRepository).stockVigenteScoped(ID);
        orden.verify(configuracionPreciosService).actual();
        orden.verify(historialPrecioRepository).save(any());
        verify(articuloRepository, never()).findScoped(any());
    }

    // ---- 404 ----

    @Test
    @DisplayName("un articulo ajeno o inexistente da 404 antes que el 400, y no se lee nada mas")
    void ajenoDa404AntesQueElCuatrocientos() {
        when(articuloRepository.findParaEditarScoped(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-100", null, null), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");
        verifyNoInteractions(historialPrecioRepository, configuracionPreciosService, dolarDePrecios);
    }

    // ---- 400 ----

    @Test
    @DisplayName("fuera de rango (-100 y 1000,01) da 400 con el mensaje de rango, por campo, y nada se escribe")
    void fueraDeRango() throws Exception {
        encontrarSinStock(importado());

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-100", "1000.01", "5"), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                    assertThat(e.errors()).containsEntry("ajuste1", RANGO).containsEntry("ajuste2", RANGO);
                    assertThat(e.errors()).doesNotContainKey("ajuste3");
                });
        verifyNoInteractions(historialPrecioRepository, configuracionPreciosService, dolarDePrecios);
    }

    @Test
    @DisplayName("3,555 da 400 por decimales (antes de que Ajustes lo redondee) y el rango gana si falla las dos cosas")
    void masDeDosDecimales() throws Exception {
        encontrarSinStock(importado());

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("3.555", "10000.555", null), USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                    assertThat(e.errors()).containsEntry("ajuste1", DECIMALES).containsEntry("ajuste2", RANGO);
                });
    }

    @Test
    @DisplayName("un pedido invalido sobre un articulo sin lista da 400, no el 409")
    void invalidoAntesQueSinLista() throws Exception {
        encontrarSinStock(articulo("500.00", Moneda.ARS));

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-100", null, null), USUARIO))
                .isInstanceOf(DatosInvalidosException.class);
    }

    @Test
    @DisplayName("-99,99, 0, 1000, -5,50 y 3 son validos")
    void valoresValidos() throws Exception {
        configuracion();
        encontrar(importado());
        assertThatCode(() -> service.editarAjustes(ID, pedido("-99.99", "0", "1000", true), USUARIO))
                .doesNotThrowAnyException();

        configuracion();
        assertThatCode(() -> service.editarAjustes(ID, pedido("-5.50", "3", null, true), USUARIO))
                .doesNotThrowAnyException();
    }

    // ---- 409 sin lista ----

    @Test
    @DisplayName("sin precio de lista, sin proveedor o sin base: 409 con el texto unico y nada se escribe")
    void sinLista() throws Exception {
        Articulo manual = articulo("500.00", Moneda.ARS);
        Articulo sinProveedor = importado();
        sinProveedor.actualizar(new DatosArticulo("TORN-001", null, null, "Tornillo", null, null, bd("880.65"),
                bd("30.00"), AlicuotaIva.IVA_21, Unidad.UNIDAD, null, null, true, Moneda.ARS),
                new ContextoPrecio(null, bd("30.00"), bd("10.00")));
        Articulo sinBase = articulo("900.00", Moneda.ARS);
        sinBase.registrarPrecioLista(bd("1000.00"));

        for (Articulo articulo : new Articulo[] {manual, sinProveedor, sinBase}) {
            when(articuloRepository.findParaEditarScoped(ID)).thenReturn(Optional.of(articulo));

            assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-5", null, null, true), USUARIO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(ArticuloSinListaException.MENSAJE);
        }
        verifyNoInteractions(historialPrecioRepository, configuracionPreciosService, dolarDePrecios);
    }

    @Test
    @DisplayName("el 409 sin lista lleva solo mensaje, sin errors")
    void sinListaSinErrors() throws Exception {
        encontrarSinStock(articulo("500.00", Moneda.ARS));

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-5", null, null), USUARIO))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errors()).isEmpty());
    }

    // ---- sin cambio ----

    @Test
    @DisplayName("ajustes iguales a los actuales (-5 contra -5,00) dan 200 sin escribir historial, aunque venga la confirmacion")
    void sinCambio() throws Exception {
        Articulo articulo = importado();
        encontrar(articulo);

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("-5.00", "3", null, true), USUARIO);

        assertThat(respuesta.costo()).isEqualByComparingTo("880.65");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1390");
        assertThat(respuesta.stockActual()).isEqualByComparingTo("4");
        verifyNoInteractions(historialPrecioRepository, configuracionPreciosService, dolarDePrecios);
    }

    // ---- USD ----

    @Test
    @DisplayName("USD con la cotizacion apagada: se permite con el dolar congelado (-10: US$ 9,00 y $ 14.160)")
    void dolaresConCotizacionApagada() throws Exception {
        configuracion();
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
        encontrar(enDolares("10.00"));

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("-10", null, null), USUARIO);

        assertThat(respuesta.costo()).isEqualByComparingTo("9.00");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("14160");
        HistorialPrecio historial = historialGuardado();
        assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getMoneda()).isEqualTo(Moneda.USD);
        assertThat(historial.getUsuarioId()).isEqualTo(USUARIO);
        verify(dolarDePrecios, never()).activa();
    }

    @Test
    @DisplayName("USD sin ninguna cotizacion: 409 SIN_COTIZACION y nada cambia")
    void dolaresSinCotizacion() throws Exception {
        configuracion();
        when(dolarDePrecios.vigente()).thenReturn(Optional.empty());
        Articulo articulo = enDolares("10.00");
        encontrar(articulo);

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-10", null, null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage(DolarDePrecios.SIN_COTIZACION);
        assertThat(articulo.getAjuste1()).isNull();
        assertThat(articulo.getCosto()).isEqualByComparingTo("10.00");
        verifyNoInteractions(historialPrecioRepository);
    }

    // ---- R-214: pisar el costo ----

    @Test
    @DisplayName("costo a mano 950 contra lista 900 y el costo cambiaria: 409 solo con mensaje y nada se escribe")
    void avisaAntesDePisar() throws Exception {
        configuracion();
        Articulo articulo = sinAjustes("950.00");
        encontrar(articulo);

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-5", null, null), USUARIO))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("El costo guardado ($ 950,00) no coincide con el que sale "
                            + "de la lista ($ 900,00). Si guardás, el costo pasa a $ 855,00.");
                    assertThat(e.errors()).isEmpty();
                });
        assertThat(articulo.getAjuste1()).isNull();
        assertThat(articulo.getCosto()).isEqualByComparingTo("950.00");
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("con la confirmacion escribe: costo 950,00 -> 855,00, precio 1.500 -> 1.350 y historial MANUAL del Dueno")
    void confirmaPisar() throws Exception {
        configuracion();
        Articulo articulo = sinAjustes("950.00");
        encontrar(articulo);

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("-5", null, null, true), USUARIO);

        assertThat(respuesta.costo()).isEqualByComparingTo("855.00");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1350");
        assertThat(respuesta.ajuste1()).isEqualByComparingTo("-5");
        HistorialPrecio historial = historialGuardado();
        assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getUsuarioId()).isEqualTo(USUARIO);
        assertThat(historial.getCostoAnterior()).isEqualByComparingTo("950.00");
        assertThat(historial.getCostoNuevo()).isEqualByComparingTo("855.00");
        assertThat(historial.getPrecioAnterior()).isEqualByComparingTo("1500");
        assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("1350");
    }

    @Test
    @DisplayName("si el costo guardado coincide con la lista no hay aviso: 900,00 -> 855,00")
    void coincideNoAvisa() throws Exception {
        configuracion();
        encontrar(sinAjustes("900.00"));

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("-5", null, null), USUARIO);

        assertThat(respuesta.costo()).isEqualByComparingTo("855.00");
        assertThat(historialGuardado().getCostoAnterior()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("tras un masivo (costo 990 con lista 900) quitar un ajuste avisa")
    void despuesDeUnMasivoAvisa() throws Exception {
        configuracion();
        Articulo articulo = sinAjustes("990.00");
        articulo.fijarAjustes(new Ajustes(bd("-5"), null, null));
        encontrar(articulo);

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido(null, null, null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("El costo guardado ($ 990,00) no coincide con el que sale de la lista ($ 855,00). "
                        + "Si guardás, el costo pasa a $ 900,00.");
    }

    @Test
    @DisplayName("en USD los importes del aviso llevan US$")
    void avisoEnDolares() throws Exception {
        configuracion();
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
        encontrar(enDolares("9.50"));

        assertThatThrownBy(() -> service.editarAjustes(ID, pedido("-10", null, null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("El costo guardado (US$ 9,50) no coincide con el que sale de la lista (US$ 10,00). "
                        + "Si guardás, el costo pasa a US$ 9,00.");
    }

    @Test
    @DisplayName("si el costo a mano ya es el que quedaria no hay nada que pisar: sin aviso")
    void sinNadaQuePisar() throws Exception {
        configuracion();
        Articulo articulo = sinAjustes("855.00");
        encontrar(articulo);

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("-5", null, null), USUARIO);

        assertThat(respuesta.costo()).isEqualByComparingTo("855.00");
        assertThat(respuesta.ajuste1()).isEqualByComparingTo("-5");
        verify(historialPrecioRepository, never()).save(any());
    }

    // ---- escritura ----

    @Test
    @DisplayName("quitar el +3: costo 880,65 -> 855,00, precio 1.390 -> 1.350, historial MANUAL y el stock vigente en la respuesta")
    void quitarUnAjuste() throws Exception {
        configuracion();
        encontrar(importado());

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("-5", null, null), USUARIO);

        assertThat(respuesta.costo()).isEqualByComparingTo("855.00");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1350");
        assertThat(respuesta.ajuste2()).isNull();
        assertThat(respuesta.stockActual()).isEqualByComparingTo("4");
        HistorialPrecio historial = historialGuardado();
        assertThat(historial.getCostoAnterior()).isEqualByComparingTo("880.65");
        assertThat(historial.getCostoNuevo()).isEqualByComparingTo("855.00");
        assertThat(historial.getPrecioAnterior()).isEqualByComparingTo("1390");
        assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("1350");
    }

    @Test
    @DisplayName("cambiar solo la posicion (+3 y -5): se guarda y no hay historial")
    void soloLaPosicion() throws Exception {
        configuracion();
        Articulo articulo = importado();
        encontrar(articulo);

        ArticuloResponse respuesta = service.editarAjustes(ID, pedido("3", "-5", null), USUARIO);

        assertThat(respuesta.ajuste1()).isEqualByComparingTo("3");
        assertThat(respuesta.ajuste2()).isEqualByComparingTo("-5");
        assertThat(respuesta.costo()).isEqualByComparingTo("880.65");
        assertThat(articulo.getAjuste1()).isEqualByComparingTo("3");
        verify(historialPrecioRepository, never()).save(any());
    }
}
