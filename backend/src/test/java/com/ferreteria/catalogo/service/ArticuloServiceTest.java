package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.ArticuloRequest;
import com.ferreteria.catalogo.dto.ArticuloResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.PageResponse;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ArticuloServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long USUARIO = 9L;

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

    @InjectMocks
    ArticuloService service;

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

    /** Ferreteria con margen por defecto 30 y multiplo 10. */
    private void configuracionPorDefecto() {
        when(configuracionPreciosService.actual()).thenReturn(new ConfiguracionPrecios(bd("30.00"), bd("10.00")));
    }

    private static ArticuloRequest request(Long rubroId, Long proveedorId, String costo, String margen) {
        return new ArticuloRequest("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4",
                rubroId, proveedorId, bd(costo), margen == null ? null : bd(margen),
                AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true);
    }

    private static ArticuloRequest requestMinimo() {
        return new ArticuloRequest("TORN-001", null, null, "Tornillo", null, null, bd("100"), null,
                null, Unidad.UNIDAD, null, null, null);
    }

    private void proveedorExiste(long id) {
        when(proveedorRepository.findScoped(id)).thenReturn(Optional.of(new Proveedor(FERRETERIA, "Bulonera", null)));
    }

    private static Rubro rubro(long id, String margen) throws Exception {
        Rubro rubro = new Rubro(FERRETERIA, "Buloneria", bd(margen));
        fijar(rubro, "id", id);
        return rubro;
    }

    private static void fijar(Object objeto, String campo, Object valor) throws Exception {
        Field field = objeto.getClass().getDeclaredField(campo);
        field.setAccessible(true);
        field.set(objeto, valor);
    }

    /** Articulo ya guardado: costo 100, margen 35, IVA 21, rubro 4, precio 170. */
    private static Articulo existente(long id) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(new DatosArticulo(
                "TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4", 4L, 7L, bd("100.00"),
                bd("35.00"), AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true),
                new ContextoPrecio(bd("35.00"), bd("30.00"), bd("10.00")));
        fijar(articulo, "id", id);
        return articulo;
    }

    /** save() de la base asigna el id (IDENTITY) y devuelve la misma entidad. */
    private void guardarAsignandoId(long id) {
        when(articuloRepository.save(any(Articulo.class))).thenAnswer(inv -> {
            Articulo articulo = inv.getArgument(0);
            fijar(articulo, "id", id);
            return articulo;
        });
    }

    // ---- crear ----

    @Test
    @DisplayName("crear: hereda el margen del rubro del tenant, guarda el articulo y su historial MANUAL con anteriores null")
    void crearConRubroCalculaPrecioYGuardaHistorial() throws Exception {
        configuracionPorDefecto();
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        proveedorExiste(7L);
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(request(4L, 7L, "100", null), USUARIO);

        assertThat(respuesta.id()).isEqualTo(55L);
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("170.00");
        assertThat(respuesta.stockActual()).isEqualByComparingTo("0");
        assertThat(respuesta.activo()).isTrue();

        ArgumentCaptor<Articulo> guardado = ArgumentCaptor.forClass(Articulo.class);
        verify(articuloRepository).save(guardado.capture());
        assertThat(guardado.getValue().getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(guardado.getValue().getRubroId()).isEqualTo(4L);
        assertThat(guardado.getValue().getProveedorId()).isEqualTo(7L);

        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        assertThat(historial.getValue().getArticuloId()).isEqualTo(55L);
        assertThat(historial.getValue().getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(historial.getValue().getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getValue().getUsuarioId()).isEqualTo(USUARIO);
        assertThat(historial.getValue().getCostoAnterior()).isNull();
        assertThat(historial.getValue().getPrecioAnterior()).isNull();
        assertThat(historial.getValue().getCostoNuevo()).isEqualByComparingTo("100");
        assertThat(historial.getValue().getPrecioNuevo()).isEqualByComparingTo("170");
    }

    @Test
    @DisplayName("crear: el margen propio gana sobre el del rubro")
    void crearMargenPropioGanaSobreElRubro() throws Exception {
        configuracionPorDefecto();
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(request(4L, null, "100", "50"), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("190.00");
    }

    @Test
    @DisplayName("crear: sin rubro ni margen usa el margen por defecto de la ferreteria (30 -> 160)")
    void crearSinRubroUsaMargenDeLaFerreteria() {
        configuracionPorDefecto();
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(requestMinimo(), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("160.00");
        verifyNoInteractions(rubroRepository, proveedorRepository);
    }

    @Test
    @DisplayName("crear: IVA, stock minimo y activo nulos toman 21 %, 0 y true; los textos se normalizan")
    void crearAplicaDefectosYNormalizaTextos() {
        configuracionPorDefecto();
        guardarAsignandoId(55L);
        ArticuloRequest sucio = new ArticuloRequest("  TORN-001 ", "   ", " ", "  Tornillo  ", null, null,
                bd("100"), null, null, Unidad.METRO, null, "  ", null);

        service.crear(sucio, USUARIO);

        ArgumentCaptor<Articulo> guardado = ArgumentCaptor.forClass(Articulo.class);
        verify(articuloRepository).save(guardado.capture());
        Articulo articulo = guardado.getValue();
        assertThat(articulo.getCodigoInterno()).isEqualTo("TORN-001");
        assertThat(articulo.getCodigoBarras()).isNull();
        assertThat(articulo.getCodigoProveedor()).isNull();
        assertThat(articulo.getDescripcion()).isEqualTo("Tornillo");
        assertThat(articulo.getUbicacion()).isNull();
        assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(articulo.getStockMinimo()).isEqualByComparingTo("0");
        assertThat(articulo.isActivo()).isTrue();
        assertThat(articulo.getUnidad()).isEqualTo(Unidad.METRO);
        // Sin codigo de barras ni de proveedor no hay nada que validar.
        verify(articuloRepository, never()).existeCodigoBarrasActivoScoped(any(), any());
        verify(articuloRepository, never()).existeCodigoProveedorScoped(any(), any(), any());
    }

    @Test
    @DisplayName("crear: valida los tres codigos contra toda la ferreteria (idPropio null)")
    void crearValidaUnicidadesSinExcluirNada() throws Exception {
        configuracionPorDefecto();
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        proveedorExiste(7L);
        guardarAsignandoId(55L);

        service.crear(request(4L, 7L, "100", null), USUARIO);

        verify(articuloRepository).existeCodigoInternoScoped("TORN-001", null);
        verify(articuloRepository).existeCodigoBarrasActivoScoped("7790001000012", null);
        verify(articuloRepository).existeCodigoProveedorScoped(7L, "AB-12", null);
    }

    @Test
    @DisplayName("crear: codigo interno repetido da 409 y no guarda ni articulo ni historial")
    void crearCodigoInternoDuplicado() {
        when(articuloRepository.existeCodigoInternoScoped("TORN-001", null)).thenReturn(true);

        assertThatThrownBy(() -> service.crear(request(null, null, "100", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un artículo con el código interno TORN-001.");

        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("crear: con varios codigos repetidos el 409 trae todos juntos, cada uno en su campo")
    void crearVariosCodigosDuplicados() {
        when(articuloRepository.existeCodigoInternoScoped("TORN-001", null)).thenReturn(true);
        when(articuloRepository.existeCodigoBarrasActivoScoped("7790001000012", null)).thenReturn(true);

        assertThatThrownBy(() -> service.crear(request(null, null, "100", null), USUARIO))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Ya existe un artículo con el código interno TORN-001. "
                            + "Ya existe un artículo activo con el código de barras 7790001000012.");
                    assertThat(e.errors()).containsOnly(
                            Map.entry("codigoInterno", "Ya existe un artículo con el código interno TORN-001."),
                            Map.entry("codigoBarras",
                                    "Ya existe un artículo activo con el código de barras 7790001000012."));
                });
        verify(articuloRepository, never()).save(any());
    }

    // ---- reactivar ----

    @Test
    @DisplayName("reactivar: vuelve a activar el articulo y revalida el codigo de barras entre los activos")
    void reactivar() throws Exception {
        Articulo articulo = existente(55L);
        articulo.desactivar();
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(articuloRepository.stockVigenteScoped(55L)).thenReturn(Optional.of(bd("12")));

        ArticuloResponse respuesta = service.reactivar(55L);

        assertThat(articulo.isActivo()).isTrue();
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.stockActual()).isEqualByComparingTo("12");
        verify(articuloRepository).existeCodigoBarrasActivoScoped("7790001000012", 55L);
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("reactivar: si otro activo ya usa el codigo de barras da 409 y sigue de baja")
    void reactivarCodigoBarrasOcupado() throws Exception {
        Articulo articulo = existente(55L);
        articulo.desactivar();
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(articuloRepository.existeCodigoBarrasActivoScoped("7790001000012", 55L)).thenReturn(true);

        assertThatThrownBy(() -> service.reactivar(55L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un artículo activo con el código de barras 7790001000012.");
        assertThat(articulo.isActivo()).isFalse();
    }

    @Test
    @DisplayName("reactivar: un articulo ajeno da 404")
    void reactivarAjeno() {
        when(articuloRepository.findScoped(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactivar(77L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");
    }

    @Test
    @DisplayName("crear: codigo de barras repetido entre activos da 409")
    void crearCodigoBarrasDuplicado() {
        when(articuloRepository.existeCodigoBarrasActivoScoped("7790001000012", null)).thenReturn(true);

        assertThatThrownBy(() -> service.crear(request(null, null, "100", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un artículo activo con el código de barras 7790001000012.");

        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("crear: un articulo dado de baja no bloquea ni consulta el codigo de barras")
    void crearInactivoNoValidaCodigoDeBarras() {
        configuracionPorDefecto();
        guardarAsignandoId(55L);
        ArticuloRequest inactivo = new ArticuloRequest("TORN-001", "7790001000012", null, "Tornillo", null, null,
                bd("100"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, null, null, false);

        service.crear(inactivo, USUARIO);

        verify(articuloRepository, never()).existeCodigoBarrasActivoScoped(any(), any());
    }

    @Test
    @DisplayName("crear: codigo de proveedor repetido con el mismo proveedor da 409")
    void crearCodigoProveedorDuplicado() {
        when(articuloRepository.existeCodigoProveedorScoped(7L, "AB-12", null)).thenReturn(true);

        assertThatThrownBy(() -> service.crear(request(null, 7L, "100", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un artículo de este proveedor con el código AB-12.");

        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("crear: codigo de proveedor sin proveedor no se valida")
    void crearCodigoProveedorSinProveedorNoSeValida() {
        configuracionPorDefecto();
        guardarAsignandoId(55L);

        service.crear(request(null, null, "100", null), USUARIO);

        verify(articuloRepository, never()).existeCodigoProveedorScoped(any(), any(), any());
    }

    @Test
    @DisplayName("crear: rubro de otra ferreteria o inexistente da 404 y no guarda nada")
    void crearRubroAjeno() {
        when(rubroRepository.findScoped(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(request(9L, null, "100", null), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Rubro no encontrado");

        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("crear: proveedor de otra ferreteria o inexistente da 404 y no guarda nada")
    void crearProveedorAjeno() {
        when(proveedorRepository.findScoped(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(request(null, 7L, "100", null), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Proveedor no encontrado");

        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);
    }

    // ---- actualizar ----

    @Test
    @DisplayName("actualizar: articulo ajeno o inexistente da 404")
    void actualizarAjeno() {
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(55L, request(null, null, "100", null), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");

        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("actualizar: las unicidades excluyen al propio articulo")
    void actualizarExcluyeAlPropio() throws Exception {
        configuracionPorDefecto();
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        proveedorExiste(7L);

        service.actualizar(55L, request(4L, 7L, "100", null), USUARIO);

        verify(articuloRepository).existeCodigoInternoScoped("TORN-001", 55L);
        verify(articuloRepository).existeCodigoBarrasActivoScoped("7790001000012", 55L);
        verify(articuloRepository).existeCodigoProveedorScoped(7L, "AB-12", 55L);
    }

    @Test
    @DisplayName("actualizar: cambio de costo guarda historial MANUAL con anteriores y nuevos (170 -> 330)")
    void actualizarCambioDeCostoGuardaHistorial() throws Exception {
        configuracionPorDefecto();
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));

        ArticuloResponse respuesta = service.actualizar(55L, request(4L, null, "200", null), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("330.00");
        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        assertThat(historial.getValue().getArticuloId()).isEqualTo(55L);
        assertThat(historial.getValue().getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getValue().getUsuarioId()).isEqualTo(USUARIO);
        assertThat(historial.getValue().getCostoAnterior()).isEqualByComparingTo("100");
        assertThat(historial.getValue().getCostoNuevo()).isEqualByComparingTo("200");
        assertThat(historial.getValue().getPrecioAnterior()).isEqualByComparingTo("170");
        assertThat(historial.getValue().getPrecioNuevo()).isEqualByComparingTo("330");
        // El articulo ya esta administrado por JPA: no se vuelve a guardar.
        verify(articuloRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualizar: cambio de margen que no cambia el precio redondeado no guarda historial")
    void actualizarMismoPrecioNoGuardaHistorial() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));

        // 100 x 1,36 x 1,21 = 164,56 que sube a 170: igual que antes.
        ArticuloResponse respuesta = service.actualizar(55L, request(4L, 7L, "100", "36"), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("170.00");
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("actualizar: solo cambia la descripcion y no hay historial")
    void actualizarSoloDescripcion() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        ArticuloRequest nuevo = new ArticuloRequest("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 5/16",
                4L, 7L, bd("100"), bd("35"), AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true);

        ArticuloResponse respuesta = service.actualizar(55L, nuevo, USUARIO);

        assertThat(respuesta.descripcion()).isEqualTo("Tornillo hexagonal 5/16");
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("actualizar: quitar el rubro recalcula con el margen de la ferreteria")
    void actualizarQuitarRubroRecalcula() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        // El articulo tiene margen propio 35; se lo saca para que herede, ahora de la ferreteria (30).
        ArticuloRequest sinRubro = new ArticuloRequest("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4",
                null, 7L, bd("100"), null, AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", true);

        ArticuloResponse respuesta = service.actualizar(55L, sinRubro, USUARIO);

        assertThat(respuesta.rubroId()).isNull();
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("160.00");
        verifyNoInteractions(rubroRepository);
        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        assertThat(historial.getValue().getPrecioAnterior()).isEqualByComparingTo("170");
        assertThat(historial.getValue().getPrecioNuevo()).isEqualByComparingTo("160");
    }

    @Test
    @DisplayName("actualizar: sin activo en el pedido conserva el estado actual (uno de baja sigue de baja)")
    void actualizarSinActivoConservaElEstadoInactivo() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        articulo.desactivar();
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        ArticuloRequest sinActivo = new ArticuloRequest("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4",
                4L, 7L, bd("100"), bd("35"), AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", null);

        ArticuloResponse respuesta = service.actualizar(55L, sinActivo, USUARIO);

        assertThat(articulo.isActivo()).isFalse();
        assertThat(respuesta.activo()).isFalse();
        // Sigue de baja: no revalida el codigo de barras como si se reactivara.
        verify(articuloRepository, never()).existeCodigoBarrasActivoScoped(any(), any());
    }

    @Test
    @DisplayName("actualizar: sin activo en el pedido conserva el estado actual (uno activo sigue activo)")
    void actualizarSinActivoConservaElEstadoActivo() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        ArticuloRequest sinActivo = new ArticuloRequest("TORN-001", "7790001000012", "AB-12", "Tornillo hexagonal 1/4",
                4L, 7L, bd("100"), bd("35"), AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("5"), "Estante 3", null);

        ArticuloResponse respuesta = service.actualizar(55L, sinActivo, USUARIO);

        assertThat(respuesta.activo()).isTrue();
    }

    @Test
    @DisplayName("actualizar: activo=true explicito reactiva y revalida el codigo de barras")
    void actualizarActivoTrueReactiva() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        articulo.desactivar();
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));

        ArticuloResponse respuesta = service.actualizar(55L, request(4L, 7L, "100", "35"), USUARIO);

        assertThat(respuesta.activo()).isTrue();
        verify(articuloRepository).existeCodigoBarrasActivoScoped("7790001000012", 55L);
    }

    @Test
    @DisplayName("actualizar: el stock actual queda intacto")
    void actualizarNoTocaElStockActual() throws Exception {
        configuracionPorDefecto();
        proveedorExiste(7L);
        Articulo articulo = existente(55L);
        fijar(articulo, "stockActual", bd("12"));
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));

        ArticuloResponse respuesta = service.actualizar(55L, request(4L, 7L, "200", null), USUARIO);

        assertThat(respuesta.stockActual()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("actualizar: reactivar con un codigo de barras ya tomado da 409 y el articulo sigue inactivo")
    void actualizarReactivarRevalidaCodigoDeBarras() throws Exception {
        Articulo articulo = existente(55L);
        articulo.desactivar();
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(articuloRepository.existeCodigoBarrasActivoScoped("7790001000012", 55L)).thenReturn(true);

        assertThatThrownBy(() -> service.actualizar(55L, request(4L, 7L, "100", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un artículo activo con el código de barras 7790001000012.");

        assertThat(articulo.isActivo()).isFalse();
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("actualizar: codigo interno de otro articulo da 409 y no cambia nada")
    void actualizarCodigoInternoDeOtro() throws Exception {
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(articuloRepository.existeCodigoInternoScoped("TORN-001", 55L)).thenReturn(true);

        assertThatThrownBy(() -> service.actualizar(55L, request(4L, 7L, "999", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un artículo con el código interno TORN-001.");

        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("actualizar: proveedor ajeno da 404 y el articulo no cambia")
    void actualizarProveedorAjeno() throws Exception {
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(rubroRepository.findScoped(4L)).thenReturn(Optional.of(rubro(4L, "35.00")));
        when(proveedorRepository.findScoped(70L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(55L, request(4L, 70L, "999", null), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Proveedor no encontrado");

        assertThat(articulo.getProveedorId()).isEqualTo(7L);
        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
        verifyNoInteractions(historialPrecioRepository);
    }

    // ---- desactivar / obtener ----

    @Test
    @DisplayName("desactivar: baja logica sin escribir historial")
    void desactivarNoEscribeHistorial() throws Exception {
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        service.desactivar(55L);

        assertThat(articulo.isActivo()).isFalse();
        verifyNoInteractions(historialPrecioRepository);
        verify(articuloRepository, never()).delete(any());
        verify(articuloRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("desactivar: articulo ajeno da 404")
    void desactivarAjeno() {
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.desactivar(55L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");
    }

    @Test
    @DisplayName("obtener: devuelve tambien un articulo inactivo; uno ajeno da 404")
    void obtener() throws Exception {
        Articulo articulo = existente(55L);
        articulo.desactivar();
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));
        when(articuloRepository.findScoped(56L)).thenReturn(Optional.empty());

        ArticuloResponse respuesta = service.obtener(55L);

        assertThat(respuesta.activo()).isFalse();
        assertThat(respuesta.costo()).isEqualByComparingTo("100");
        assertThat(respuesta.margenPorcentaje()).isEqualByComparingTo("35");
        assertThatThrownBy(() -> service.obtener(56L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- listar ----

    private Pageable listarYCapturarPageable(String q, int page, int size, boolean incluirInactivos) {
        when(articuloRepository.buscarScoped(any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(Page.empty());
        service.listar(q, page, size, incluirInactivos);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(articuloRepository).buscarScoped(any(), anyBoolean(), pageable.capture());
        return pageable.getValue();
    }

    @Test
    @DisplayName("listar: ordena por descripcion y respeta pagina y tamano validos")
    void listarOrdenaPorDescripcion() {
        Pageable pageable = listarYCapturarPageable(null, 1, 20, false);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        // El id desempata: sin el, dos articulos con la misma descripcion pueden cambiar de pagina.
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.asc("descripcion"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("listar: el tamano se acota a 1..100 y la pagina no es negativa")
    void listarAcotaTamanoYPagina() {
        assertThat(listarYCapturarPageable(null, -3, 500, false).getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("listar: un tamano de 0 o negativo se acota a 1")
    void listarTamanoMinimo() {
        Pageable pageable = listarYCapturarPageable(null, -3, 0, false);

        assertThat(pageable.getPageSize()).isEqualTo(1);
        assertThat(pageable.getPageNumber()).isEqualTo(0);
    }

    @Test
    @DisplayName("listar: q en blanco o null no filtra; q se arma como %texto% sin espacios sobrantes")
    void listarArmaElPatronDeBusqueda() {
        when(articuloRepository.buscarScoped(any(), anyBoolean(), any(Pageable.class))).thenReturn(Page.empty());

        service.listar(null, 0, 20, false);
        service.listar("   ", 0, 20, false);
        service.listar(" torni ", 0, 20, true);

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> inactivos = ArgumentCaptor.forClass(Boolean.class);
        verify(articuloRepository, times(3))
                .buscarScoped(q.capture(), inactivos.capture(), any(Pageable.class));
        assertThat(q.getAllValues()).containsExactly(null, null, "%torni%");
        assertThat(inactivos.getAllValues()).containsExactly(false, false, true);
    }

    @Test
    @DisplayName("listar: escapa \\, % y _ para que se busquen como texto literal")
    void listarEscapaLosComodines() {
        when(articuloRepository.buscarScoped(any(), anyBoolean(), any(Pageable.class))).thenReturn(Page.empty());

        service.listar("50%_a\\b", 0, 20, false);

        verify(articuloRepository).buscarScoped(eq("%50\\%\\_a\\\\b%"), eq(false), any(Pageable.class));
    }

    @Test
    @DisplayName("listar: devuelve la pagina con contenido, pagina, tamano y totales")
    void listarDevuelveLaPagina() throws Exception {
        Articulo articulo = existente(55L);
        Page<Articulo> pagina = new PageImpl<>(List.of(articulo), PageRequest.of(1, 20), 21);
        when(articuloRepository.buscarScoped(any(), anyBoolean(), any(Pageable.class))).thenReturn(pagina);

        PageResponse<ArticuloResponse> respuesta = service.listar(null, 1, 20, false);

        assertThat(respuesta.items()).hasSize(1);
        assertThat(respuesta.items().get(0).codigoInterno()).isEqualTo("TORN-001");
        assertThat(respuesta.page()).isEqualTo(1);
        assertThat(respuesta.size()).isEqualTo(20);
        assertThat(respuesta.totalElements()).isEqualTo(21);
        assertThat(respuesta.totalPages()).isEqualTo(2);
    }

    // ---- moneda: articulos en dolares (T-035) ----

    private static final String COTIZACION_APAGADA = "Activá la cotización para cargar artículos en dólares";
    private static final String SIN_COTIZACION = "Todavía no hay cotización del dólar. Probá en unos minutos";

    /** Estado del dolar de la ferreteria: si la cotizacion esta activada y cual es su dolar vigente (null = ninguno). */
    private void dolar(boolean activa, String vigente) {
        lenient().when(dolarDePrecios.activa()).thenReturn(activa);
        lenient().when(dolarDePrecios.vigente())
                .thenReturn(vigente == null ? Optional.empty() : Optional.of(bd(vigente)));
    }

    private static ArticuloRequest requestEn(Moneda moneda, String costo, String margen) {
        return new ArticuloRequest("TORN-001", null, null, "Tornillo", null, null, bd(costo),
                margen == null ? null : bd(margen), AlicuotaIva.IVA_21, Unidad.UNIDAD, null, null, true, moneda);
    }

    /** Articulo ya guardado en dolares: costo 10, margen de la ferreteria (30), IVA 21, calculado con ese dolar. */
    private static Articulo existenteEnDolares(long id, String dolarConElQueSeCalculo) throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        articulo.actualizar(requestEn(Moneda.USD, "10.00", null).toDatos(),
                new ContextoPrecio(null, bd("30.00"), bd("10.00"), bd(dolarConElQueSeCalculo)));
        fijar(articulo, "id", id);
        return articulo;
    }

    @Test
    @DisplayName("crear en pesos con costo 1000 da 1.580 y no consulta el dolar")
    void crearEnPesosNoConsultaElDolar() {
        configuracionPorDefecto();
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(requestEn(Moneda.ARS, "1000", null), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.ARS);
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1580");
        verifyNoInteractions(dolarDePrecios);
    }

    @Test
    @DisplayName("crear sin moneda es en pesos y tampoco consulta el dolar")
    void crearSinMonedaEsEnPesos() {
        configuracionPorDefecto();
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(requestMinimo(), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.ARS);
        verifyNoInteractions(dolarDePrecios);
    }

    @Test
    @DisplayName("crear en USD con costo 10 y vigente 1000: costo 10,00 USD y precio 15.730 en pesos, historial MANUAL en USD")
    void crearEnDolares() {
        configuracionPorDefecto();
        dolar(true, "1000");
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(requestEn(Moneda.USD, "10", null), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.USD);
        assertThat(respuesta.costo()).isEqualByComparingTo("10.00");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("15730");
        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        assertThat(historial.getValue().getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getValue().getMoneda()).isEqualTo(Moneda.USD);
        assertThat(historial.getValue().getUsuarioId()).isEqualTo(USUARIO);
        assertThat(historial.getValue().getPrecioNuevo()).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("crear en USD usa el vigente de la ferreteria, no uno fijo: con 1100 el mismo articulo vale 17.310")
    void crearEnDolaresUsaElVigente() {
        configuracionPorDefecto();
        dolar(true, "1100");
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(requestEn(Moneda.USD, "10", null), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("crear en USD sin ninguna cotizacion da 409 y no guarda nada; el mismo articulo en pesos se guarda normal (R-003)")
    void crearEnDolaresSinCotizacion() {
        configuracionPorDefecto();
        dolar(true, null);
        guardarAsignandoId(55L);

        assertThatThrownBy(() -> service.crear(requestEn(Moneda.USD, "10", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage(SIN_COTIZACION);
        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);

        ArticuloResponse enPesos = service.crear(requestEn(Moneda.ARS, "10", null), USUARIO);
        assertThat(enPesos.moneda()).isEqualTo(Moneda.ARS);
        verify(articuloRepository).save(any(Articulo.class));
    }

    @Test
    @DisplayName("crear en USD con la cotizacion apagada da 409 «Activá la cotización…» y no guarda nada")
    void crearEnDolaresConCotizacionApagada() {
        configuracionPorDefecto();
        dolar(false, "1000");

        assertThatThrownBy(() -> service.crear(requestEn(Moneda.USD, "10", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage(COTIZACION_APAGADA);
        verify(articuloRepository, never()).save(any());
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("crear en pesos con la cotizacion apagada se guarda normal")
    void crearEnPesosConCotizacionApagada() {
        configuracionPorDefecto();
        dolar(false, null);
        guardarAsignandoId(55L);

        ArticuloResponse respuesta = service.crear(requestEn(Moneda.ARS, "1000", null), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1580");
    }

    @Test
    @DisplayName("actualizar de pesos a USD con la cotizacion apagada da 409 y el articulo sigue en pesos")
    void pasarAUsdConCotizacionApagada() throws Exception {
        configuracionPorDefecto();
        dolar(false, "1000");
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        assertThatThrownBy(() -> service.actualizar(55L, requestEn(Moneda.USD, "10", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage(COTIZACION_APAGADA);

        assertThat(articulo.getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(articulo.getCosto()).isEqualByComparingTo("100");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("170");
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("actualizar de pesos a USD toma el costo tal cual (sin convertir) y recalcula con el vigente (R-004)")
    void pasarAUsdTomaElCostoTalCual() throws Exception {
        configuracionPorDefecto();
        dolar(true, "1000");
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(Moneda.USD, "10", null), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.USD);
        assertThat(respuesta.costo()).isEqualByComparingTo("10");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("15730");
        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        assertThat(historial.getValue().getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getValue().getMoneda()).isEqualTo(Moneda.USD);
        assertThat(historial.getValue().getCostoAnterior()).isEqualByComparingTo("100");
        assertThat(historial.getValue().getCostoNuevo()).isEqualByComparingTo("10");
        assertThat(historial.getValue().getPrecioAnterior()).isEqualByComparingTo("170");
        assertThat(historial.getValue().getPrecioNuevo()).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("actualizar de pesos a USD sin ninguna cotizacion da 409 y no cambia nada")
    void pasarAUsdSinCotizacion() throws Exception {
        configuracionPorDefecto();
        dolar(true, null);
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        assertThatThrownBy(() -> service.actualizar(55L, requestEn(Moneda.USD, "10", null), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage(SIN_COTIZACION);

        assertThat(articulo.getMoneda()).isEqualTo(Moneda.ARS);
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("editar uno que YA es USD con la cotizacion apagada se permite y calcula con el vigente congelado: margen 40 % = 16.940 con 1000")
    void editarUnoEnDolaresConCotizacionApagada() throws Exception {
        configuracionPorDefecto();
        // El dolar de la API puede ir por 1100: el servicio solo conoce el vigente de la ferreteria.
        dolar(false, "1000");
        Articulo articulo = existenteEnDolares(55L, "1000");
        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730");
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(Moneda.USD, "10", "40"), USUARIO);

        // 10 x 1000 = 10000; x 1,40 = 14000; x 1,21 = 16940
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("16940");
        assertThat(respuesta.moneda()).isEqualTo(Moneda.USD);
        ArgumentCaptor<HistorialPrecio> historial = ArgumentCaptor.forClass(HistorialPrecio.class);
        verify(historialPrecioRepository).save(historial.capture());
        assertThat(historial.getValue().getPrecioAnterior()).isEqualByComparingTo("15730");
        assertThat(historial.getValue().getPrecioNuevo()).isEqualByComparingTo("16940");
    }

    @Test
    @DisplayName("editar uno en USD con la cotizacion activa tambien usa el vigente (14:00 con vigente 1000: sigue en 15.730)")
    void editarUnoEnDolaresUsaElVigenteNoElDeLaApi() throws Exception {
        configuracionPorDefecto();
        dolar(true, "1000");
        Articulo articulo = existenteEnDolares(55L, "1000");
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(Moneda.USD, "10", null), USUARIO);

        assertThat(respuesta.precioFinal()).isEqualByComparingTo("15730");
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("editar uno en USD cuando no hay ninguna cotizacion ni vigente da 409 y no cambia nada")
    void editarUnoEnDolaresSinCotizacion() throws Exception {
        configuracionPorDefecto();
        dolar(true, null);
        Articulo articulo = existenteEnDolares(55L, "1000");
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        assertThatThrownBy(() -> service.actualizar(55L, requestEn(Moneda.USD, "10", "40"), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage(SIN_COTIZACION);

        assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("pasar de USD a pesos no necesita el dolar (ni siquiera con la cotizacion apagada): el costo queda tal cual")
    void pasarAPesosNoNecesitaElDolar() throws Exception {
        configuracionPorDefecto();
        dolar(false, null);
        Articulo articulo = existenteEnDolares(55L, "1000");
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(Moneda.ARS, "1000", null), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.ARS);
        assertThat(respuesta.costo()).isEqualByComparingTo("1000");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1580");
        verifyNoInteractions(dolarDePrecios);
    }

    @Test
    @DisplayName("un PUT sin moneda sobre un articulo en USD conserva USD: costo 10 y precio 15.730, sin tocar nada")
    void putSinMonedaConservaUsd() throws Exception {
        configuracionPorDefecto();
        dolar(true, "1000");
        Articulo articulo = existenteEnDolares(55L, "1000");
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(null, "10", null), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.USD);
        assertThat(respuesta.costo()).isEqualByComparingTo("10");
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("15730");
        assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
        verifyNoInteractions(historialPrecioRepository);
    }

    @Test
    @DisplayName("un PUT sin moneda sobre un articulo en USD con la cotizacion apagada tambien conserva USD (vigente congelado)")
    void putSinMonedaConservaUsdConCotizacionApagada() throws Exception {
        configuracionPorDefecto();
        dolar(false, "1000");
        Articulo articulo = existenteEnDolares(55L, "1000");
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(null, "10", "40"), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.USD);
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("16940");
    }

    @Test
    @DisplayName("un PUT sin moneda sobre un articulo en pesos sigue en pesos y no consulta el dolar")
    void putSinMonedaSobreArticuloEnPesosSigueEnPesos() throws Exception {
        configuracionPorDefecto();
        Articulo articulo = existente(55L);
        when(articuloRepository.findScoped(55L)).thenReturn(Optional.of(articulo));

        ArticuloResponse respuesta = service.actualizar(55L, requestEn(null, "1000", null), USUARIO);

        assertThat(respuesta.moneda()).isEqualTo(Moneda.ARS);
        assertThat(respuesta.precioFinal()).isEqualByComparingTo("1580");
        verifyNoInteractions(dolarDePrecios);
    }
}
