package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class RecalculoPreciosServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long USUARIO = 9L;
    private static final Long RUBRO_ID = 4L;

    @Mock
    ArticuloRepository articuloRepository;

    @Mock
    HistorialPrecioRepository historialPrecioRepository;

    @Mock
    ConfiguracionPreciosService configuracionPreciosService;

    @Mock
    RubroRepository rubroRepository;

    @InjectMocks
    RecalculoPreciosService service;

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static void fijar(Object objeto, String campo, Object valor) throws Exception {
        Field field = objeto.getClass().getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    /** Rubro con el margen ya editado (la cascada corre despues de rubro.actualizar). */
    private static Rubro rubro(String margen) throws Exception {
        Rubro rubro = new Rubro(FERRETERIA, "Buloneria", bd(margen));
        fijar(rubro, "id", RUBRO_ID);
        return rubro;
    }

    /** Articulo del rubro 4, sin margen propio, IVA 21, que ya cuesta 170 con el margen viejo de 35. */
    private static Articulo heredero(long id, String costo) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo("COD-" + id, null, null, "Articulo " + id, RUBRO_ID, null,
                        bd(costo), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true),
                new ContextoPrecio(bd("35.00"), bd("30.00"), bd("10.00")));
        fijar(articulo, "id", id);
        return articulo;
    }

    private void configuracionPorDefecto() {
        when(configuracionPreciosService.actual()).thenReturn(new ConfiguracionPrecios(bd("30.00"), bd("10.00")));
    }

    @Test
    @DisplayName("exige una transaccion en curso (MANDATORY): no corre fuera de la edicion del rubro")
    void esTransaccionalMandatory() throws Exception {
        Transactional transaccional = RecalculoPreciosService.class
                .getMethod("porCambioDeMargenDeRubro", Rubro.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transaccional).isNotNull();
        assertThat(transaccional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("recalcula los herederos con el margen nuevo y guarda un MASIVO por cada precio que cambia")
    void recalculaYGuardaHistorialMasivo() throws Exception {
        configuracionPorDefecto();
        Articulo x = heredero(11L, "100.00");
        Articulo z = heredero(12L, "100.00");
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(x, z));

        service.porCambioDeMargenDeRubro(rubro("50.00"), USUARIO);

        assertThat(x.getPrecioFinal()).isEqualByComparingTo("190.00");
        assertThat(z.getPrecioFinal()).isEqualByComparingTo("190.00");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HistorialPrecio>> guardado = ArgumentCaptor.forClass(List.class);
        verify(historialPrecioRepository).saveAll(guardado.capture());
        assertThat(guardado.getValue()).hasSize(2).allSatisfy(h -> {
            assertThat(h.getOrigen()).isEqualTo(OrigenPrecio.MASIVO);
            assertThat(h.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(h.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(h.getCostoAnterior()).isEqualByComparingTo("100");
            assertThat(h.getCostoNuevo()).isEqualByComparingTo("100");
            assertThat(h.getPrecioAnterior()).isEqualByComparingTo("170");
            assertThat(h.getPrecioNuevo()).isEqualByComparingTo("190");
        });
        assertThat(guardado.getValue()).extracting(HistorialPrecio::getArticuloId).containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("si el precio redondeado no cambia (35 -> 36) no hay entrada para ese articulo")
    void sinCambioDePrecioNoGuardaHistorial() throws Exception {
        configuracionPorDefecto();
        Articulo x = heredero(11L, "100.00");
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(x));

        service.porCambioDeMargenDeRubro(rubro("36.00"), USUARIO);

        assertThat(x.getPrecioFinal()).isEqualByComparingTo("170.00");
        verify(historialPrecioRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("solo guarda historial de los articulos cuyo precio cambia")
    void historialSoloDeLosQueCambian() throws Exception {
        configuracionPorDefecto();
        // Costo 100: 35 % da 170 y 36 % tambien (no cambia). Costo 1000: 1633,50 -> 1640 y 1645,60 -> 1650 (cambia).
        Articulo igual = heredero(12L, "100.00");
        Articulo otro = heredero(13L, "1000.00");
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(igual, otro));

        service.porCambioDeMargenDeRubro(rubro("36.00"), USUARIO);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HistorialPrecio>> guardado = ArgumentCaptor.forClass(List.class);
        verify(historialPrecioRepository).saveAll(guardado.capture());
        assertThat(guardado.getValue()).extracting(HistorialPrecio::getArticuloId).containsExactly(13L);
        assertThat(guardado.getValue().get(0).getPrecioNuevo()).isEqualByComparingTo("1650");
        assertThat(igual.getPrecioFinal()).isEqualByComparingTo("170.00");
    }

    @Test
    @DisplayName("sin herederos no se guarda nada")
    void sinHerederos() throws Exception {
        configuracionPorDefecto();
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of());

        service.porCambioDeMargenDeRubro(rubro("50.00"), USUARIO);

        verify(historialPrecioRepository, never()).saveAll(anyList());
    }

    // ---- porCotizacion (T-026) ----

    /** Articulo en dolares sin rubro (margen 30 de la ferreteria), IVA 21, calculado con ese dolar. */
    private static Articulo enDolares(long id, String costo, String dolarConElQueSeCalculo, boolean activo)
            throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo("USD-" + id, null, null, "Articulo USD " + id, null, null,
                        bd(costo), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, activo, Moneda.USD),
                new ContextoPrecio(null, bd("30.00"), bd("10.00"), bd(dolarConElQueSeCalculo)));
        fijar(articulo, "id", id);
        return articulo;
    }

    @SuppressWarnings("unchecked")
    private List<HistorialPrecio> historialGuardado() {
        ArgumentCaptor<List<HistorialPrecio>> guardado = ArgumentCaptor.forClass(List.class);
        verify(historialPrecioRepository).saveAll(guardado.capture());
        return guardado.getValue();
    }

    @Test
    @DisplayName("porCotizacion exige una transaccion en curso (MANDATORY), igual que la cascada de rubro")
    void porCotizacionEsMandatory() throws Exception {
        Transactional transaccional = RecalculoPreciosService.class
                .getMethod("porCotizacion", BigDecimal.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transaccional).isNotNull();
        assertThat(transaccional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("10 USD pasa de 1000 a 1100: el precio sube de 15730 a 17310 y queda un COTIZACION en USD")
    void porCotizacionRecalculaYGuardaHistorial() throws Exception {
        configuracionPorDefecto();
        Articulo usd = enDolares(21L, "10.00", "1000", true);
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("15730");
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(usd));

        int cambiados = service.porCotizacion(bd("1100"), null);

        assertThat(cambiados).isEqualTo(1);
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("17310");
        assertThat(usd.getCosto()).isEqualByComparingTo("10");
        assertThat(historialGuardado()).singleElement().satisfies(h -> {
            assertThat(h.getOrigen()).isEqualTo(OrigenPrecio.COTIZACION);
            assertThat(h.getMoneda()).isEqualTo(Moneda.USD);
            assertThat(h.getArticuloId()).isEqualTo(21L);
            assertThat(h.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(h.getPrecioAnterior()).isEqualByComparingTo("15730");
            assertThat(h.getPrecioNuevo()).isEqualByComparingTo("17310");
            assertThat(h.getCostoAnterior()).isEqualByComparingTo("10");
            assertThat(h.getCostoNuevo()).isEqualByComparingTo("10");
        });
    }

    @Test
    @DisplayName("con otro dolar baja: 10 USD de 1000 a 950 deja 14950 (la cuenta usa el dolar recibido, no uno fijo)")
    void porCotizacionUsaElDolarRecibido() throws Exception {
        configuracionPorDefecto();
        Articulo usd = enDolares(21L, "10.00", "1000", true);
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(usd));

        service.porCotizacion(bd("950"), USUARIO);

        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("14950");
        assertThat(historialGuardado()).singleElement().satisfies(h -> {
            assertThat(h.getPrecioAnterior()).isEqualByComparingTo("15730");
            assertThat(h.getPrecioNuevo()).isEqualByComparingTo("14950");
        });
    }

    @Test
    @DisplayName("un articulo USD inactivo tambien se recalcula (R-032) y sigue inactivo")
    void porCotizacionIncluyeInactivos() throws Exception {
        configuracionPorDefecto();
        Articulo inactivo = enDolares(22L, "10.00", "1000", false);
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(inactivo));

        int cambiados = service.porCotizacion(bd("1100"), null);

        assertThat(cambiados).isEqualTo(1);
        assertThat(inactivo.getPrecioFinal()).isEqualByComparingTo("17310");
        assertThat(inactivo.isActivo()).isFalse();
        assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(22L);
    }

    @Test
    @DisplayName("si el precio no cambia no hay historial y no cuenta; con 3 USD donde cambian 2 hay 2 filas y devuelve 2")
    void porCotizacionSoloCuentaLosQueCambian() throws Exception {
        configuracionPorDefecto();
        Articulo sube1 = enDolares(31L, "10.00", "1000", true);
        Articulo yaAlDia = enDolares(32L, "10.00", "1100", true);
        Articulo sube2 = enDolares(33L, "10.00", "1000", true);
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(sube1, yaAlDia, sube2));

        int cambiados = service.porCotizacion(bd("1100"), null);

        assertThat(cambiados).isEqualTo(2);
        assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(31L, 33L);
        assertThat(yaAlDia.getPrecioFinal()).isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("dolar sin cambio: devuelve 0 y no guarda nada")
    void porCotizacionSinCambio() throws Exception {
        configuracionPorDefecto();
        Articulo usd = enDolares(21L, "10.00", "1000", true);
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(usd));

        int cambiados = service.porCotizacion(bd("1000"), null);

        assertThat(cambiados).isZero();
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("15730");
        verify(historialPrecioRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("el margen del rubro del articulo USD entra en la cuenta: rubro de 50 % con 10 USD da 18150 y 19970")
    void porCotizacionUsaElMargenDelRubro() throws Exception {
        configuracionPorDefecto();
        Rubro rubro = rubro("50.00");
        Articulo usd = new Articulo(FERRETERIA);
        usd.actualizar(new DatosArticulo("USD-51", null, null, "Con rubro", RUBRO_ID, null, bd("10.00"), null,
                        AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, Moneda.USD),
                new ContextoPrecio(bd("50.00"), bd("30.00"), bd("10.00"), bd("1000")));
        fijar(usd, "id", 51L);
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("18150");
        when(rubroRepository.listarScoped()).thenReturn(List.of(rubro));
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(usd));

        service.porCotizacion(bd("1100"), null);

        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("19970");
    }

    @Test
    @DisplayName("sin articulos en dolares devuelve 0 y no guarda nada")
    void porCotizacionSinArticulosUsd() {
        configuracionPorDefecto();
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of());

        assertThat(service.porCotizacion(bd("1100"), null)).isZero();
        verify(historialPrecioRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("con usuarioId null (job diario) el historial queda sin usuario; con un id queda a su nombre (R-031)")
    void porCotizacionUsuarioDelHistorial() throws Exception {
        configuracionPorDefecto();
        Articulo automatico = enDolares(41L, "10.00", "1000", true);
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(automatico));
        service.porCotizacion(bd("1100"), null);
        assertThat(historialGuardado()).singleElement().satisfies(h -> assertThat(h.getUsuarioId()).isNull());
    }

    @Test
    @DisplayName("con un usuario (Recalcular ahora) el historial queda a nombre del Dueno")
    void porCotizacionACargoDelDueno() throws Exception {
        configuracionPorDefecto();
        Articulo usd = enDolares(42L, "10.00", "1000", true);
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(usd));

        service.porCotizacion(bd("1100"), USUARIO);

        assertThat(historialGuardado()).singleElement().satisfies(h -> assertThat(h.getUsuarioId()).isEqualTo(USUARIO));
    }
}
