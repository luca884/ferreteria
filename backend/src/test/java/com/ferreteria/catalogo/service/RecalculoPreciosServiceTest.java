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
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
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
}
