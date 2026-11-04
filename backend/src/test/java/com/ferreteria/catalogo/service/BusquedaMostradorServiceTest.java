package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.ArticuloMostradorResponse;
import com.ferreteria.catalogo.dto.BusquedaMostradorResponse;
import com.ferreteria.catalogo.dto.TipoResultadoMostrador;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ConsultaMostrador;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BusquedaMostradorServiceTest {

    private static final Long FERRETERIA = 3L;

    @Mock
    ArticuloRepository articuloRepository;

    @InjectMocks
    BusquedaMostradorService service;

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- helpers ----

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** Articulo con los datos que ve el mostrador; el costo y el margen estan puestos para probar que no salen. */
    private static Articulo articulo(long id, String descripcion) {
        Articulo a = new Articulo(FERRETERIA);
        ReflectionTestUtils.setField(a, "id", id);
        ReflectionTestUtils.setField(a, "codigoInterno", "ART-" + id);
        ReflectionTestUtils.setField(a, "codigoBarras", "779" + id);
        ReflectionTestUtils.setField(a, "codigoProveedor", "PROV-" + id);
        ReflectionTestUtils.setField(a, "descripcion", descripcion);
        ReflectionTestUtils.setField(a, "costo", bd("100.00"));
        ReflectionTestUtils.setField(a, "margenPorcentaje", bd("50.00"));
        ReflectionTestUtils.setField(a, "precioFinal", bd("1234.50"));
        ReflectionTestUtils.setField(a, "unidad", Unidad.METRO);
        ReflectionTestUtils.setField(a, "stockActual", bd("2.500"));
        ReflectionTestUtils.setField(a, "stockMinimo", bd("5.000"));
        ReflectionTestUtils.setField(a, "ubicacion", "Estante 3");
        return a;
    }

    private static List<Articulo> articulos(int cantidad) {
        return IntStream.rangeClosed(1, cantidad).mapToObj(i -> articulo(i, "Articulo " + i)).toList();
    }

    // ---- consulta vacia (R-002) ----

    @Test
    @DisplayName("q nulo, vacio o en blanco: SIN_RESULTADOS y no consulta ningun articulo")
    void consultaVaciaNoConsulta() {
        for (String q : new String[] {null, "", "   ", "\n\t"}) {
            BusquedaMostradorResponse r = service.buscar(q);

            assertThat(r.tipo()).as("q=[%s]", q).isEqualTo(TipoResultadoMostrador.SIN_RESULTADOS);
            assertThat(r.items()).isEmpty();
            assertThat(r.hayMas()).isFalse();
        }
        verifyNoInteractions(articuloRepository);
    }

    @Test
    @DisplayName("sin tenant falla aunque q este en blanco (el super admin no busca)")
    void sinTenantFallaAunqueQSeaVacio() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.buscar("   ")).isInstanceOf(MissingTenantException.class);
        assertThatThrownBy(() -> service.buscar("7790001")).isInstanceOf(MissingTenantException.class);
        verifyNoInteractions(articuloRepository);
    }

    // ---- EXACTO (R-003) ----

    @Test
    @DisplayName("un exacto activo: EXACTO con ese articulo y sin mirar baja ni parciales")
    void unExactoActivoEsExacto() {
        when(articuloRepository.exactosActivosScoped("7790001", 21)).thenReturn(List.of(articulo(1, "Tornillo")));

        BusquedaMostradorResponse r = service.buscar("  7790001\n");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.EXACTO);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).descripcion()).isEqualTo("Tornillo");
        assertThat(r.hayMas()).isFalse();
        verify(articuloRepository, never()).coincidenciasMostradorScoped(any(), anyInt());
    }

    @Test
    @DisplayName("el exacto se busca con el codigo recortado de q")
    void elExactoUsaElCodigoRecortado() {
        when(articuloRepository.exactosActivosScoped(eq("TOR-014"), anyInt())).thenReturn(List.of(articulo(2, "Tuerca")));

        BusquedaMostradorResponse r = service.buscar("\tTOR-014 ");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.EXACTO);
        assertThat(r.items().get(0).id()).isEqualTo(2L);
    }

    // ---- mapeo y DTO fino (R-008) ----

    @Test
    @DisplayName("el mapeo copia los datos del mostrador y el precio final guardado, sin recalcular")
    void elMapeoCopiaLosDatosYElPrecioGuardado() {
        when(articuloRepository.exactosActivosScoped("7790007", 21)).thenReturn(List.of(articulo(7, "Cable")));

        ArticuloMostradorResponse a = service.buscar("7790007").items().get(0);

        assertThat(a.id()).isEqualTo(7L);
        assertThat(a.codigoInterno()).isEqualTo("ART-7");
        assertThat(a.codigoBarras()).isEqualTo("7797");
        assertThat(a.codigoProveedor()).isEqualTo("PROV-7");
        assertThat(a.descripcion()).isEqualTo("Cable");
        // Costo 100 + margen 50 no daria 1234.50: el valor sale tal cual de lo guardado.
        assertThat(a.precioFinal()).isEqualByComparingTo("1234.50");
        assertThat(a.unidad()).isEqualTo(Unidad.METRO);
        assertThat(a.stockActual()).isEqualByComparingTo("2.5");
        assertThat(a.stockMinimo()).isEqualByComparingTo("5");
        assertThat(a.ubicacion()).isEqualTo("Estante 3");
        assertThat(a.activo()).isTrue();
    }

    @Test
    @DisplayName("el DTO no tiene costo, margen, rubro, proveedor, IVA ni precio de lista; si dice si esta activo")
    void elDtoNoExponeCostoNiMargen() {
        List<String> componentes = Arrays.stream(ArticuloMostradorResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(componentes).containsExactly("id", "codigoInterno", "codigoBarras", "codigoProveedor",
                "descripcion", "precioFinal", "unidad", "stockActual", "stockMinimo", "ubicacion", "activo", "costoAl",
                "enOferta", "precioNormal");
        assertThat(componentes).doesNotContain("costo", "margen", "margenPorcentaje", "margenPropio",
                "alicuotaIva", "precioListaProveedor", "rubro", "rubroId", "proveedor", "proveedorId");
    }

    // ---- varios exactos: LISTA (R-004) ----

    @Test
    @DisplayName("dos o mas exactos activos: LISTA con solo esos, sin mas y sin consultar baja ni parciales")
    void variosExactosSonLista() {
        when(articuloRepository.exactosActivosScoped("7790002", 21))
                .thenReturn(List.of(articulo(1, "Arandela"), articulo(2, "Bulon")));

        BusquedaMostradorResponse r = service.buscar("7790002");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.LISTA);
        assertThat(r.items()).extracting(ArticuloMostradorResponse::id).containsExactly(1L, 2L);
        assertThat(r.hayMas()).isFalse();
        verify(articuloRepository, never()).coincidenciasMostradorScoped(any(), anyInt());
    }

    // ---- dados de baja: aparecen en la lista, marcados (H06) ----

    @Test
    @DisplayName("sin exacto activo, un dado de baja con ese codigo sale en la LISTA de parciales con activo false")
    void elDadoDeBajaSaleEnLaLista() {
        sinExacto("7790003");
        Articulo deBaja = articulo(3, "Taladro viejo");
        ReflectionTestUtils.setField(deBaja, "activo", false);
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21))).thenReturn(List.of(deBaja));

        BusquedaMostradorResponse r = service.buscar("7790003");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.LISTA);
        assertThat(r.items()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(3L);
            assertThat(a.activo()).isFalse();
        });
    }

    @Test
    @DisplayName("un dado de baja con codigo exacto no tapa a los activos que coinciden por palabra")
    void elDadoDeBajaNoTapaLaBusqueda() {
        sinExacto("tornillo");
        Articulo deBaja = articulo(5, "Viejo");
        ReflectionTestUtils.setField(deBaja, "activo", false);
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21)))
                .thenReturn(List.of(articulo(1, "Tornillo hexagonal"), deBaja));

        BusquedaMostradorResponse r = service.buscar("tornillo");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.LISTA);
        assertThat(r.items()).extracting(ArticuloMostradorResponse::activo).containsExactly(true, false);
    }

    @Test
    @DisplayName("un exacto activo gana sobre un inactivo con el mismo codigo: no se consulta la parcial")
    void elActivoGanaSobreElInactivo() {
        when(articuloRepository.exactosActivosScoped("7790004", 21)).thenReturn(List.of(articulo(4, "Activo")));

        BusquedaMostradorResponse r = service.buscar("7790004");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.EXACTO);
        verify(articuloRepository, never()).coincidenciasMostradorScoped(any(), anyInt());
    }

    // ---- parcial y limite (R-006, R-007) ----

    /** Sin exacto activo: la busqueda cae en la parcial. */
    private void sinExacto(String codigo) {
        when(articuloRepository.exactosActivosScoped(codigo, 21)).thenReturn(List.of());
    }

    @Test
    @DisplayName("sin exacto y con filas parciales: LISTA con esas filas")
    void parcialConFilasEsLista() {
        sinExacto("tornillo 1/4");
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21)))
                .thenReturn(List.of(articulo(1, "Tornillo autoperforante 1/4"), articulo(2, "Tornillo 1/4 largo")));

        BusquedaMostradorResponse r = service.buscar("tornillo 1/4");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.LISTA);
        assertThat(r.items()).extracting(ArticuloMostradorResponse::id).containsExactly(1L, 2L);
        assertThat(r.hayMas()).isFalse();
    }

    @Test
    @DisplayName("sin filas parciales: SIN_RESULTADOS")
    void parcialSinFilasEsSinResultados() {
        sinExacto("martillo");
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21))).thenReturn(List.of());

        BusquedaMostradorResponse r = service.buscar("martillo");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.SIN_RESULTADOS);
        assertThat(r.items()).isEmpty();
        assertThat(r.hayMas()).isFalse();
        verify(articuloRepository).coincidenciasMostradorScoped(any(), eq(21));
    }

    @Test
    @DisplayName("la parcial se consulta con las palabras de q y con LIMITE + 1")
    void laParcialSeConsultaConLasPalabrasYElLimiteMasUno() {
        sinExacto("tornillo 1/4");
        when(articuloRepository.coincidenciasMostradorScoped(any(), anyInt())).thenReturn(List.of(articulo(1, "Tornillo")));
        ArgumentCaptor<ConsultaMostrador> consulta = ArgumentCaptor.forClass(ConsultaMostrador.class);

        service.buscar("tornillo 1/4");

        verify(articuloRepository).coincidenciasMostradorScoped(consulta.capture(), eq(21));
        assertThat(consulta.getValue()).isEqualTo(ConsultaMostrador.de("tornillo 1/4"));
        assertThat(consulta.getValue().patrones()).containsExactly("%tornillo%", "%1/4%", null, null, null);
    }

    @Test
    @DisplayName("un solo resultado parcial es LISTA de una fila, no EXACTO")
    void unSoloParcialEsListaDeUnaFila() {
        sinExacto("torn");
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21))).thenReturn(List.of(articulo(9, "Tornillo")));

        BusquedaMostradorResponse r = service.buscar("torn");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.LISTA);
        assertThat(r.items()).hasSize(1);
        assertThat(r.hayMas()).isFalse();
    }

    @Test
    @DisplayName("20 filas: las 20 y hayMas false")
    void veinteFilasNoTienenMas() {
        sinExacto("cable");
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21))).thenReturn(articulos(20));

        BusquedaMostradorResponse r = service.buscar("cable");

        assertThat(r.items()).hasSize(20);
        assertThat(r.hayMas()).isFalse();
    }

    @Test
    @DisplayName("21 filas: las primeras 20 y hayMas true")
    void veintiunaFilasRecortanAVeinteYAvisan() {
        sinExacto("cable");
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21))).thenReturn(articulos(21));

        BusquedaMostradorResponse r = service.buscar("cable");

        assertThat(r.tipo()).isEqualTo(TipoResultadoMostrador.LISTA);
        assertThat(r.items()).hasSize(20);
        assertThat(r.items().get(0).id()).isEqualTo(1L);
        assertThat(r.items().get(19).id()).isEqualTo(20L);
        assertThat(r.hayMas()).isTrue();
    }

    @Test
    @DisplayName("hayMas solo es true en LISTA: EXACTO y SIN_RESULTADOS lo traen en false")
    void hayMasSoloEnLista() {
        when(articuloRepository.exactosActivosScoped("A", 21)).thenReturn(List.of(articulo(1, "Uno")));
        sinExacto("C");
        when(articuloRepository.coincidenciasMostradorScoped(any(), eq(21))).thenReturn(List.of());

        assertThat(service.buscar("A").hayMas()).isFalse();
        assertThat(service.buscar("C").hayMas()).isFalse();
    }
}
