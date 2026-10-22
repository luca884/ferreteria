package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.ActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.ActualizacionMasivaResponse;
import com.ferreteria.catalogo.dto.AplicarActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.VistaPreviaMasivaResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ConteosMasivos;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActualizacionMasivaServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long PROVEEDOR = 7L;
    private static final Long RUBRO = 4L;
    private static final Long USUARIO = 9L;

    @Mock
    ArticuloRepository articuloRepository;

    @Mock
    ProveedorRepository proveedorRepository;

    @Mock
    RubroRepository rubroRepository;

    @Mock
    HistorialPrecioRepository historialPrecioRepository;

    @Mock
    ImportacionListaRepository importacionListaRepository;

    @Mock
    ConfiguracionPreciosService configuracionPreciosService;

    @Mock
    DolarDePrecios dolarDePrecios;

    private static ValidatorFactory fabricaDeValidadores;

    ActualizacionMasivaService service;

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
        service = new ActualizacionMasivaService(
                articuloRepository, proveedorRepository, rubroRepository, historialPrecioRepository,
                configuracionPreciosService, fabricaDeValidadores.getValidator(), dolarDePrecios,
                importacionListaRepository);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- helpers ----

    private static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    private static void fijar(Object objeto, String campo, Object valor) throws Exception {
        Field field = objeto.getClass().getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    private static Proveedor proveedor() throws Exception {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Bulonera", null);
        fijar(proveedor, "id", PROVEEDOR);
        return proveedor;
    }

    private static Rubro rubro() throws Exception {
        Rubro rubro = new Rubro(FERRETERIA, "Buloneria", bd("35.00"));
        fijar(rubro, "id", RUBRO);
        return rubro;
    }

    /**
     * Articulo con id, costo `costo`, IVA 21 y el precio que da su contexto (margen del rubro 35 si tiene
     * rubro, 30 por defecto; multiplo 10). Sin margen propio salvo que se pase.
     */
    private static Articulo articulo(long id, String costo, Long rubroId, String margenPropio, boolean activo)
            throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        BigDecimal margenRubro = rubroId == null ? null : bd("35.00");
        articulo.actualizar(new DatosArticulo(
                        "INT-" + id, null, "PROV-" + id, "Articulo " + id, rubroId, PROVEEDOR, bd(costo),
                        bd(margenPropio), AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, activo),
                new ContextoPrecio(margenRubro, bd("30.00"), bd("10.00")));
        fijar(articulo, "id", id);
        return articulo;
    }

    /** Articulo sin rubro ni margen propio, activo: costo 100 da precio 160. */
    private static Articulo articulo(long id, String costo) throws Exception {
        return articulo(id, costo, null, null, true);
    }

    private static ActualizacionMasivaRequest pedido(
            Long proveedorId, Long rubroId, String q, boolean incluirInactivos, String modo, String valor) {
        return new ActualizacionMasivaRequest(proveedorId, rubroId, q, incluirInactivos, modo, bd(valor));
    }

    /** Filtro vacio, COSTO_PORCENTAJE +10. */
    private static ActualizacionMasivaRequest pedidoMasDiez() {
        return pedido(null, null, null, false, "COSTO_PORCENTAJE", "10");
    }

    /** Ferreteria con margen por defecto 30 y multiplo 10. */
    private void configuracionPorDefecto() {
        when(configuracionPreciosService.actual()).thenReturn(new ConfiguracionPrecios(bd("30.00"), bd("10.00")));
    }

    /** Todo lo que lee preparar despues del conteo: el alcance, los rubros y la configuracion. */
    private void alcance(FiltroArticulos filtro, List<Articulo> articulos, Rubro... rubros) {
        when(articuloRepository.contarScoped(filtro)).thenReturn((long) articulos.size());
        when(articuloRepository.filtrarScoped(filtro)).thenReturn(articulos);
        when(rubroRepository.listarScoped()).thenReturn(List.of(rubros));
        configuracionPorDefecto();
    }

    // ---- preparar (orden de validacion), observado a traves de vistaPrevia ----

    @Nested
    @DisplayName("preparar: 404, 400, tope y lecturas unicas")
    class Preparar {

        @Test
        @DisplayName("proveedor ajeno con valor 0: 404 del proveedor, no el 400 del cero, y no se lee nada mas")
        void proveedorAjenoAntesQueElCero() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(pedido(PROVEEDOR, null, null, false, "COSTO_PORCENTAJE", "0")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");

            verifyNoInteractions(articuloRepository, rubroRepository, configuracionPreciosService,
                    historialPrecioRepository);
        }

        @Test
        @DisplayName("rubro ajeno con modo invalido: 404 del rubro, no el 400 del modo")
        void rubroAjenoAntesQueElModo() {
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(pedido(null, RUBRO, null, false, "OTRO", "10")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Rubro no encontrado");

            verifyNoInteractions(articuloRepository, configuracionPreciosService, historialPrecioRepository);
            verify(rubroRepository, never()).listarScoped();
        }

        @Test
        @DisplayName("proveedor y rubro ajenos: el 404 es el del proveedor (se busca primero)")
        void proveedorAntesQueRubro() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(pedido(PROVEEDOR, RUBRO, null, false, "COSTO_PORCENTAJE", "10")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");

            verify(rubroRepository, never()).findScoped(RUBRO);
        }

        @Test
        @DisplayName("proveedor propio con valor 0: 400 del cero, sin leer articulos")
        void ceroDespuesDeLosNotFound() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));

            assertThatThrownBy(() -> service.vistaPrevia(pedido(PROVEEDOR, null, null, false, "COSTO_PORCENTAJE", "0")))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                            .containsOnly(Map.entry("porcentajeDistintoDeCero", "El porcentaje no puede ser cero.")));

            verifyNoInteractions(articuloRepository, configuracionPreciosService, historialPrecioRepository);
        }

        @Test
        @DisplayName("rubro propio con modo invalido: 400 del modo, un mensaje por campo")
        void modoInvalidoDespuesDeLosNotFound() throws Exception {
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(rubro()));

            assertThatThrownBy(() -> service.vistaPrevia(pedido(null, RUBRO, null, false, "OTRO", null)))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                            Map.entry("modo", "Elegí qué querés hacer: cambiar el costo en porcentaje, sumarle un monto al costo, "
                                    + "fijar el margen o cambiar el precio final en porcentaje o en monto."),
                            Map.entry("valor", "Ingresá un valor.")));

            verifyNoInteractions(articuloRepository, configuracionPreciosService);
        }

        @Test
        @DisplayName("filtro sin proveedor ni rubro: no se busca ninguno")
        void sinProveedorNiRubroNoBuscaNada() throws Exception {
            FiltroArticulos filtro = new FiltroArticulos(null, null, null, false);
            alcance(filtro, List.of(articulo(1, "100.00")));

            service.vistaPrevia(pedidoMasDiez());

            verifyNoInteractions(proveedorRepository);
            verify(rubroRepository, never()).findScoped(RUBRO);
        }

        @Test
        @DisplayName("tope: 20.001 artículos dan 400 con el numero en formato argentino y no se cargan")
        void topeSuperado() {
            FiltroArticulos filtro = new FiltroArticulos(null, null, null, false);
            when(articuloRepository.contarScoped(filtro)).thenReturn(20_001L);

            assertThatThrownBy(() -> service.vistaPrevia(pedidoMasDiez()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El filtro abarca 20.001 artículos y el máximo es 20.000. Afiná el filtro.");

            verify(articuloRepository, never()).filtrarScoped(filtro);
            verifyNoInteractions(rubroRepository, configuracionPreciosService, historialPrecioRepository);
        }

        @Test
        @DisplayName("tope: el numero del mensaje usa el punto de miles tambien para cantidades mas grandes")
        void topeConNumeroGrande() {
            FiltroArticulos filtro = new FiltroArticulos(null, null, null, false);
            when(articuloRepository.contarScoped(filtro)).thenReturn(1_234_567L);

            assertThatThrownBy(() -> service.vistaPrevia(pedidoMasDiez()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El filtro abarca 1.234.567 artículos y el máximo es 20.000. Afiná el filtro.");
        }

        @Test
        @DisplayName("tope: exactamente 20.000 se procesa")
        void exactamenteElTope() throws Exception {
            FiltroArticulos filtro = new FiltroArticulos(null, null, null, false);
            when(articuloRepository.contarScoped(filtro)).thenReturn(20_000L);
            when(articuloRepository.filtrarScoped(filtro)).thenReturn(List.of(articulo(1, "100.00")));
            when(rubroRepository.listarScoped()).thenReturn(List.of());
            configuracionPorDefecto();

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.conteos().total()).isEqualTo(1);
            verify(articuloRepository, times(1)).filtrarScoped(filtro);
        }

        @Test
        @DisplayName("el filtro que llega al repositorio es el del pedido, con el texto sin espacios sobrantes")
        void elRepositorioRecibeElFiltroNormalizado() throws Exception {
            FiltroArticulos filtro = new FiltroArticulos(PROVEEDOR, RUBRO, "cable", true);
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(rubro()));
            alcance(filtro, List.of(articulo(1, "100.00", RUBRO, null, false)), rubro());

            service.vistaPrevia(pedido(PROVEEDOR, RUBRO, "  cable ", true, "COSTO_PORCENTAJE", "10"));

            verify(articuloRepository).contarScoped(filtro);
            verify(articuloRepository).filtrarScoped(filtro);
        }

        @Test
        @DisplayName("lecturas: el conteo, el alcance, los rubros, la configuracion y el proveedor, una sola vez")
        void lecturasUnaSolaVez() throws Exception {
            FiltroArticulos filtro = new FiltroArticulos(PROVEEDOR, null, null, false);
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            alcance(filtro, List.of(articulo(1, "100.00"), articulo(2, "200.00"), articulo(3, "300.00")));

            service.vistaPrevia(pedido(PROVEEDOR, null, null, false, "COSTO_PORCENTAJE", "10"));

            verify(proveedorRepository, times(1)).findScoped(PROVEEDOR);
            verify(articuloRepository, times(1)).contarScoped(filtro);
            verify(articuloRepository, times(1)).filtrarScoped(filtro);
            verify(rubroRepository, times(1)).listarScoped();
            verify(configuracionPreciosService, times(1)).actual();
        }

        @Test
        @DisplayName("devuelve el plan: contadores, cambios con antes y despues, y una huella de 64 hex")
        void devuelveElPlan() throws Exception {
            FiltroArticulos filtro = new FiltroArticulos(null, null, null, false);
            alcance(filtro, List.of(articulo(1, "100.00"), articulo(2, "200.00")));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.conteos().total()).isEqualTo(2);
            assertThat(respuesta.conteos().suben()).isEqualTo(2);
            assertThat(respuesta.conteos().modificados()).isEqualTo(2);
            assertThat(respuesta.cambios()).hasSize(2);
            assertThat(respuesta.cambios().get(0).articuloId()).isEqualTo(1L);
            assertThat(respuesta.cambios().get(0).costoNuevo()).isEqualByComparingTo("110.00");
            assertThat(respuesta.cambios().get(0).precioAnterior()).isEqualByComparingTo("160.00");
            assertThat(respuesta.cambios().get(0).precioNuevo()).isEqualByComparingTo("180.00");
            assertThat(respuesta.errores()).isEmpty();
            assertThat(respuesta.huella()).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("el costo de cada articulo se calcula con el contexto de SU rubro")
        void usaElContextoDelRubro() throws Exception {
            FiltroArticulos filtro = new FiltroArticulos(null, null, null, false);
            // Con rubro (margen 35): 100 * 1.35 * 1.21 = 163.35 -> 170. Sin rubro (margen 30): 160.
            alcance(filtro, List.of(articulo(1, "100.00", RUBRO, null, true), articulo(2, "100.00")), rubro());

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(
                    pedido(null, null, null, false, "MARGEN", "50"));

            assertThat(respuesta.cambios()).hasSize(2);
            assertThat(respuesta.cambios().get(0).precioAnterior()).isEqualByComparingTo("170.00");
            assertThat(respuesta.cambios().get(1).precioAnterior()).isEqualByComparingTo("160.00");
            assertThat(respuesta.cambios().get(0).margenAnterior()).isEqualByComparingTo("35.00");
            assertThat(respuesta.cambios().get(1).margenAnterior()).isEqualByComparingTo("30.00");
        }
    }

    // ---- vista previa: sin efectos y detalle acotado ----

    /** Articulos con ids de `desde` a `hasta` (incluidos), todos con ese costo y sin rubro. */
    private static List<Articulo> articulos(long desde, long hasta, String costo) throws Exception {
        List<Articulo> articulos = new ArrayList<>();
        for (long id = desde; id <= hasta; id++) {
            articulos.add(articulo(id, costo));
        }
        return articulos;
    }

    private static List<Articulo> juntar(List<Articulo> uno, List<Articulo> otro) {
        List<Articulo> todos = new ArrayList<>(uno);
        todos.addAll(otro);
        return todos;
    }

    @Nested
    @DisplayName("vistaPrevia: no escribe y recorta el detalle")
    class VistaPrevia {

        private final FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        @Test
        @DisplayName("no guarda nada: ni articulos ni historial, y las entidades quedan como estaban")
        void noEscribeNada() throws Exception {
            Articulo uno = articulo(1, "100.00");
            Articulo dos = articulo(2, "200.00", RUBRO, "50.00", true);
            alcance(vacio, List.of(uno, dos), rubro());

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.conteos().modificados()).isEqualTo(2);
            verify(articuloRepository, never()).save(any());
            verify(articuloRepository, never()).saveAll(any());
            verifyNoInteractions(historialPrecioRepository);
            assertThat(uno.getCosto()).isEqualByComparingTo("100.00");
            assertThat(uno.getPrecioFinal()).isEqualByComparingTo("160.00");
            assertThat(dos.getCosto()).isEqualByComparingTo("200.00");
            assertThat(dos.getMargenPorcentaje()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("1.001 cambios: trae las 1.000 filas de id mas bajo, detalleRecortado y contadores sin recortar")
        void recortaLosCambios() throws Exception {
            alcance(vacio, articulos(1, 1001, "100.00"));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.cambios()).hasSize(1000);
            assertThat(respuesta.cambios().get(0).articuloId()).isEqualTo(1L);
            assertThat(respuesta.cambios().get(999).articuloId()).isEqualTo(1000L);
            assertThat(respuesta.detalleRecortado()).isTrue();
            assertThat(respuesta.conteos().total()).isEqualTo(1001);
            assertThat(respuesta.conteos().suben()).isEqualTo(1001);
            assertThat(respuesta.conteos().modificados()).isEqualTo(1001);
        }

        @Test
        @DisplayName("exactamente 1.000 cambios y 1.000 errores: nada se recorta")
        void exactamenteElMaximoEnCadaLista() throws Exception {
            // Costo 0.00 con +10 da un costo nuevo de 0: error de fila.
            alcance(vacio, juntar(articulos(1, 1000, "100.00"), articulos(1001, 2000, "0.00")));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.cambios()).hasSize(1000);
            assertThat(respuesta.errores()).hasSize(1000);
            assertThat(respuesta.detalleRecortado()).isFalse();
            assertThat(respuesta.conteos().conError()).isEqualTo(1000);
        }

        @Test
        @DisplayName("1.001 errores: recorta los errores, detalleRecortado, y conError sigue en 1.001")
        void recortaLosErrores() throws Exception {
            alcance(vacio, juntar(articulos(1, 3, "100.00"), articulos(4, 1004, "0.00")));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.errores()).hasSize(1000);
            assertThat(respuesta.errores().get(0).articuloId()).isEqualTo(4L);
            assertThat(respuesta.errores().get(999).articuloId()).isEqualTo(1003L);
            assertThat(respuesta.cambios()).hasSize(3);
            assertThat(respuesta.detalleRecortado()).isTrue();
            assertThat(respuesta.conteos().conError()).isEqualTo(1001);
            assertThat(respuesta.conteos().total()).isEqualTo(1004);
        }

        @Test
        @DisplayName("alcance vacio: 200 con todo en cero, sin filas y sin recorte")
        void alcanceVacio() {
            alcance(vacio, List.of());

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.conteos()).isEqualTo(new ConteosMasivos(0, 0, 0, 0, 0, 0, 0, 0));
            assertThat(respuesta.cambios()).isEmpty();
            assertThat(respuesta.errores()).isEmpty();
            assertThat(respuesta.detalleRecortado()).isFalse();
            assertThat(respuesta.huella()).matches("[0-9a-f]{64}");
            verifyNoInteractions(historialPrecioRepository);
        }
    }

    // ---- aplicar: lo que pasa antes de escribir ----

    private static AplicarActualizacionMasivaRequest aplicarPedido(
            Long proveedorId, Long rubroId, String q, boolean incluirInactivos, String modo, String valor,
            String huella) {
        return new AplicarActualizacionMasivaRequest(proveedorId, rubroId, q, incluirInactivos, modo, bd(valor), huella);
    }

    /** Filtro vacio, COSTO_PORCENTAJE +10, con esa huella. */
    private static AplicarActualizacionMasivaRequest aplicarMasDiez(String huella) {
        return aplicarPedido(null, null, null, false, "COSTO_PORCENTAJE", "10", huella);
    }

    private void verificarSinEscrituras() {
        verify(articuloRepository, never()).save(any());
        verify(articuloRepository, never()).saveAll(any());
        verify(historialPrecioRepository, never()).save(any());
        verify(historialPrecioRepository, never()).saveAll(any());
    }

    @Nested
    @DisplayName("aplicar: validaciones, alcance vacio, tope y deriva (todo antes de escribir)")
    class AplicarSinEscribir {

        private final FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        @Test
        @DisplayName("alcance vacio con una huella vieja: 400 del alcance vacio, no el 409, y no se carga nada")
        void alcanceVacioAntesQueLaDeriva() {
            when(articuloRepository.contarScoped(vacio)).thenReturn(0L);

            assertThatThrownBy(() -> service.aplicar(aplicarMasDiez("huella-vieja"), USUARIO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No hay artículos para actualizar con ese filtro.");

            verify(articuloRepository, never()).filtrarScoped(any());
            verifyNoInteractions(rubroRepository, configuracionPreciosService);
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("tope superado al aplicar: 400 del tope (no 409), aunque la huella no coincida")
        void topeAlAplicar() {
            when(articuloRepository.contarScoped(vacio)).thenReturn(20_010L);

            assertThatThrownBy(() -> service.aplicar(aplicarMasDiez("huella-vieja"), USUARIO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El filtro abarca 20.010 artículos y el máximo es 20.000. Afiná el filtro.");

            verify(articuloRepository, never()).filtrarScoped(any());
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("huella distinta: 409 y no se escribe ni se muta nada")
        void huellaDistinta() throws Exception {
            Articulo uno = articulo(1, "100.00");
            Articulo dos = articulo(2, "200.00", RUBRO, "50.00", true);
            alcance(vacio, List.of(uno, dos), rubro());

            assertThatThrownBy(() -> service.aplicar(aplicarMasDiez("0".repeat(64)), USUARIO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Los artículos cambiaron desde la vista previa. "
                            + "Volvé a generar la vista previa y confirmá de nuevo.");

            verificarSinEscrituras();
            assertThat(uno.getCosto()).isEqualByComparingTo("100.00");
            assertThat(uno.getPrecioFinal()).isEqualByComparingTo("160.00");
            assertThat(dos.getCosto()).isEqualByComparingTo("200.00");
            assertThat(dos.getMargenPorcentaje()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("huella 'abc': 409")
        void huellaQueNoCorrespondeANada() throws Exception {
            alcance(vacio, List.of(articulo(1, "100.00")));

            assertThatThrownBy(() -> service.aplicar(aplicarMasDiez("abc"), USUARIO))
                    .isInstanceOf(ConflictException.class);

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("la misma huella pero con otro valor: 409 (la huella incluye la operacion)")
        void otroValorConLaMismaHuella() throws Exception {
            alcance(vacio, List.of(articulo(1, "100.00")));
            String huellaConDiez = service.vistaPrevia(pedidoMasDiez()).huella();

            assertThatThrownBy(() -> service.aplicar(
                            aplicarPedido(null, null, null, false, "COSTO_PORCENTAJE", "15", huellaConDiez), USUARIO))
                    .isInstanceOf(ConflictException.class);

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("todos con error (modificados=0, total>0): 201 con los contadores, 0 entradas y sin escribir")
        void todosConError() throws Exception {
            // Costo 0.00 con +10 da un costo nuevo de 0: error de fila.
            Articulo uno = articulo(1, "0.00");
            Articulo dos = articulo(2, "0.00");
            alcance(vacio, List.of(uno, dos));
            String huella = service.vistaPrevia(pedidoMasDiez()).huella();

            ActualizacionMasivaResponse respuesta = service.aplicar(aplicarMasDiez(huella), USUARIO);

            assertThat(respuesta.conteos()).isEqualTo(new ConteosMasivos(2, 0, 0, 0, 2, 0, 0, 0));
            assertThat(respuesta.entradasDeHistorial()).isZero();
            verificarSinEscrituras();
            assertThat(uno.getCosto()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("nada cambia (modificados=0, total>0): 201 con los contadores, 0 entradas y sin escribir")
        void nadaCambia() throws Exception {
            // MARGEN 30 sobre un articulo que ya tiene margen propio 30: costo, margen y precio iguales.
            Articulo igual = articulo(1, "100.00", null, "30.00", true);
            alcance(vacio, List.of(igual));
            ActualizacionMasivaRequest vistaPrevia = pedido(null, null, null, false, "MARGEN", "30");
            String huella = service.vistaPrevia(vistaPrevia).huella();

            ActualizacionMasivaResponse respuesta = service.aplicar(
                    aplicarPedido(null, null, null, false, "MARGEN", "30", huella), USUARIO);

            assertThat(respuesta.conteos()).isEqualTo(new ConteosMasivos(1, 0, 0, 1, 0, 0, 0, 0));
            assertThat(respuesta.entradasDeHistorial()).isZero();
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("404 del proveedor y del rubro antes que el 400 de la huella y del valor")
        void notFoundAntesQueValidacion() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aplicar(
                            aplicarPedido(PROVEEDOR, null, null, false, "COSTO_PORCENTAJE", "0", " "), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");

            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.aplicar(
                            aplicarPedido(null, RUBRO, null, false, "OTRO", null, null), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Rubro no encontrado");

            verifyNoInteractions(articuloRepository, historialPrecioRepository);
        }

        @Test
        @DisplayName("sin huella o con valor invalido: 400 por campo, antes de contar nada")
        void validacionDeAplicar() {
            assertThatThrownBy(() -> service.aplicar(
                            aplicarPedido(null, null, null, false, "COSTO_PORCENTAJE", "0", " "), USUARIO))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                            Map.entry("huella", "Falta la vista previa. Generala de nuevo antes de confirmar."),
                            Map.entry("porcentajeDistintoDeCero", "El porcentaje no puede ser cero.")));
            assertThatThrownBy(() -> service.aplicar(aplicarMasDiez(null), USUARIO))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                            .containsOnly(Map.entry("huella", "Falta la vista previa. Generala de nuevo antes de confirmar.")));

            verifyNoInteractions(articuloRepository, historialPrecioRepository);
        }
    }

    // ---- aplicar: escribe ----

    private static final ContextoPrecio SIN_RUBRO = new ContextoPrecio(null, new BigDecimal("30.00"), new BigDecimal("10.00"));
    private static final ContextoPrecio DEL_RUBRO =
            new ContextoPrecio(new BigDecimal("35.00"), new BigDecimal("30.00"), new BigDecimal("10.00"));

    /** La huella que daria la vista previa del mismo pedido sobre el mismo estado. */
    private String huellaDe(ActualizacionMasivaRequest vistaPrevia) {
        return service.vistaPrevia(vistaPrevia).huella();
    }

    private List<HistorialPrecio> historialGuardado() {
        ArgumentCaptor<Iterable<HistorialPrecio>> captor = ArgumentCaptor.captor();
        verify(historialPrecioRepository, times(1)).saveAll(captor.capture());
        List<HistorialPrecio> historial = new ArrayList<>();
        captor.getValue().forEach(historial::add);
        return historial;
    }

    @Nested
    @DisplayName("aplicar: modifica los articulos y escribe el historial MASIVO")
    class AplicarEscribiendo {

        private final FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        @Test
        @DisplayName("COSTO_PORCENTAJE: cambia costo y precio, una entrada MASIVO por articulo, un solo saveAll")
        void costoPorcentaje() throws Exception {
            Articulo uno = articulo(1, "100.00");
            Articulo dos = articulo(2, "200.00", RUBRO, null, true);
            alcance(vacio, List.of(uno, dos), rubro());
            String huella = huellaDe(pedidoMasDiez());

            ActualizacionMasivaResponse respuesta = service.aplicar(aplicarMasDiez(huella), USUARIO);

            // 100 +10% = 110: 110 * 1.30 * 1.21 = 173.03 -> 180. 200 +10% = 220: 220 * 1.35 * 1.21 = 359.37 -> 360.
            assertThat(uno.getCosto()).isEqualByComparingTo("110.00");
            assertThat(uno.getPrecioFinal()).isEqualByComparingTo("180.00");
            assertThat(dos.getCosto()).isEqualByComparingTo("220.00");
            assertThat(dos.getPrecioFinal()).isEqualByComparingTo("360.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(2);
            assertThat(historial).extracting(HistorialPrecio::getArticuloId).containsExactly(1L, 2L);
            assertThat(historial).extracting(HistorialPrecio::getOrigen).containsOnly(OrigenPrecio.MASIVO);
            assertThat(historial).extracting(HistorialPrecio::getUsuarioId).containsOnly(USUARIO);
            assertThat(historial).extracting(HistorialPrecio::getFerreteriaId).containsOnly(FERRETERIA);
            assertThat(historial.get(0).getCostoAnterior()).isEqualByComparingTo("100.00");
            assertThat(historial.get(0).getCostoNuevo()).isEqualByComparingTo("110.00");
            assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("160.00");
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("180.00");
            assertThat(historial.get(1).getCostoNuevo()).isEqualByComparingTo("220.00");
            assertThat(historial.get(1).getPrecioNuevo()).isEqualByComparingTo("360.00");
            assertThat(respuesta.conteos()).isEqualTo(new ConteosMasivos(2, 2, 0, 0, 0, 0, 0, 2));
            assertThat(respuesta.entradasDeHistorial()).isEqualTo(2);
            verify(articuloRepository, never()).save(any());
            verify(articuloRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("COSTO_PORCENTAJE llama ajustarCosto con el contexto de SU rubro, y nunca fijarMargen")
        void usaAjustarCostoConElContextoDelRubro() throws Exception {
            Articulo sinRubro = spy(articulo(1, "100.00"));
            Articulo conRubro = spy(articulo(2, "200.00", RUBRO, null, true));
            alcance(vacio, List.of(sinRubro, conRubro), rubro());
            String huella = huellaDe(pedidoMasDiez());

            service.aplicar(aplicarMasDiez(huella), USUARIO);

            verify(sinRubro).ajustarCosto(argThat(c -> c.compareTo(bd("110.00")) == 0), eq(SIN_RUBRO));
            verify(conRubro).ajustarCosto(argThat(c -> c.compareTo(bd("220.00")) == 0), eq(DEL_RUBRO));
            verify(sinRubro, never()).fijarMargen(any(), any());
            verify(conRubro, never()).fijarMargen(any(), any());
        }

        @Test
        @DisplayName("MARGEN: llama fijarMargen (nunca ajustarCosto); solo se mutan los modificados")
        void margenSoloMutaLosModificados() throws Exception {
            // B1: sin margen propio (rubro 35), precio 170 -> 190. B2: ya tiene 50, precio 190, no cambia.
            // B3: margen propio 30 sin rubro, precio 160 -> 190.
            Articulo b1 = spy(articulo(1, "100.00", RUBRO, null, true));
            Articulo b2 = spy(articulo(2, "100.00", null, "50.00", true));
            Articulo b3 = spy(articulo(3, "100.00", null, "30.00", true));
            alcance(vacio, List.of(b1, b2, b3), rubro());
            String huella = huellaDe(pedido(null, null, null, false, "MARGEN", "50"));

            ActualizacionMasivaResponse respuesta = service.aplicar(
                    aplicarPedido(null, null, null, false, "MARGEN", "50", huella), USUARIO);

            verify(b1).fijarMargen(argThat(m -> m.compareTo(bd("50.00")) == 0), eq(DEL_RUBRO));
            verify(b3).fijarMargen(argThat(m -> m.compareTo(bd("50.00")) == 0), eq(SIN_RUBRO));
            verify(b2, never()).fijarMargen(any(), any());
            verify(b1, never()).ajustarCosto(any(), any());
            verify(b2, never()).ajustarCosto(any(), any());
            verify(b3, never()).ajustarCosto(any(), any());
            assertThat(b1.getMargenPorcentaje()).isEqualByComparingTo("50.00");
            assertThat(b1.getPrecioFinal()).isEqualByComparingTo("190.00");
            assertThat(b3.getMargenPorcentaje()).isEqualByComparingTo("50.00");
            assertThat(b3.getPrecioFinal()).isEqualByComparingTo("190.00");
            assertThat(b2.getPrecioFinal()).isEqualByComparingTo("190.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).extracting(HistorialPrecio::getArticuloId).containsExactly(1L, 3L);
            assertThat(historial.get(0).getCostoAnterior()).isEqualByComparingTo("100.00");
            assertThat(historial.get(0).getCostoNuevo()).isEqualByComparingTo("100.00");
            assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("170.00");
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("190.00");
            assertThat(respuesta.conteos().modificados()).isEqualTo(2);
            assertThat(respuesta.conteos().pasanAMargenPropio()).isEqualTo(1);
            assertThat(respuesta.entradasDeHistorial()).isEqualTo(2);
        }

        @Test
        @DisplayName("cambia el costo pero no el precio: hay entrada de historial (A3: 1,00 a 1,10, precio 10,00)")
        void cambiaElCostoYNoElPrecio() throws Exception {
            // 1.00 * 1.30 * 1.21 = 1.573 -> 10; 1.10 * 1.30 * 1.21 = 1.7303 -> 10.
            Articulo a3 = articulo(3, "1.00");
            alcance(vacio, List.of(a3));
            String huella = huellaDe(pedidoMasDiez());

            ActualizacionMasivaResponse respuesta = service.aplicar(aplicarMasDiez(huella), USUARIO);

            assertThat(a3.getCosto()).isEqualByComparingTo("1.10");
            assertThat(a3.getPrecioFinal()).isEqualByComparingTo("10.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(1);
            assertThat(historial.get(0).getCostoAnterior()).isEqualByComparingTo("1.00");
            assertThat(historial.get(0).getCostoNuevo()).isEqualByComparingTo("1.10");
            assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("10.00");
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("10.00");
            assertThat(respuesta.conteos().sinCambioDePrecio()).isEqualTo(1);
            assertThat(respuesta.entradasDeHistorial()).isEqualTo(1);
        }

        @Test
        @DisplayName("cambia solo el margen propio (costo y precio iguales): se muta el articulo y NO hay entrada ni saveAll")
        void cambiaSoloElMargenSinHistorial() throws Exception {
            // Sin rubro y sin margen propio, el precio es 160 con el margen por defecto 30; fijar 30 no lo mueve.
            Articulo articulo = articulo(1, "100.00");
            alcance(vacio, List.of(articulo));
            String huella = huellaDe(pedido(null, null, null, false, "MARGEN", "30"));

            ActualizacionMasivaResponse respuesta = service.aplicar(
                    aplicarPedido(null, null, null, false, "MARGEN", "30", huella), USUARIO);

            assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("30.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("160.00");
            assertThat(respuesta.conteos().modificados()).isEqualTo(1);
            assertThat(respuesta.conteos().sinCambioDePrecio()).isEqualTo(1);
            assertThat(respuesta.entradasDeHistorial()).isZero();
            verify(historialPrecioRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("el precio de lista del proveedor y los demas campos quedan intactos, tambien en un dado de baja")
        void camposQueNoSeTocan() throws Exception {
            Articulo baja = articulo(1, "100.00", null, null, false);
            baja.registrarPrecioLista(bd("250.00"));
            Articulo activo = articulo(2, "100.00");
            activo.registrarPrecioLista(bd("260.00"));
            FiltroArticulos conInactivos = new FiltroArticulos(null, null, null, true);
            alcance(conInactivos, List.of(baja, activo));
            ActualizacionMasivaRequest vistaPrevia = pedido(null, null, null, true, "COSTO_PORCENTAJE", "10");
            String huella = huellaDe(vistaPrevia);

            service.aplicar(aplicarPedido(null, null, null, true, "COSTO_PORCENTAJE", "10", huella), USUARIO);

            assertThat(baja.getCosto()).isEqualByComparingTo("110.00");
            assertThat(baja.isActivo()).isFalse();
            assertThat(baja.getPrecioListaProveedor()).isEqualByComparingTo("250.00");
            assertThat(activo.getPrecioListaProveedor()).isEqualByComparingTo("260.00");
            assertThat(baja.getDescripcion()).isEqualTo("Articulo 1");
            assertThat(baja.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(baja.getStockMinimo()).isEqualByComparingTo("0");
            assertThat(historialGuardado()).hasSize(2);
        }

        @Test
        @DisplayName("los articulos con error no se tocan y no frenan a los demas")
        void losErroresNoSeEscriben() throws Exception {
            Articulo bueno = articulo(1, "100.00");
            Articulo conError = articulo(2, "0.00");
            alcance(vacio, List.of(bueno, conError));
            String huella = huellaDe(pedidoMasDiez());

            ActualizacionMasivaResponse respuesta = service.aplicar(aplicarMasDiez(huella), USUARIO);

            assertThat(bueno.getCosto()).isEqualByComparingTo("110.00");
            assertThat(conError.getCosto()).isEqualByComparingTo("0.00");
            assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(1L);
            assertThat(respuesta.conteos().conError()).isEqualTo(1);
            assertThat(respuesta.conteos().total()).isEqualTo(2);
            assertThat(respuesta.entradasDeHistorial()).isEqualTo(1);
        }

        @Test
        @DisplayName("invariante: si el precio de la entidad difiere del del plan, IllegalStateException y no se guarda el historial")
        void invarianteDelPrecio() throws Exception {
            // El precio que informa la entidad queda fijo en 999, asi que despues de ajustar el costo no
            // coincide con el 180 que calculo el planificador.
            Articulo desviado = spy(articulo(1, "100.00"));
            doReturn(bd("999.00")).when(desviado).getPrecioFinal();
            alcance(vacio, List.of(desviado));
            String huella = huellaDe(pedidoMasDiez());

            assertThatThrownBy(() -> service.aplicar(aplicarMasDiez(huella), USUARIO))
                    .isInstanceOf(IllegalStateException.class);

            verify(historialPrecioRepository, never()).saveAll(any());
        }
    }

    // ---- articulos en dolares (T-036) ----

    @Nested
    @DisplayName("articulos en USD: el plan y el aplicar usan el dolar vigente de la ferreteria")
    class ArticulosEnDolares {

        private final FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        /** Articulo en USD sin rubro ni margen propio (margen de la ferreteria 30, IVA 21), calculado con ese dolar. */
        private Articulo enDolares(long id, String costo, String dolarConElQueSeCalculo) throws Exception {
            Articulo articulo = new Articulo(FERRETERIA);
            articulo.actualizar(new DatosArticulo(
                            "INT-" + id, null, "PROV-" + id, "Articulo USD " + id, null, PROVEEDOR, bd(costo), null,
                            AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, Moneda.USD),
                    new ContextoPrecio(null, bd("30.00"), bd("10.00"), bd(dolarConElQueSeCalculo)));
            fijar(articulo, "id", id);
            return articulo;
        }

        private void vigente(String dolar) {
            when(dolarDePrecios.vigente()).thenReturn(dolar == null ? Optional.empty() : Optional.of(bd(dolar)));
        }

        @Test
        @DisplayName("vista previa: 10 USD +10 % de costo con el vigente 1000 pasa de 15.730 a 17.310 (11 x 1000 x 1,30 x 1,21 = 17.303)")
        void vistaPreviaUsaElVigente() throws Exception {
            alcance(vacio, List.of(enDolares(1, "10.00", "1000")));
            vigente("1000");

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.errores()).isEmpty();
            assertThat(respuesta.cambios()).hasSize(1);
            assertThat(respuesta.cambios().get(0).costoNuevo()).isEqualByComparingTo("11.00");
            assertThat(respuesta.cambios().get(0).precioAnterior()).isEqualByComparingTo("15730");
            assertThat(respuesta.cambios().get(0).precioNuevo()).isEqualByComparingTo("17310");
        }

        @Test
        @DisplayName("el dolar es el vigente de la ferreteria, no uno fijo: con 950 el mismo ajuste da 16.440")
        void otroVigenteOtroPrecio() throws Exception {
            alcance(vacio, List.of(enDolares(1, "10.00", "1000")));
            vigente("950");

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            // 11 x 950 = 10450; x 1,30 = 13585; x 1,21 = 16437,85 -> 16440
            assertThat(respuesta.cambios().get(0).precioNuevo()).isEqualByComparingTo("16440");
        }

        @Test
        @DisplayName("aplicar: el articulo USD queda con costo 11 USD y precio 17.310, con un MASIVO en USD")
        void aplicarUsaElVigente() throws Exception {
            Articulo usd = enDolares(1, "10.00", "1000");
            alcance(vacio, List.of(usd));
            vigente("1000");
            String huella = huellaDe(pedidoMasDiez());

            service.aplicar(aplicarMasDiez(huella), USUARIO);

            assertThat(usd.getCosto()).isEqualByComparingTo("11.00");
            assertThat(usd.getMoneda()).isEqualTo(Moneda.USD);
            assertThat(usd.getPrecioFinal()).isEqualByComparingTo("17310");
            assertThat(historialGuardado()).singleElement().satisfies(h -> {
                assertThat(h.getOrigen()).isEqualTo(OrigenPrecio.MASIVO);
                assertThat(h.getMoneda()).isEqualTo(Moneda.USD);
                assertThat(h.getPrecioAnterior()).isEqualByComparingTo("15730");
                assertThat(h.getPrecioNuevo()).isEqualByComparingTo("17310");
            });
        }

        @Test
        @DisplayName("MARGEN sobre un USD: fijar 40 % con el vigente 1000 da 16.940")
        void margenSobreUnUsd() throws Exception {
            Articulo usd = enDolares(1, "10.00", "1000");
            alcance(vacio, List.of(usd));
            vigente("1000");
            ActualizacionMasivaRequest vistaPrevia = pedido(null, null, null, false, "MARGEN", "40");
            String huella = huellaDe(vistaPrevia);

            service.aplicar(aplicarPedido(null, null, null, false, "MARGEN", "40", huella), USUARIO);

            assertThat(usd.getPrecioFinal()).isEqualByComparingTo("16940");
            assertThat(usd.getCosto()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("un alcance con pesos y dolares calcula cada uno con lo suyo: ARS 160 -> 180 y USD 15.730 -> 17.310")
        void mezclaDeMonedas() throws Exception {
            alcance(vacio, List.of(articulo(1, "100.00"), enDolares(2, "10.00", "1000")));
            vigente("1000");

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoMasDiez());

            assertThat(respuesta.cambios()).hasSize(2);
            assertThat(respuesta.cambios().get(0).precioNuevo()).isEqualByComparingTo("180");
            assertThat(respuesta.cambios().get(1).precioNuevo()).isEqualByComparingTo("17310");
        }

        @Test
        @DisplayName("si no hay ningun articulo en USD en el alcance no se consulta el dolar")
        void sinUsdNoConsultaElDolar() throws Exception {
            alcance(vacio, List.of(articulo(1, "100.00")));

            service.vistaPrevia(pedidoMasDiez());

            verifyNoInteractions(dolarDePrecios);
        }

        @Test
        @DisplayName("con un USD en el alcance y ninguna cotizacion guardada: 409 claro (nunca IllegalStateException) y no se escribe nada")
        void sinCotizacionDa409() throws Exception {
            alcance(vacio, List.of(enDolares(1, "10.00", "1000")));
            vigente(null);

            assertThatThrownBy(() -> service.vistaPrevia(pedidoMasDiez()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
            verificarSinEscrituras();
        }
    }

    // ---- corte 3: filtro por importacion y modo COSTO_MONTO ----

    private static final Long IMPORTACION_1 = 21L;
    private static final Long IMPORTACION_2 = 22L;

    private static ActualizacionMasivaRequest pedidoDeImportacion(Long importacionId, String modo, String valor) {
        return new ActualizacionMasivaRequest(null, null, importacionId, null, false, modo, bd(valor));
    }

    private static AplicarActualizacionMasivaRequest aplicarDeImportacion(
            Long importacionId, String modo, String valor, String huella) {
        return new AplicarActualizacionMasivaRequest(
                null, null, importacionId, null, false, modo, bd(valor), huella);
    }

    private static FiltroArticulos deImportacion(Long importacionId) {
        return new FiltroArticulos(null, null, null, false, importacionId);
    }

    private void laImportacionExiste(Long id) {
        when(importacionListaRepository.findScoped(id)).thenReturn(Optional.of(mock(ImportacionLista.class)));
    }

    private Articulo articuloEnDolares(long id, String costo, String dolarConElQueSeCalculo) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo(
                        "INT-" + id, null, "PROV-" + id, "Articulo USD " + id, null, PROVEEDOR, bd(costo), null,
                        AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true, Moneda.USD),
                new ContextoPrecio(null, bd("30.00"), bd("10.00"), bd(dolarConElQueSeCalculo)));
        fijar(articulo, "id", id);
        return articulo;
    }

    @Nested
    @DisplayName("filtro por importacion: 404, orden de validacion y huella")
    class PorImportacion {

        @Test
        @DisplayName("importacion inexistente o ajena: 404 en la vista previa, sin leer articulos")
        void vistaPreviaConImportacionAjena() {
            when(importacionListaRepository.findScoped(IMPORTACION_1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(pedidoDeImportacion(IMPORTACION_1, "COSTO_MONTO", "100")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Importación no encontrada");

            verifyNoInteractions(articuloRepository, configuracionPreciosService, historialPrecioRepository);
        }

        @Test
        @DisplayName("importacion inexistente o ajena: 404 al aplicar, sin escribir")
        void aplicarConImportacionAjena() {
            when(importacionListaRepository.findScoped(IMPORTACION_1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aplicar(aplicarDeImportacion(IMPORTACION_1, "COSTO_MONTO", "100", "h"), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Importación no encontrada");

            verifyNoInteractions(articuloRepository, configuracionPreciosService);
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("importacion ajena con valor 0: gana el 404 al 400 del cero")
        void elNotFoundGanaAlCero() {
            when(importacionListaRepository.findScoped(IMPORTACION_1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(pedidoDeImportacion(IMPORTACION_1, "COSTO_MONTO", "0")))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.aplicar(aplicarDeImportacion(IMPORTACION_1, "COSTO_MONTO", "0", "h"), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("el orden es proveedor, rubro, importacion: con rubro propio e importacion ajena, 404 de la importacion")
        void importacionDespuesDelRubro() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(rubro()));
            when(importacionListaRepository.findScoped(IMPORTACION_1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(new ActualizacionMasivaRequest(
                    PROVEEDOR, RUBRO, IMPORTACION_1, null, false, "COSTO_MONTO", bd("100"))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Importación no encontrada");

            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.vistaPrevia(new ActualizacionMasivaRequest(
                    PROVEEDOR, RUBRO, IMPORTACION_1, null, false, "COSTO_MONTO", bd("100"))))
                    .hasMessage("Rubro no encontrado");
        }

        @Test
        @DisplayName("importacion propia con valor 0: 400 del cero, sin leer articulos")
        void ceroConImportacionPropia() {
            laImportacionExiste(IMPORTACION_1);

            assertThatThrownBy(() -> service.vistaPrevia(pedidoDeImportacion(IMPORTACION_1, "COSTO_MONTO", "0")))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                            .containsEntry("montoDistintoDeCero", "El monto no puede ser cero."));

            verifyNoInteractions(articuloRepository);
        }

        @Test
        @DisplayName("sin importacionId no se consulta el repositorio de importaciones")
        void sinImportacionNoConsulta() throws Exception {
            alcance(new FiltroArticulos(null, null, null, false), List.of(articulo(1, "100.00")));

            service.vistaPrevia(pedidoMasDiez());

            verifyNoInteractions(importacionListaRepository);
        }

        @Test
        @DisplayName("la vista previa usa el filtro con la importacion")
        void usaElFiltroConLaImportacion() throws Exception {
            laImportacionExiste(IMPORTACION_1);
            alcance(deImportacion(IMPORTACION_1), List.of(articulo(1, "100.00")));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(pedidoDeImportacion(IMPORTACION_1, "COSTO_PORCENTAJE", "10"));

            assertThat(respuesta.conteos().total()).isEqualTo(1);
            verify(articuloRepository).contarScoped(deImportacion(IMPORTACION_1));
            verify(articuloRepository).filtrarScoped(deImportacion(IMPORTACION_1));
        }

        @Test
        @DisplayName("huella pedida con la importacion 1 y aplicada con la 2: 409 y no se escribe")
        void huellaDeOtraImportacion() throws Exception {
            laImportacionExiste(IMPORTACION_1);
            laImportacionExiste(IMPORTACION_2);
            Articulo articulo = articulo(1, "100.00");
            alcance(deImportacion(IMPORTACION_1), List.of(articulo));
            alcance(deImportacion(IMPORTACION_2), List.of(articulo));
            String huella = huellaDe(pedidoDeImportacion(IMPORTACION_1, "COSTO_PORCENTAJE", "10"));

            assertThatThrownBy(() -> service.aplicar(
                    aplicarDeImportacion(IMPORTACION_2, "COSTO_PORCENTAJE", "10", huella), USUARIO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Los artículos cambiaron desde la vista previa. "
                            + "Volvé a generar la vista previa y confirmá de nuevo.");

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("otra importacion toca un articulo del alcance entre la vista previa y aplicar: 409")
        void unArticuloSaleDelAlcance() throws Exception {
            laImportacionExiste(IMPORTACION_1);
            Articulo uno = articulo(1, "100.00");
            Articulo dos = articulo(2, "100.00");
            alcance(deImportacion(IMPORTACION_1), List.of(uno, dos));
            String huella = huellaDe(pedidoDeImportacion(IMPORTACION_1, "COSTO_PORCENTAJE", "10"));
            alcance(deImportacion(IMPORTACION_1), List.of(uno));

            assertThatThrownBy(() -> service.aplicar(
                    aplicarDeImportacion(IMPORTACION_1, "COSTO_PORCENTAJE", "10", huella), USUARIO))
                    .isInstanceOf(ConflictException.class);

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("alcance vacio de la importacion: aplicar da 400 y no se carga nada")
        void alcanceVacio() {
            laImportacionExiste(IMPORTACION_1);
            when(articuloRepository.contarScoped(deImportacion(IMPORTACION_1))).thenReturn(0L);

            assertThatThrownBy(() -> service.aplicar(
                    aplicarDeImportacion(IMPORTACION_1, "COSTO_MONTO", "100", "h"), USUARIO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No hay artículos para actualizar con ese filtro.");

            verify(articuloRepository, never()).filtrarScoped(any());
        }
    }

    @Nested
    @DisplayName("COSTO_MONTO: costo, precio, monedas y errores")
    class CostoMonto {

        private final FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        private ActualizacionMasivaRequest monto(String valor) {
            return pedido(null, null, null, false, "COSTO_MONTO", valor);
        }

        private AplicarActualizacionMasivaRequest aplicarMonto(String valor, String huella) {
            return aplicarPedido(null, null, null, false, "COSTO_MONTO", valor, huella);
        }

        @Test
        @DisplayName("+100 sobre 1.000,00 da costo 1.100,00 y precio 1.740; la vista previa no escribe")
        void vistaPrevia() throws Exception {
            alcance(vacio, List.of(articulo(1, "1000.00")));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(monto("100"));

            assertThat(respuesta.cambios()).singleElement().satisfies(cambio -> {
                assertThat(cambio.costoAnterior()).isEqualByComparingTo("1000.00");
                assertThat(cambio.costoNuevo()).isEqualByComparingTo("1100.00");
                assertThat(cambio.precioNuevo()).isEqualByComparingTo("1740");
                assertThat(cambio.moneda()).isEqualTo(Moneda.ARS);
            });
            assertThat(respuesta.conteos()).isEqualTo(new ConteosMasivos(1, 1, 0, 0, 0, 0, 0, 1));
            verifyNoInteractions(historialPrecioRepository);
        }

        @Test
        @DisplayName("aplicar usa ajustarCosto con el costo nuevo (nunca fijarMargen) y una entrada MASIVO con el Dueno")
        void aplicarUsaAjustarCosto() throws Exception {
            Articulo articulo = spy(articulo(1, "1000.00"));
            alcance(vacio, List.of(articulo));
            String huella = huellaDe(monto("100"));

            ActualizacionMasivaResponse respuesta = service.aplicar(aplicarMonto("100", huella), USUARIO);

            verify(articulo).ajustarCosto(argThat(c -> c.compareTo(bd("1100.00")) == 0), any());
            verify(articulo, never()).fijarMargen(any(), any());
            assertThat(articulo.getCosto()).isEqualByComparingTo("1100.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("1740");
            assertThat(historialGuardado()).singleElement().satisfies(h -> {
                assertThat(h.getOrigen()).isEqualTo(OrigenPrecio.MASIVO);
                assertThat(h.getUsuarioId()).isEqualTo(USUARIO);
                assertThat(h.getCostoNuevo()).isEqualByComparingTo("1100.00");
            });
            assertThat(respuesta.entradasDeHistorial()).isEqualTo(1);
        }

        @Test
        @DisplayName("33,33 + 0,01: el costo cambia aunque el precio quede igual, y deja historial")
        void soloCambiaElCosto() throws Exception {
            Articulo articulo = articulo(1, "33.33");
            BigDecimal precioAntes = articulo.getPrecioFinal();
            alcance(vacio, List.of(articulo));
            String huella = huellaDe(monto("0.01"));

            service.aplicar(aplicarMonto("0.01", huella), USUARIO);

            assertThat(articulo.getCosto()).isEqualByComparingTo("33.34");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo(precioAntes);
            assertThat(historialGuardado()).hasSize(1);
        }

        @Test
        @DisplayName("un costo que queda en 0 o negativo es error de esa fila: no se escribe y los demas se aplican")
        void costoCeroONegativo() throws Exception {
            Articulo cero = articulo(1, "50.00");
            Articulo negativo = articulo(2, "30.00");
            Articulo bien = articulo(3, "500.00");
            alcance(vacio, List.of(cero, negativo, bien));
            String huella = huellaDe(monto("-50"));

            VistaPreviaMasivaResponse vista = service.vistaPrevia(monto("-50"));
            service.aplicar(aplicarMonto("-50", huella), USUARIO);

            assertThat(vista.errores()).extracting(e -> e.motivo())
                    .containsOnly("El costo nuevo da 0 o menos. Revisá el monto.");
            assertThat(vista.conteos().conError()).isEqualTo(2);
            assertThat(cero.getCosto()).isEqualByComparingTo("50.00");
            assertThat(negativo.getCosto()).isEqualByComparingTo("30.00");
            assertThat(bien.getCosto()).isEqualByComparingTo("450.00");
        }

        @Test
        @DisplayName("la vista previa informa enPesos y enDolares y la advertencia de mezcla con las dos monedas")
        void mezclaDeMonedas() throws Exception {
            alcance(vacio, List.of(articulo(1, "100.00"), articulo(2, "100.00"), articuloEnDolares(3, "10.00", "1000")));
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));

            VistaPreviaMasivaResponse respuesta = service.vistaPrevia(monto("100"));

            assertThat(respuesta.enPesos()).isEqualTo(2);
            assertThat(respuesta.enDolares()).isEqualTo(1);
            assertThat(respuesta.advertenciaMezcla()).startsWith("La selección mezcla artículos en pesos y en dólares.");
            assertThat(respuesta.cambios()).extracting(c -> c.moneda())
                    .containsExactly(Moneda.ARS, Moneda.ARS, Moneda.USD);
        }

        @Test
        @DisplayName("el aviso de mezcla no bloquea: aplicar con la huella escribe y el USD suma el monto en dolares")
        void aplicarConMezcla() throws Exception {
            Articulo pesos = articulo(1, "100.00");
            Articulo dolares = articuloEnDolares(2, "10.00", "1000");
            alcance(vacio, List.of(pesos, dolares));
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
            String huella = huellaDe(monto("5"));

            service.aplicar(aplicarMonto("5", huella), USUARIO);

            assertThat(pesos.getCosto()).isEqualByComparingTo("105.00");
            assertThat(dolares.getCosto()).isEqualByComparingTo("15.00");
            assertThat(dolares.getPrecioFinal()).isEqualByComparingTo("23600");
        }

        @Test
        @DisplayName("solo pesos o solo dolares: sin advertencia; con COSTO_PORCENTAJE y mezcla tampoco")
        void sinMezclaNoAdvierte() throws Exception {
            alcance(vacio, List.of(articuloEnDolares(1, "10.00", "1000"), articuloEnDolares(2, "10.00", "1000")));
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
            assertThat(service.vistaPrevia(monto("5")).advertenciaMezcla()).isNull();

            alcance(vacio, List.of(articulo(1, "100.00"), articuloEnDolares(2, "10.00", "1000")));
            VistaPreviaMasivaResponse porcentaje = service.vistaPrevia(pedidoMasDiez());
            assertThat(porcentaje.advertenciaMezcla()).isNull();
            assertThat(porcentaje.enPesos()).isEqualTo(1);
            assertThat(porcentaje.enDolares()).isEqualTo(1);
        }

        @Test
        @DisplayName("alcance con un USD y sin cotizacion: 409 y no se escribe; con solo pesos no se consulta el dolar")
        void sinCotizacion() throws Exception {
            alcance(vacio, List.of(articuloEnDolares(1, "10.00", "1000")));
            when(dolarDePrecios.vigente()).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(monto("5")))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
            verificarSinEscrituras();

            alcance(vacio, List.of(articulo(1, "100.00")));
            assertThat(service.vistaPrevia(monto("5")).cambios()).hasSize(1);
        }

    }

    @Nested
    @DisplayName("valores que el dominio rechaza: 400 en español y nunca 500")
    class ValorInvalidoDelDominio {

        /** Un validador que no ve nada, para llegar a toOperacion con lo que las anotaciones ya frenan. */
        private ActualizacionMasivaService sinValidador() {
            Validator permisivo = mock(Validator.class);
            when(permisivo.validate(any(), any(Class[].class))).thenReturn(Set.of());
            return new ActualizacionMasivaService(
                    articuloRepository, proveedorRepository, rubroRepository, historialPrecioRepository,
                    configuracionPreciosService, permisivo, dolarDePrecios, importacionListaRepository);
        }

        @Test
        @DisplayName("COSTO_MONTO con 0 que llega al dominio: 400 con el mensaje del monto")
        void montoCero() {
            assertThatThrownBy(() -> sinValidador().vistaPrevia(
                    pedido(null, null, null, false, "COSTO_MONTO", "0")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El monto no puede ser cero.");
            verifyNoInteractions(articuloRepository);
        }

        @Test
        @DisplayName("COSTO_MONTO con 3 decimales que llega al dominio: 400 de decimales")
        void montoConTresDecimales() {
            assertThatThrownBy(() -> sinValidador().vistaPrevia(
                    pedido(null, null, null, false, "COSTO_MONTO", "10.555")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El valor puede tener como máximo 2 decimales.");
        }

        @Test
        @DisplayName("COSTO_PORCENTAJE y MARGEN con 3 decimales que llegan al dominio: 400 de decimales, no ArithmeticException")
        void porcentajeYMargenConTresDecimales() {
            for (String modo : List.of("COSTO_PORCENTAJE", "MARGEN")) {
                assertThatThrownBy(() -> sinValidador().vistaPrevia(pedido(null, null, null, false, modo, "10.555")))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage("El valor puede tener como máximo 2 decimales.");
                assertThatThrownBy(() -> sinValidador().aplicar(
                        aplicarPedido(null, null, null, false, modo, "10.555", "h"), USUARIO))
                        .isInstanceOf(BadRequestException.class);
            }
        }
    }
}
