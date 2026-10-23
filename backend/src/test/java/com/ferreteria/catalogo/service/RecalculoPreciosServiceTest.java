package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.model.AjustePrecio;
import com.ferreteria.catalogo.model.AjustesDePrecio;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.TipoAjustePrecio;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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

    @Mock
    DolarDePrecios dolarDePrecios;

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

        int cambiados = service.porCotizacion(bd("1100"), null).articulosRecalculados();

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

        int cambiados = service.porCotizacion(bd("1100"), null).articulosRecalculados();

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

        int cambiados = service.porCotizacion(bd("1100"), null).articulosRecalculados();

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

        int cambiados = service.porCotizacion(bd("1000"), null).articulosRecalculados();

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

        assertThat(service.porCotizacion(bd("1100"), null).articulosRecalculados()).isZero();
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

    // ---- la cascada de rubro con articulos en dolares (T-036) ----

    /** Articulo del rubro 4, en USD, sin margen propio, que ya vale 16.340 con el margen viejo de 35 y el dolar en 1000. */
    private static Articulo usdHeredero(long id) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo("USD-" + id, null, null, "Articulo USD " + id, RUBRO_ID, null,
                        bd("10.00"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, Moneda.USD),
                new ContextoPrecio(bd("35.00"), bd("30.00"), bd("10.00"), bd("1000")));
        fijar(articulo, "id", id);
        return articulo;
    }

    @Test
    @DisplayName("la cascada recalcula un heredero USD con el dolar vigente: rubro de 35 a 50 % con 10 USD a 1000 pasa de 16.340 a 18.150")
    void cascadaConUnArticuloEnDolares() throws Exception {
        configuracionPorDefecto();
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
        Articulo usd = usdHeredero(61L);
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("16340");
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(usd));

        service.porCambioDeMargenDeRubro(rubro("50.00"), USUARIO);

        // 10 x 1000 = 10000; x 1,50 = 15000; x 1,21 = 18150
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("18150");
        assertThat(usd.getCosto()).isEqualByComparingTo("10");
        assertThat(historialGuardado()).singleElement().satisfies(h -> {
            assertThat(h.getOrigen()).isEqualTo(OrigenPrecio.MASIVO);
            assertThat(h.getMoneda()).isEqualTo(Moneda.USD);
            assertThat(h.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(h.getPrecioAnterior()).isEqualByComparingTo("16340");
            assertThat(h.getPrecioNuevo()).isEqualByComparingTo("18150");
        });
    }

    @Test
    @DisplayName("la cascada con herederos en pesos y en dolares calcula cada uno con lo suyo")
    void cascadaConMezclaDeMonedas() throws Exception {
        configuracionPorDefecto();
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
        Articulo ars = heredero(11L, "100.00");
        Articulo usd = usdHeredero(61L);
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(ars, usd));

        service.porCambioDeMargenDeRubro(rubro("50.00"), USUARIO);

        assertThat(ars.getPrecioFinal()).isEqualByComparingTo("190");
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("18150");
        assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(11L, 61L);
    }

    @Test
    @DisplayName("sin herederos en USD la cascada no consulta el dolar")
    void cascadaSinUsdNoConsultaElDolar() throws Exception {
        configuracionPorDefecto();
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(heredero(11L, "100.00")));

        service.porCambioDeMargenDeRubro(rubro("50.00"), USUARIO);

        verifyNoInteractions(dolarDePrecios);
    }

    @Test
    @DisplayName("con un heredero USD y ninguna cotizacion: 409 claro (no IllegalStateException) y no se guarda historial")
    void cascadaSinCotizacionDa409() throws Exception {
        configuracionPorDefecto();
        when(dolarDePrecios.vigente()).thenReturn(Optional.empty());
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(usdHeredero(61L)));

        assertThatThrownBy(() -> service.porCambioDeMargenDeRubro(rubro("50.00"), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
        verify(historialPrecioRepository, never()).saveAll(anyList());
    }

    // ---- ajustes sobre el precio final que dejarian el precio en 0 o menos (S7) ----

    private static final ContextoPrecio CTX_RUBRO_35 = new ContextoPrecio(bd("35.00"), bd("30.00"), bd("10.00"));

    private static AjustesDePrecio ajusteEnPesos(String monto) {
        return new AjustesDePrecio(List.of(new AjustePrecio(null, TipoAjustePrecio.MONTO, bd(monto))));
    }

    private static String aviso(String codigo, String descripcion) {
        return "No se cambió el precio de " + codigo + " (" + descripcion
                + "): con sus ajustes quedaría en 0 o menos. Revisá sus ajustes.";
    }

    @Test
    @DisplayName("la cascada saltea al heredero cuyo ajuste lo dejaria en 0 o menos: conserva el precio, avisa y el resto sigue")
    void cascadaSaltea() throws Exception {
        configuracionPorDefecto();
        Articulo normal = heredero(11L, "100.00");
        Articulo conAjuste = heredero(12L, "100.00");
        conAjuste.fijarAjustesDePrecio(ajusteEnPesos("-150"), CTX_RUBRO_35);
        assertThat(conAjuste.getPrecioFinal()).isEqualByComparingTo("20.00");
        when(articuloRepository.heredanMargenDeRubroScoped(RUBRO_ID)).thenReturn(List.of(normal, conAjuste));

        List<String> avisos = service.porCambioDeMargenDeRubro(rubro("0.00"), USUARIO);

        assertThat(normal.getPrecioFinal()).isEqualByComparingTo("130.00");
        assertThat(conAjuste.getPrecioFinal()).isEqualByComparingTo("20.00");
        assertThat(avisos).containsExactly(aviso("COD-12", "Articulo 12"));
        assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(11L);
    }

    @Test
    @DisplayName("el recalculo por cotizacion saltea al articulo cuyo ajuste lo dejaria en 0 o menos y cuenta solo los que cambian")
    void cotizacionSaltea() throws Exception {
        configuracionPorDefecto();
        when(rubroRepository.listarScoped()).thenReturn(List.of(rubro("35.00")));
        Articulo normal = usdHeredero(61L);
        Articulo conAjuste = usdHeredero(62L);
        conAjuste.fijarAjustesDePrecio(ajusteEnPesos("-16000"),
                new ContextoPrecio(bd("35.00"), bd("30.00"), bd("10.00"), bd("1000")));
        assertThat(conAjuste.getPrecioFinal()).isEqualByComparingTo("340");
        when(articuloRepository.enDolaresScoped()).thenReturn(List.of(normal, conAjuste));

        RecalculoPreciosService.ResultadoPorCotizacion resultado = service.porCotizacion(bd("100"), null);

        assertThat(normal.getPrecioFinal()).isEqualByComparingTo("1640");
        assertThat(conAjuste.getPrecioFinal()).isEqualByComparingTo("340");
        assertThat(resultado.articulosRecalculados()).isEqualTo(1);
        assertThat(resultado.avisos()).containsExactly(aviso("USD-62", "Articulo USD 62"));
        assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(61L);
    }

    // ---- porCambioDeConfiguracion: margen por defecto y multiplo de redondeo (cu-03-configuracion-precios) ----

    private static final ConfiguracionPrecios CONFIG_ACTUAL = new ConfiguracionPrecios(bd("30.00"), bd("10.00"));

    /** Articulo sin rubro ni margen propio (usa el 30 de la ferreteria), IVA 21: costo 100 vale 160 con multiplo 10. */
    private static Articulo sinRubro(long id, String costo, Moneda moneda) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo("SR-" + id, null, null, "Sin rubro " + id, null, null,
                        bd(costo), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, moneda),
                new ContextoPrecio(null, bd("30.00"), bd("10.00"), moneda == Moneda.USD ? bd("1000") : null));
        fijar(articulo, "id", id);
        return articulo;
    }

    @Test
    @DisplayName("porCambioDeConfiguracion exige una transaccion en curso (MANDATORY): corre dentro del guardado")
    void porConfiguracionEsMandatory() throws Exception {
        Transactional transaccional = RecalculoPreciosService.class
                .getMethod("porCambioDeConfiguracion", ConfiguracionPrecios.class, ConfiguracionPrecios.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transaccional).isNotNull();
        assertThat(transaccional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("si solo cambia el margen por defecto recalcula los que lo heredan (30 a 50 %: 160 pasa a 190) con historial MASIVO")
    void cambioDeMargenPorDefectoRecalculaLosHerederos() throws Exception {
        Articulo x = sinRubro(21L, "100.00", Moneda.ARS);
        assertThat(x.getPrecioFinal()).isEqualByComparingTo("160");
        when(articuloRepository.heredanMargenPorDefectoScoped()).thenReturn(List.of(x));

        RecalculoPreciosService.ResultadoRecalculo resultado = service.porCambioDeConfiguracion(
                CONFIG_ACTUAL, new ConfiguracionPrecios(bd("50.00"), bd("10.00")), USUARIO);

        // 100 x 1,50 x 1,21 = 181,50 -> multiplo 10 -> 190
        assertThat(x.getPrecioFinal()).isEqualByComparingTo("190");
        assertThat(resultado.articulosRecalculados()).isEqualTo(1);
        assertThat(resultado.avisos()).isEmpty();
        assertThat(historialGuardado()).singleElement().satisfies(h -> {
            assertThat(h.getOrigen()).isEqualTo(OrigenPrecio.MASIVO);
            assertThat(h.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(h.getPrecioAnterior()).isEqualByComparingTo("160");
            assertThat(h.getPrecioNuevo()).isEqualByComparingTo("190");
        });
        verify(articuloRepository, never()).activosConPrecioCalculadoScoped();
    }

    @Test
    @DisplayName("si cambia el multiplo recalcula todos los activos con precio calculado, cada uno con el margen de su rubro")
    void cambioDeMultiploRecalculaTodos() throws Exception {
        when(rubroRepository.listarScoped()).thenReturn(List.of(rubro("35.00")));
        Articulo delRubro = heredero(11L, "100.00");
        Articulo deLaFerreteria = sinRubro(21L, "100.00", Moneda.ARS);
        when(articuloRepository.activosConPrecioCalculadoScoped()).thenReturn(List.of(delRubro, deLaFerreteria));

        RecalculoPreciosService.ResultadoRecalculo resultado = service.porCambioDeConfiguracion(
                CONFIG_ACTUAL, new ConfiguracionPrecios(bd("30.00"), bd("0.00")), USUARIO);

        // Sin redondeo: 100 x 1,35 x 1,21 = 163,35 y 100 x 1,30 x 1,21 = 157,30
        assertThat(delRubro.getPrecioFinal()).isEqualByComparingTo("163.35");
        assertThat(deLaFerreteria.getPrecioFinal()).isEqualByComparingTo("157.30");
        assertThat(resultado.articulosRecalculados()).isEqualTo(2);
        assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(11L, 21L);
        verify(articuloRepository, never()).heredanMargenPorDefectoScoped();
    }

    @Test
    @DisplayName("si cambian los dos valores recalcula todos con el margen y el multiplo nuevos")
    void cambianLosDos() throws Exception {
        when(rubroRepository.listarScoped()).thenReturn(List.of());
        Articulo x = sinRubro(21L, "100.00", Moneda.ARS);
        when(articuloRepository.activosConPrecioCalculadoScoped()).thenReturn(List.of(x));

        service.porCambioDeConfiguracion(CONFIG_ACTUAL, new ConfiguracionPrecios(bd("50.00"), bd("0.50")), USUARIO);

        // 181,50 ya es multiplo de 0,50
        assertThat(x.getPrecioFinal()).isEqualByComparingTo("181.50");
    }

    @Test
    @DisplayName("si no cambia nada no consulta articulos ni guarda historial (35 y 35.00 son el mismo margen)")
    void sinCambioNoRecalcula() {
        RecalculoPreciosService.ResultadoRecalculo resultado = service.porCambioDeConfiguracion(
                CONFIG_ACTUAL, new ConfiguracionPrecios(bd("30"), bd("10")), USUARIO);

        assertThat(resultado.articulosRecalculados()).isZero();
        assertThat(resultado.avisos()).isEmpty();
        verifyNoInteractions(articuloRepository, historialPrecioRepository, dolarDePrecios);
    }

    @Test
    @DisplayName("un heredero en dolares usa el dolar vigente: 10 USD a 1000 con 50 % pasa de 15.730 a 18.150")
    void configuracionConArticuloEnDolares() throws Exception {
        when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
        Articulo usd = sinRubro(61L, "10.00", Moneda.USD);
        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("15730");
        when(articuloRepository.heredanMargenPorDefectoScoped()).thenReturn(List.of(usd));

        service.porCambioDeConfiguracion(CONFIG_ACTUAL, new ConfiguracionPrecios(bd("50.00"), bd("10.00")), USUARIO);

        assertThat(usd.getPrecioFinal()).isEqualByComparingTo("18150");
    }

    @Test
    @DisplayName("con un articulo en dolares y ninguna cotizacion: 409 y no se guarda historial")
    void configuracionSinCotizacionDa409() throws Exception {
        when(dolarDePrecios.vigente()).thenReturn(Optional.empty());
        when(articuloRepository.heredanMargenPorDefectoScoped()).thenReturn(List.of(sinRubro(61L, "10.00", Moneda.USD)));

        assertThatThrownBy(() -> service.porCambioDeConfiguracion(
                        CONFIG_ACTUAL, new ConfiguracionPrecios(bd("50.00"), bd("10.00")), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
        verify(historialPrecioRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("saltea al articulo cuyo ajuste lo dejaria en 0 o menos: conserva el precio, avisa y no lo cuenta")
    void configuracionSaltea() throws Exception {
        Articulo normal = sinRubro(21L, "100.00", Moneda.ARS);
        Articulo conAjuste = sinRubro(22L, "100.00", Moneda.ARS);
        conAjuste.fijarAjustesDePrecio(ajusteEnPesos("-150"), new ContextoPrecio(null, bd("30.00"), bd("10.00")));
        assertThat(conAjuste.getPrecioFinal()).isEqualByComparingTo("10.00");
        when(articuloRepository.heredanMargenPorDefectoScoped()).thenReturn(List.of(normal, conAjuste));

        RecalculoPreciosService.ResultadoRecalculo resultado = service.porCambioDeConfiguracion(
                CONFIG_ACTUAL, new ConfiguracionPrecios(bd("0.00"), bd("10.00")), USUARIO);

        assertThat(conAjuste.getPrecioFinal()).isEqualByComparingTo("10.00");
        assertThat(resultado.articulosRecalculados()).isEqualTo(1);
        assertThat(resultado.avisos()).containsExactly(aviso("SR-22", "Sin rubro 22"));
    }

    @Test
    @DisplayName("cuenta los que se recalcularian: herederos si cambia solo el margen, todos si cambia el multiplo, 0 si nada")
    void cuentaLosQueSeRecalcularian() {
        when(articuloRepository.contarHeredanMargenPorDefectoScoped()).thenReturn(3L);
        when(articuloRepository.contarActivosConPrecioCalculadoScoped()).thenReturn(40L);

        assertThat(service.contarPorCambioDeConfiguracion(CONFIG_ACTUAL, new ConfiguracionPrecios(bd("35"), bd("10"))))
                .isEqualTo(3L);
        assertThat(service.contarPorCambioDeConfiguracion(CONFIG_ACTUAL, new ConfiguracionPrecios(bd("30"), bd("1"))))
                .isEqualTo(40L);
        assertThat(service.contarPorCambioDeConfiguracion(CONFIG_ACTUAL, new ConfiguracionPrecios(bd("30"), bd("10"))))
                .isZero();
    }
}
