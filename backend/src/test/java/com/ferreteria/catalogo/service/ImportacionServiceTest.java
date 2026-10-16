package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.AplicarRequest;
import com.ferreteria.catalogo.dto.ConteosRequest;
import com.ferreteria.catalogo.dto.ImportacionResponse;
import com.ferreteria.catalogo.dto.MapeoRequest;
import com.ferreteria.catalogo.dto.MuestraResponse;
import com.ferreteria.catalogo.dto.VistaPreviaRequest;
import com.ferreteria.catalogo.dto.VistaPreviaResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.ArchivoLista;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.EstadoImportacion;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.catalogo.service.lectura.LectorDeListas;
import com.ferreteria.catalogo.service.lectura.LimitesDeLista;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportacionServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long PROVEEDOR = 7L;
    private static final Long RUBRO = 4L;
    private static final Long USUARIO = 9L;

    /** Los bytes no se leen: el lector esta simulado. */
    private static final ArchivoLista ARCHIVO = new ArchivoLista("lista.xlsx", new byte[] {1, 2, 3});

    @Mock
    LectorDeListas lector;

    @Mock
    ProveedorRepository proveedorRepository;

    @Mock
    RubroRepository rubroRepository;

    @Mock
    ArticuloRepository articuloRepository;

    @Mock
    HistorialPrecioRepository historialPrecioRepository;

    @Mock
    ImportacionListaRepository importacionListaRepository;

    @Mock
    ConfiguracionPreciosService configuracionPreciosService;

    @Mock
    DolarDePrecios dolarDePrecios;

    private static ValidatorFactory fabricaDeValidadores;

    ImportacionService service;

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
        service = new ImportacionService(
                lector, proveedorRepository, rubroRepository, articuloRepository, historialPrecioRepository,
                importacionListaRepository, configuracionPreciosService, dolarDePrecios,
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

    private static Rubro rubro(String margen) throws Exception {
        Rubro rubro = new Rubro(FERRETERIA, "Buloneria", bd(margen));
        fijar(rubro, "id", RUBRO);
        return rubro;
    }

    private static Celda celda(String texto) {
        return new Celda(texto, null);
    }

    /** Codigo en la columna 0, descripcion en la 1 y costo en la 2. */
    private static FilaLeida fila(int numero, String codigo, String descripcion, String costo) {
        return new FilaLeida(numero, List.of(celda(codigo), celda(descripcion), celda(costo)));
    }

    private static FilaLeida encabezado() {
        return fila(1, "Codigo", "Descripcion", "Costo");
    }

    private static LecturaLista lectura(FilaLeida... filas) {
        return new LecturaLista(List.of("Hoja1"), List.of(filas));
    }

    /**
     * `previas` filas antes de los datos (titulos y encabezado, con una sola celda) y despues `datos`
     * filas validas, cada una con su codigo, descripcion y costo.
     */
    private static LecturaLista filasDeDatos(int previas, int datos) {
        List<FilaLeida> filas = new ArrayList<>();
        for (int n = 1; n <= previas; n++) {
            filas.add(new FilaLeida(n, List.of(celda("Titulo " + n))));
        }
        for (int n = previas + 1; n <= previas + datos; n++) {
            filas.add(fila(n, "C-" + n, "Articulo " + n, "10"));
        }
        return new LecturaLista(List.of("Hoja1"), filas);
    }

    /** Mapeo base: hoja 0, datos desde la fila 2, codigo 0, descripcion 1, costo 2. */
    private static MapeoRequest mapeo() {
        return new MapeoRequest(0, 2, 0, 1, 2);
    }

    private static VistaPreviaRequest vistaPreviaRequest() {
        return new VistaPreviaRequest(PROVEEDOR, mapeo(), false, null, null, null);
    }

    /** Articulo del proveedor 7 con costo `costo`, sin margen propio, IVA_21 y precio calculado con su contexto. */
    private static Articulo existente(long id, String codigoProveedor, String costo, Long rubroId, boolean activo)
            throws Exception {
        Articulo articulo = new Articulo(FERRETERIA);
        BigDecimal margenRubro = rubroId == null ? null : bd("35.00");
        articulo.actualizar(new DatosArticulo(
                        "INT-" + id, null, codigoProveedor, "Articulo " + id, rubroId, PROVEEDOR, bd(costo),
                        null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, activo),
                new ContextoPrecio(margenRubro, bd("30.00"), bd("10.00")));
        fijar(articulo, "id", id);
        return articulo;
    }

    /** Ferreteria con margen por defecto 30 y multiplo 10. */
    private void configuracionPorDefecto() {
        when(configuracionPreciosService.actual()).thenReturn(new ConfiguracionPrecios(bd("30.00"), bd("10.00")));
    }

    /** Todo lo que lee preparar: proveedor, rubros, configuracion, articulos del proveedor y el archivo. */
    private void escenario(Proveedor proveedor, List<Articulo> articulos, LecturaLista lectura, Rubro... rubros) {
        when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor));
        when(lector.leer(any(byte[].class), eq(0))).thenReturn(lectura);
        when(articuloRepository.deProveedorScoped(PROVEEDOR)).thenReturn(articulos);
        when(rubroRepository.listarScoped()).thenReturn(List.of(rubros));
        configuracionPorDefecto();
    }

    private void verificarSinEscrituras() {
        verify(articuloRepository, never()).save(any());
        verify(articuloRepository, never()).saveAll(any());
        verify(historialPrecioRepository, never()).save(any());
        verify(historialPrecioRepository, never()).saveAll(any());
        verify(importacionListaRepository, never()).save(any());
        verify(proveedorRepository, never()).save(any());
    }

    private static ConteosRequest conteos(int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError) {
        return new ConteosRequest(suben, bajan, nuevos, sinCambio, noEstan, conError);
    }

    private static AplicarRequest aplicarRequest(ConteosRequest esperados) {
        return new AplicarRequest(PROVEEDOR, mapeo(), false, null, null, null, false, null, esperados);
    }

    /** save() de la base devuelve la misma entidad. */
    private void guardaImportacion() {
        when(importacionListaRepository.save(any(ImportacionLista.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<HistorialPrecio> historialGuardado() {
        ArgumentCaptor<Iterable<HistorialPrecio>> captor = ArgumentCaptor.captor();
        verify(historialPrecioRepository, times(1)).saveAll(captor.capture());
        List<HistorialPrecio> historial = new ArrayList<>();
        captor.getValue().forEach(historial::add);
        return historial;
    }

    // ---- muestra ----

    @Nested
    @DisplayName("muestra")
    class Muestra {

        @Test
        @DisplayName("devuelve las hojas y TODAS las filas con su numero original, sin tocar repositorios")
        void todasLasFilas() {
            List<FilaLeida> filas = new ArrayList<>();
            for (int n = 1; n <= 20_000; n++) {
                filas.add(fila(n * 2, "C" + n, "Desc " + n, "10"));
            }
            when(lector.leer(any(byte[].class), eq(1)))
                    .thenReturn(new LecturaLista(List.of("Lista", "Precios"), filas));

            MuestraResponse respuesta = service.muestra(ARCHIVO, 1);

            assertThat(respuesta.hojas()).containsExactly("Lista", "Precios");
            assertThat(respuesta.hoja()).isEqualTo(1);
            assertThat(respuesta.filas()).hasSize(20_000);
            assertThat(respuesta.filas().get(0).numero()).isEqualTo(2);
            assertThat(respuesta.filas().get(0).celdas()).containsExactly("C1", "Desc 1", "10");
            assertThat(respuesta.filas().get(14).numero()).isEqualTo(30);
            assertThat(respuesta.filas().get(19_999).numero()).isEqualTo(40_000);
            assertThat(respuesta.columnas()).isEqualTo(3);
            verifyNoInteractions(proveedorRepository, rubroRepository, articuloRepository,
                    historialPrecioRepository, importacionListaRepository, configuracionPreciosService);
        }

        @Test
        @DisplayName("devuelve todas las celdas de cada fila, mas alla de la 30, y informa el ancho de la grilla")
        void todasLasCeldas() {
            List<Celda> ancha = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                ancha.add(celda("c" + i));
            }
            when(lector.leer(any(byte[].class), eq(0)))
                    .thenReturn(lectura(new FilaLeida(1, ancha), fila(2, "A", "B", "C")));

            MuestraResponse respuesta = service.muestra(ARCHIVO, 0);

            assertThat(respuesta.filas().get(0).celdas()).hasSize(35);
            assertThat(respuesta.filas().get(0).celdas().get(34)).isEqualTo("c34");
            assertThat(respuesta.columnas()).isEqualTo(35);
        }

        @Test
        @DisplayName("tope de seguridad: corta cada fila en el maximo de columnas (200)")
        void topeDeColumnas() {
            assertThat(LimitesDeLista.MAXIMO_COLUMNAS_DE_MUESTRA).isEqualTo(200);
            List<Celda> patologica = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                patologica.add(celda("c" + i));
            }
            when(lector.leer(any(byte[].class), eq(0)))
                    .thenReturn(lectura(new FilaLeida(1, patologica), fila(2, "A", "B", "C")));

            MuestraResponse respuesta = service.muestra(ARCHIVO, 0);

            assertThat(respuesta.filas().get(0).celdas()).hasSize(200);
            assertThat(respuesta.filas().get(0).celdas().get(199)).isEqualTo("c199");
            assertThat(respuesta.columnas()).isEqualTo(200);
        }

        @Test
        @DisplayName("un archivo con pocas filas las devuelve todas")
        void pocasFilas() {
            when(lector.leer(any(byte[].class), eq(0))).thenReturn(lectura(encabezado(), fila(2, "A", "B", "1")));

            assertThat(service.muestra(ARCHIVO, 0).filas()).hasSize(2);
        }

        @Test
        @DisplayName("un error del lector (hoja inexistente, archivo invalido) llega tal cual")
        void errorDelLector() {
            when(lector.leer(any(byte[].class), eq(5)))
                    .thenThrow(new BadRequestException("La hoja elegida no existe en el archivo."));

            assertThatThrownBy(() -> service.muestra(ARCHIVO, 5))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La hoja elegida no existe en el archivo.");
        }
    }

    // ---- vistaPrevia ----

    @Nested
    @DisplayName("vistaPrevia")
    class VistaPrevia {

        @Test
        @DisplayName("proveedor ajeno o inexistente: 404 y no lee el archivo")
        void proveedorAjeno() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, vistaPreviaRequest()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");
            verifyNoInteractions(lector, articuloRepository);
        }

        @Test
        @DisplayName("proveedor ajeno con bonificaciones invalidas: 404 (la validacion va despues)")
        void proveedorAjenoConBonificacionesInvalidas() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, bd("150"), null, null)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");
            verifyNoInteractions(lector, articuloRepository);
        }

        @Test
        @DisplayName("bonificaciones invalidas con el proveedor del tenant: errores por campo y no lee el archivo")
        void bonificacionesInvalidas() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, bd("150"), null, bd("10.555"))))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                            Map.entry("bonificacion1", "La bonificación 1 debe estar entre 0 y 100."),
                            Map.entry("bonificacion3", "La bonificación 3 puede tener como máximo 2 decimales.")));
            verifyNoInteractions(lector, articuloRepository);
        }

        @Test
        @DisplayName("una bonificacion con rango y decimales a la vez informa el rango")
        void bonificacionConDosViolaciones() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, bd("100.555"), null, null)))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                            .containsOnly(Map.entry("bonificacion1", "La bonificación 1 debe estar entre 0 y 100.")));
        }

        @Test
        @DisplayName("bonificaciones en el limite (0 y 100 con 2 decimales) no son error")
        void bonificacionesEnElLimite() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Uno", "10")));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, bd("0"), bd("100.00"), bd("99.99")));

            assertThat(respuesta.conteos().conError()).isEqualTo(1);
        }

        @Test
        @DisplayName("clasifica las filas, calcula el precio con el contexto del rubro y cuenta lo que no esta")
        void contadoresYPrecios() throws Exception {
            Articulo conRubro = existente(1L, "AB-1", "100.00", RUBRO, true);
            Articulo sinRubro = existente(2L, "AB-2", "100.00", null, true);
            Articulo ausente = existente(3L, "AB-3", "50.00", null, true);
            escenario(proveedor(), List.of(conRubro, sinRubro, ausente), lectura(
                            encabezado(),
                            fila(2, "AB-1", "x", "120"),
                            fila(3, "AB-2", "x", "80"),
                            fila(4, "NUEVO-1", "Tuerca", "10"),
                            fila(5, "", "x", "abc")),
                    rubro("35.00"));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.conteos().suben()).isEqualTo(1);
            assertThat(respuesta.conteos().bajan()).isEqualTo(1);
            assertThat(respuesta.conteos().nuevos()).isEqualTo(1);
            assertThat(respuesta.conteos().sinCambio()).isZero();
            assertThat(respuesta.conteos().noEstan()).isEqualTo(1);
            assertThat(respuesta.conteos().conError()).isEqualTo(1);
            // 120 x 1,35 x 1,21 = 196,02 -> 200 (rubro); 80 x 1,30 x 1,21 = 125,84 -> 130 (margen por defecto)
            assertThat(respuesta.cambios()).hasSize(2);
            assertThat(respuesta.cambios().get(0).precioNuevo()).isEqualByComparingTo("200");
            assertThat(respuesta.cambios().get(1).precioNuevo()).isEqualByComparingTo("130");
            assertThat(respuesta.nuevos()).hasSize(1);
            assertThat(respuesta.nuevos().get(0).codigo()).isEqualTo("NUEVO-1");
            assertThat(respuesta.errores()).hasSize(1);
            assertThat(respuesta.errores().get(0).mensaje()).isEqualTo("Falta el código del proveedor.");
            assertThat(respuesta.noEstan()).hasSize(1);
            assertThat(respuesta.noEstan().get(0).id()).isEqualTo(3L);
            assertThat(respuesta.noEstan().get(0).codigoInterno()).isEqualTo("INT-3");
            assertThat(respuesta.noEstan().get(0).codigoProveedor()).isEqualTo("AB-3");
            assertThat(respuesta.noEstan().get(0).descripcion()).isEqualTo("Articulo 3");
            assertThat(respuesta.detalleRecortado()).isFalse();
        }

        @Test
        @DisplayName("lee todo una sola vez: articulos, rubros, configuracion y codigos internos ocupados")
        void unaConsultaPorDato() throws Exception {
            escenario(proveedor(), List.of(existente(1L, "AB-1", "100.00", null, true)), lectura(
                    encabezado(),
                    fila(2, "AB-1", "x", "120"),
                    fila(3, "N-1", "Nuevo uno", "10"),
                    fila(4, "N-2", "Nuevo dos", "10")));

            service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            verify(articuloRepository, times(1)).deProveedorScoped(PROVEEDOR);
            verify(rubroRepository, times(1)).listarScoped();
            verify(configuracionPreciosService, times(1)).actual();
            verify(articuloRepository, times(1)).codigosInternosOcupadosScoped(anyCollection());
            verify(proveedorRepository, times(1)).findScoped(PROVEEDOR);
        }

        @Test
        @DisplayName("pregunta por los codigos internos candidatos, no por cada fila")
        void codigosCandidatos() throws Exception {
            escenario(proveedor(), List.of(), lectura(
                    encabezado(), fila(2, "N-1", "Uno", "10"), fila(3, "n-1", "Repetido", "10"), fila(4, "N-2", "Dos", "10")));

            service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            verify(articuloRepository).codigosInternosOcupadosScoped(List.of("N-1", "N-2"));
        }

        @Test
        @DisplayName("normaliza con la clave los codigos ocupados que devuelve la base (con acentos y mayusculas)")
        void ocupadosNormalizados() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "TEE-10", "Te", "10")));
            when(articuloRepository.codigosInternosOcupadosScoped(anyCollection())).thenReturn(List.of("tÉe-10"));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.conteos().nuevos()).isZero();
            assertThat(respuesta.conteos().conError()).isEqualTo(1);
            assertThat(respuesta.errores().get(0).mensaje())
                    .isEqualTo("No se puede crear como artículo nuevo: ya existe un artículo con el código interno TEE-10.");
        }

        @Test
        @DisplayName("usa las bonificaciones de la SOLICITUD, no las guardadas en el proveedor")
        void bonificacionesDeLaSolicitud() throws Exception {
            Proveedor proveedor = proveedor();
            proveedor.configurarLista(null, false, new Bonificaciones(bd("40"), null, null));
            escenario(proveedor, List.of(existente(1L, "AB-1", "100.00", null, true)), lectura(
                    encabezado(), fila(2, "AB-1", "x", "1000")));

            VistaPreviaRequest sinBonificaciones = vistaPreviaRequest();
            VistaPreviaResponse sin = service.vistaPrevia(ARCHIVO, sinBonificaciones);
            VistaPreviaResponse con = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, bd("40"), bd("10"), null));

            assertThat(sin.cambios().get(0).costoNuevo()).isEqualByComparingTo("1000.00");
            assertThat(con.cambios().get(0).costoNuevo()).isEqualByComparingTo("540.00");
            assertThat(con.cambios().get(0).precioLista()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("con la lista con IVA quita el de cada articulo antes del costo")
        void listaConIva() throws Exception {
            escenario(proveedor(), List.of(existente(1L, "AB-1", "50.00", null, true)), lectura(
                    encabezado(), fila(2, "AB-1", "x", "121")));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), true, null, null, null));

            assertThat(respuesta.cambios().get(0).costoNuevo()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("el encabezado del archivo se compara con el mapeo guardado del proveedor")
        void advertenciaDeEncabezado() throws Exception {
            Proveedor proveedor = proveedor();
            proveedor.configurarLista(
                    new MapeoLista(0, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Descripcion", "Precio")),
                    false, Bonificaciones.NINGUNA);
            escenario(proveedor, List.of(), lectura(encabezado(), fila(2, "N-1", "Uno", "10")));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.advertencias()).extracting(a -> a.mensaje())
                    .containsExactly("El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «Costo». Revisá el mapeo.");
        }

        @Test
        @DisplayName("no escribe nada: ni articulos, ni historial, ni registro, ni el proveedor")
        void sinEfectos() throws Exception {
            Proveedor proveedor = proveedor();
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            escenario(proveedor, List.of(articulo), lectura(
                    encabezado(), fila(2, "AB-1", "x", "1000"), fila(3, "N-1", "Nuevo", "10")));

            service.vistaPrevia(ARCHIVO, new VistaPreviaRequest(PROVEEDOR, mapeo(), true, bd("40"), null, null));

            verificarSinEscrituras();
            assertThat(proveedor.getMapeoLista()).isNull();
            assertThat(proveedor.isListaIncluyeIva()).isFalse();
            assertThat(proveedor.getBonificacion1()).isNull();
            assertThat(articulo.getCosto()).isEqualByComparingTo("100.00");
            assertThat(articulo.getPrecioListaProveedor()).isNull();
        }

        @Test
        @DisplayName("sin filas de datos desde la primera fila elegida: 400 con el numero de fila")
        void sinFilasDeDatos() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class), eq(0))).thenReturn(lectura(encabezado()));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, vistaPreviaRequest()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No hay filas de datos desde la fila 2.");
            verify(articuloRepository, never()).deProveedorScoped(any());
        }

        @Test
        @DisplayName("un archivo sin ninguna fila tambien da 400")
        void archivoSinFilas() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class), eq(0))).thenReturn(lectura());

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, vistaPreviaRequest()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No hay filas de datos desde la fila 2.");
        }

        @Test
        @DisplayName("sin encabezado, 20.001 filas de datos: 400 con el mensaje del limite (R-025)")
        void veinteMilUnaFilasDeDatosSinEncabezado() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class), eq(0))).thenReturn(filasDeDatos(0, 20_001));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, new MapeoRequest(0, 1, 0, 1, 2), false, null, null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La lista tiene más de 20.000 filas. Dividila en archivos más chicos.");
            verify(articuloRepository, never()).deProveedorScoped(any());
        }

        @Test
        @DisplayName("tres titulos, el encabezado y justo 20.000 filas de datos: se procesa (cuentan solo las filas desde la primera de datos)")
        void titulosEncabezadoYVeinteMilFilasDeDatos() throws Exception {
            escenario(proveedor(), List.of(), filasDeDatos(4, 20_000));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, new MapeoRequest(0, 5, 0, 1, 2), false, null, null, null));

            assertThat(respuesta.conteos().nuevos()).isEqualTo(20_000);
            assertThat(respuesta.conteos().conError()).isZero();
        }

        @Test
        @DisplayName("el titulo y el encabezado no cuentan: 20.001 datos con encabezado tambien dan 400")
        void veinteMilUnaFilasConEncabezado() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class), eq(0))).thenReturn(filasDeDatos(4, 20_001));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, new MapeoRequest(0, 5, 0, 1, 2), false, null, null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La lista tiene más de 20.000 filas. Dividila en archivos más chicos.");
        }

        @Test
        @DisplayName("recorta cada lista a 1.000 filas y avisa, pero los contadores son los reales")
        void recortaElDetalle() throws Exception {
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(encabezado());
            for (int n = 2; n <= 1101; n++) {
                filas.add(fila(n, "C" + n, "x", "abc"));
            }
            escenario(proveedor(), List.of(), new LecturaLista(List.of("Hoja1"), filas));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.conteos().conError()).isEqualTo(1100);
            assertThat(respuesta.errores()).hasSize(1000);
            assertThat(respuesta.detalleRecortado()).isTrue();
        }

        @Test
        @DisplayName("justo 1.000 errores no se recortan")
        void milExactos() throws Exception {
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(encabezado());
            for (int n = 2; n <= 1001; n++) {
                filas.add(fila(n, "C" + n, "x", "abc"));
            }
            escenario(proveedor(), List.of(), new LecturaLista(List.of("Hoja1"), filas));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.errores()).hasSize(1000);
            assertThat(respuesta.detalleRecortado()).isFalse();
        }
    }

    // ---- aplicar: articulos existentes y deriva ----

    @Nested
    @DisplayName("aplicar: existentes y verificacion de deriva")
    class AplicarExistentes {

        private static final String MENSAJE_DERIVA =
                "La lista cambió desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.";

        @Test
        @DisplayName("contadores distintos a los de la vista previa: 409 y no se escribe nada")
        void derivaConteos() throws Exception {
            Proveedor proveedor = proveedor();
            Articulo articulo = existente(1L, "AB-1", "100.00", RUBRO, true);
            escenario(proveedor, List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "120")), rubro("35.00"));

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 0, 1, 0, 0)), USUARIO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(MENSAJE_DERIVA);

            verificarSinEscrituras();
            assertThat(articulo.getCosto()).isEqualByComparingTo("100.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("170.00");
            assertThat(articulo.getPrecioListaProveedor()).isNull();
            assertThat(proveedor.getMapeoLista()).isNull();
        }

        @Test
        @DisplayName("cada uno de los seis contadores cuenta para la deriva")
        void derivaPorCadaContador() throws Exception {
            // suben=1 y noEstan=1: el articulo AB-2 no esta en la lista
            Articulo sube = existente(1L, "AB-1", "100.00", null, true);
            Articulo ausente = existente(2L, "AB-2", "100.00", null, true);
            escenario(proveedor(), List.of(sube, ausente), lectura(encabezado(), fila(2, "AB-1", "x", "120")));

            List<ConteosRequest> distintos = List.of(
                    conteos(0, 0, 0, 0, 1, 0), conteos(1, 1, 0, 0, 1, 0), conteos(1, 0, 1, 0, 1, 0),
                    conteos(1, 0, 0, 1, 1, 0), conteos(1, 0, 0, 0, 0, 0), conteos(1, 0, 0, 0, 1, 1));
            for (ConteosRequest esperados : distintos) {
                assertThatThrownBy(() -> service.aplicar(ARCHIVO, aplicarRequest(esperados), USUARIO))
                        .isInstanceOf(ConflictException.class)
                        .hasMessage(MENSAJE_DERIVA);
            }
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("bonificaciones distintas a las de la vista previa: 409 y el proveedor no guarda nada")
        void derivaPorBonificaciones() throws Exception {
            Proveedor proveedor = proveedor();
            Articulo articulo = existente(1L, "AB-1", "400.00", null, true);
            escenario(proveedor, List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "1000")));
            // La vista previa sin bonificaciones dio suben=1; con 60 % el costo es 400: sin cambio.
            AplicarRequest conSesenta = new AplicarRequest(
                    PROVEEDOR, mapeo(), false, bd("60"), null, null, false, null, conteos(1, 0, 0, 0, 0, 0));

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, conSesenta, USUARIO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(MENSAJE_DERIVA);

            verificarSinEscrituras();
            assertThat(proveedor.getBonificacion1()).isNull();
            assertThat(proveedor.getMapeoLista()).isNull();
            assertThat(articulo.getPrecioListaProveedor()).isNull();
        }

        @Test
        @DisplayName("con los mismos contadores aplica y devuelve la importacion")
        void conteosIgualesAplican() throws Exception {
            escenario(proveedor(), List.of(existente(1L, "AB-1", "100.00", RUBRO, true)),
                    lectura(encabezado(), fila(2, "AB-1", "x", "120")), rubro("35.00"));
            guardaImportacion();

            ImportacionResponse respuesta =
                    service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(respuesta.conteos().suben()).isEqualTo(1);
            assertThat(respuesta.proveedorId()).isEqualTo(PROVEEDOR);
        }

        @Test
        @DisplayName("sube: cambia costo y precio de lista de la entidad gestionada, recalcula el precio y deja UNA entrada IMPORTACION")
        void suben() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", RUBRO, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "120")), rubro("35.00"));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(articulo.getCosto()).isEqualByComparingTo("120.00");
            // 120 x 1,35 x 1,21 = 196,02 -> 200
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("200.00");
            assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("120.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(1);
            HistorialPrecio entrada = historial.get(0);
            assertThat(entrada.getOrigen()).isEqualTo(OrigenPrecio.IMPORTACION);
            assertThat(entrada.getArticuloId()).isEqualTo(1L);
            assertThat(entrada.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(entrada.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(entrada.getCostoAnterior()).isEqualByComparingTo("100.00");
            assertThat(entrada.getCostoNuevo()).isEqualByComparingTo("120.00");
            assertThat(entrada.getPrecioAnterior()).isEqualByComparingTo("170.00");
            assertThat(entrada.getPrecioNuevo()).isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("baja: costo 100 a 80 sin rubro, precio 160 a 130")
        void bajan() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "80")));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 1, 0, 0, 0, 0)), USUARIO);

            assertThat(articulo.getCosto()).isEqualByComparingTo("80.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("130.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(1);
            assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("160.00");
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("130.00");
        }

        @Test
        @DisplayName("varios cambios: un solo saveAll del historial con una entrada por articulo")
        void unSoloSaveAll() throws Exception {
            Articulo uno = existente(1L, "AB-1", "100.00", null, true);
            Articulo dos = existente(2L, "AB-2", "100.00", null, true);
            Articulo tres = existente(3L, "AB-3", "100.00", null, true);
            escenario(proveedor(), List.of(uno, dos, tres), lectura(
                    encabezado(), fila(2, "AB-1", "x", "120"), fila(3, "AB-2", "x", "80"), fila(4, "AB-3", "x", "130")));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(2, 1, 0, 0, 0, 0)), USUARIO);

            assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactlyInAnyOrder(1L, 2L, 3L);
            verify(historialPrecioRepository, never()).save(any());
        }

        @Test
        @DisplayName("sin cambio de costo: solo se fija el precio de lista, sin historial")
        void sinCambioSoloPrecioDeLista() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "540.00", null, true);
            BigDecimal precioAntes = articulo.getPrecioFinal();
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "1000")));
            guardaImportacion();
            AplicarRequest conBonificaciones = new AplicarRequest(
                    PROVEEDOR, mapeo(), false, bd("40"), bd("10"), null, false, null, conteos(0, 0, 0, 1, 0, 0));

            service.aplicar(ARCHIVO, conBonificaciones, USUARIO);

            assertThat(articulo.getCosto()).isEqualByComparingTo("540.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo(precioAntes);
            assertThat(articulo.getPrecioListaProveedor()).isEqualByComparingTo("1000.00");
            verify(historialPrecioRepository, never()).saveAll(any());
            verify(historialPrecioRepository, never()).save(any());
        }

        @Test
        @DisplayName("costo 100 a 100,50 con el precio igual: el historial se escribe igual")
        void costoCambiaPrecioIgual() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", RUBRO, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "100,50")), rubro("35.00"));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(articulo.getCosto()).isEqualByComparingTo("100.50");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("170.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(1);
            assertThat(historial.get(0).getCostoAnterior()).isEqualByComparingTo("100.00");
            assertThat(historial.get(0).getCostoNuevo()).isEqualByComparingTo("100.50");
            assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("170.00");
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("170.00");
        }

        @Test
        @DisplayName("fila duplicada, con error o ausente de la lista: el articulo no se toca y no hay historial")
        void sinHistorialParaLoQueNoCambia() throws Exception {
            Articulo repetido = existente(1L, "AB-1", "100.00", null, true);
            Articulo conError = existente(2L, "AB-2", "100.00", null, true);
            Articulo ausente = existente(3L, "AB-3", "100.00", null, true);
            escenario(proveedor(), List.of(repetido, conError, ausente), lectura(
                    encabezado(), fila(2, "AB-1", "x", "120"), fila(3, "ab-1", "x", "130"), fila(4, "AB-2", "x", "abc")));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 0, 0, 1, 3)), USUARIO);

            for (Articulo articulo : List.of(repetido, conError, ausente)) {
                assertThat(articulo.getCosto()).isEqualByComparingTo("100.00");
                assertThat(articulo.getPrecioListaProveedor()).isNull();
            }
            verify(historialPrecioRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("un articulo inactivo coincidente se actualiza y sigue inactivo")
        void inactivoSigueInactivo() throws Exception {
            Articulo inactivo = existente(1L, "AB-1", "100.00", null, false);
            escenario(proveedor(), List.of(inactivo), lectura(encabezado(), fila(2, "AB-1", "x", "120")));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(inactivo.getCosto()).isEqualByComparingTo("120.00");
            assertThat(inactivo.isActivo()).isFalse();
            assertThat(historialGuardado()).hasSize(1);
        }

        @Test
        @DisplayName("lo que no es costo ni precio de lista queda intacto")
        void camposIntactos() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", RUBRO, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "Otra descripcion", "120")),
                    rubro("35.00"));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(articulo.getDescripcion()).isEqualTo("Articulo 1");
            assertThat(articulo.getRubroId()).isEqualTo(RUBRO);
            assertThat(articulo.getMargenPorcentaje()).isNull();
            assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(articulo.getCodigoProveedor()).isEqualTo("AB-1");
        }

        @Test
        @DisplayName("proveedor ajeno: 404 antes de leer el archivo")
        void proveedorAjeno() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 0, 0, 0, 0)), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");
            verifyNoInteractions(lector);
            verificarSinEscrituras();
        }

        private AplicarRequest conBonificaciones(Long rubroId, boolean crearNuevos, String b1, String b2, String b3) {
            return new AplicarRequest(
                    PROVEEDOR, mapeo(), false, bd(b1), bd(b2), bd(b3), crearNuevos, rubroId, conteos(0, 0, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("proveedor ajeno con bonificaciones invalidas: 404 (la validacion va despues)")
        void proveedorAjenoConBonificacionesInvalidas() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, conBonificaciones(null, false, "150", null, null), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");
            verifyNoInteractions(lector);
        }

        @Test
        @DisplayName("rubro ajeno con bonificaciones invalidas: 404 del rubro")
        void rubroAjenoConBonificacionesInvalidas() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(rubroRepository.findScoped(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, conBonificaciones(99L, true, "150", null, null), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Rubro no encontrado");
        }

        @Test
        @DisplayName("bonificaciones invalidas con el proveedor del tenant: errores por campo, sin leer ni escribir nada")
        void bonificacionesInvalidas() throws Exception {
            Proveedor proveedor = proveedor();
            proveedor.configurarLista(null, false, new Bonificaciones(bd("40"), null, null));
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor));

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, conBonificaciones(null, false, "150", "-1", "10.555"), USUARIO))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                            Map.entry("bonificacion1", "La bonificación 1 debe estar entre 0 y 100."),
                            Map.entry("bonificacion2", "La bonificación 2 debe estar entre 0 y 100."),
                            Map.entry("bonificacion3", "La bonificación 3 puede tener como máximo 2 decimales.")));
            verifyNoInteractions(lector);
            verificarSinEscrituras();
            assertThat(proveedor.getBonificacion1()).isEqualByComparingTo("40");
        }
    }

    // ---- aplicar: articulos nuevos, mapeo y registro ----

    @Nested
    @DisplayName("aplicar: nuevos, mapeo guardado y registro")
    class AplicarNuevosYRegistro {

        /** Los articulos de saveAll, con el id que asigna la base (IDENTITY) a partir de 100. */
        private List<Articulo> articulosGuardados() {
            ArgumentCaptor<Iterable<Articulo>> captor = ArgumentCaptor.captor();
            verify(articuloRepository, times(1)).saveAll(captor.capture());
            List<Articulo> articulos = new ArrayList<>();
            captor.getValue().forEach(articulos::add);
            return articulos;
        }

        private void guardaArticulosAsignandoId() {
            when(articuloRepository.saveAll(any())).thenAnswer(inv -> {
                List<Articulo> guardados = new ArrayList<>();
                long id = 100;
                for (Object articulo : (Iterable<?>) inv.getArgument(0)) {
                    fijar(articulo, "id", id++);
                    guardados.add((Articulo) articulo);
                }
                return guardados;
            });
        }

        private ImportacionLista importacionGuardada() {
            ArgumentCaptor<ImportacionLista> captor = ArgumentCaptor.forClass(ImportacionLista.class);
            verify(importacionListaRepository).save(captor.capture());
            return captor.getValue();
        }

        private AplicarRequest crearNuevos(Long rubroId, ConteosRequest esperados) {
            return new AplicarRequest(PROVEEDOR, mapeo(), false, null, null, null, true, rubroId, esperados);
        }

        @Test
        @DisplayName("crearNuevos=false: no crea articulos y el registro cuenta 0 nuevos, pero aplica lo demas")
        void sinCrearNuevos() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(
                    encabezado(), fila(2, "AB-1", "x", "120"), fila(3, "N-1", "Tuerca", "10")));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 1, 0, 0, 0)), USUARIO);

            verify(articuloRepository, never()).saveAll(any());
            verify(articuloRepository, never()).save(any());
            assertThat(historialGuardado()).hasSize(1);
            ImportacionLista registro = importacionGuardada();
            assertThat(registro.getNuevos()).isZero();
            assertThat(registro.getSuben()).isEqualTo(1);
            assertThat(articulo.getCosto()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("crearNuevos=false con rubroId: no busca el rubro")
        void sinCrearNoBuscaElRubro() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Tuerca", "10")));
            guardaImportacion();
            AplicarRequest pedido = new AplicarRequest(
                    PROVEEDOR, mapeo(), false, null, null, null, false, 999L, conteos(0, 0, 1, 0, 0, 0));

            service.aplicar(ARCHIVO, pedido, USUARIO);

            verify(rubroRepository, never()).findScoped(any());
        }

        @Test
        @DisplayName("crearNuevos=true sin rubro: crea el articulo con los defaults, margen por defecto 30 % y precio 160")
        void creaSinRubro() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, " TOL-100 ", "Tornillo 1/4", "100")));
            guardaArticulosAsignandoId();
            guardaImportacion();

            service.aplicar(ARCHIVO, crearNuevos(null, conteos(0, 0, 1, 0, 0, 0)), USUARIO);

            List<Articulo> creados = articulosGuardados();
            assertThat(creados).hasSize(1);
            Articulo nuevo = creados.get(0);
            assertThat(nuevo.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(nuevo.getCodigoInterno()).isEqualTo("TOL-100");
            assertThat(nuevo.getCodigoProveedor()).isEqualTo("TOL-100");
            assertThat(nuevo.getDescripcion()).isEqualTo("Tornillo 1/4");
            assertThat(nuevo.getProveedorId()).isEqualTo(PROVEEDOR);
            assertThat(nuevo.getRubroId()).isNull();
            assertThat(nuevo.getCodigoBarras()).isNull();
            assertThat(nuevo.getMargenPorcentaje()).isNull();
            assertThat(nuevo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(nuevo.getUnidad()).isEqualTo(Unidad.UNIDAD);
            assertThat(nuevo.isActivo()).isTrue();
            assertThat(nuevo.getStockMinimo()).isEqualByComparingTo("0");
            assertThat(nuevo.getStockActual()).isEqualByComparingTo("0");
            assertThat(nuevo.getCosto()).isEqualByComparingTo("100.00");
            assertThat(nuevo.getPrecioListaProveedor()).isEqualByComparingTo("100.00");
            assertThat(nuevo.getPrecioFinal()).isEqualByComparingTo("160.00");
            assertThat(importacionGuardada().getNuevos()).isEqualTo(1);
        }

        @Test
        @DisplayName("crearNuevos=true con rubro del tenant: usa el margen del rubro (precio 170)")
        void creaConRubro() throws Exception {
            Rubro rubro = rubro("35.00");
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "TOL-100", "Tornillo", "100")), rubro);
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(rubro));
            guardaArticulosAsignandoId();
            guardaImportacion();

            service.aplicar(ARCHIVO, crearNuevos(RUBRO, conteos(0, 0, 1, 0, 0, 0)), USUARIO);

            Articulo nuevo = articulosGuardados().get(0);
            assertThat(nuevo.getRubroId()).isEqualTo(RUBRO);
            assertThat(nuevo.getPrecioFinal()).isEqualByComparingTo("170.00");
        }

        @Test
        @DisplayName("el alta se guarda ANTES de su historial IMPORTACION, con anteriores nulos y el id generado")
        void historialDeAltas() throws Exception {
            escenario(proveedor(), List.of(existente(1L, "AB-1", "100.00", null, true)), lectura(
                    encabezado(), fila(2, "AB-1", "x", "120"), fila(3, "TOL-100", "Tornillo", "100")));
            guardaArticulosAsignandoId();
            guardaImportacion();

            service.aplicar(ARCHIVO, crearNuevos(null, conteos(1, 0, 1, 0, 0, 0)), USUARIO);

            InOrder orden = inOrder(articuloRepository, historialPrecioRepository);
            orden.verify(articuloRepository).saveAll(any());
            orden.verify(historialPrecioRepository).saveAll(any());
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(2);
            HistorialPrecio alta = historial.stream().filter(h -> h.getCostoAnterior() == null).findFirst().orElseThrow();
            assertThat(alta.getArticuloId()).isEqualTo(100L);
            assertThat(alta.getOrigen()).isEqualTo(OrigenPrecio.IMPORTACION);
            assertThat(alta.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(alta.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(alta.getPrecioAnterior()).isNull();
            assertThat(alta.getCostoNuevo()).isEqualByComparingTo("100.00");
            assertThat(alta.getPrecioNuevo()).isEqualByComparingTo("160.00");
        }

        @Test
        @DisplayName("lista con IVA y bonificaciones: el alta toma el costo calculado y guarda el precio de lista crudo")
        void altaConIvaYBonificaciones() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "TOL-100", "Tornillo", "1210")));
            guardaArticulosAsignandoId();
            guardaImportacion();
            AplicarRequest pedido = new AplicarRequest(
                    PROVEEDOR, mapeo(), true, bd("40"), null, null, true, null, conteos(0, 0, 1, 0, 0, 0));

            service.aplicar(ARCHIVO, pedido, USUARIO);

            Articulo nuevo = articulosGuardados().get(0);
            assertThat(nuevo.getCosto()).isEqualByComparingTo("600.00");
            assertThat(nuevo.getPrecioListaProveedor()).isEqualByComparingTo("1210.00");
        }

        @Test
        @DisplayName("de dos filas nuevas solo se crea la creable; la otra queda como error")
        void soloLaCreable() throws Exception {
            String largo = "X".repeat(35);
            escenario(proveedor(), List.of(), lectura(
                    encabezado(), fila(2, "TOL-100", "Tornillo", "100"), fila(3, largo, "Largo", "100")));
            guardaArticulosAsignandoId();
            guardaImportacion();

            service.aplicar(ARCHIVO, crearNuevos(null, conteos(0, 0, 1, 0, 0, 1)), USUARIO);

            assertThat(articulosGuardados()).extracting(Articulo::getCodigoInterno).containsExactly("TOL-100");
            ImportacionLista registro = importacionGuardada();
            assertThat(registro.getNuevos()).isEqualTo(1);
            assertThat(registro.getConError()).isEqualTo(1);
        }

        @Test
        @DisplayName("rubro ajeno con crearNuevos: 404 y no se escribe nada")
        void rubroAjeno() throws Exception {
            Proveedor proveedor = proveedor();
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor));
            when(rubroRepository.findScoped(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, crearNuevos(99L, conteos(0, 0, 1, 0, 0, 0)), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Rubro no encontrado");

            verificarSinEscrituras();
            assertThat(proveedor.getMapeoLista()).isNull();
        }

        @Test
        @DisplayName("el camino feliz guarda en el proveedor el mapeo con encabezados, el IVA y las bonificaciones")
        void configuraElProveedor() throws Exception {
            Proveedor proveedor = proveedor();
            escenario(proveedor, List.of(existente(1L, "AB-1", "100.00", null, true)),
                    lectura(encabezado(), fila(2, "AB-1", "x", "1000")));
            guardaImportacion();
            AplicarRequest pedido = new AplicarRequest(
                    PROVEEDOR, mapeo(), true, bd("40"), bd("10"), null, false, null, conteos(1, 0, 0, 0, 0, 0));

            service.aplicar(ARCHIVO, pedido, USUARIO);

            assertThat(proveedor.getMapeoLista()).isEqualTo(
                    new MapeoLista(0, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Descripcion", "Costo")));
            assertThat(proveedor.isListaIncluyeIva()).isTrue();
            assertThat(proveedor.getBonificacion1()).isEqualByComparingTo("40");
            assertThat(proveedor.getBonificacion2()).isEqualByComparingTo("10");
            assertThat(proveedor.getBonificacion3()).isNull();
        }

        @Test
        @DisplayName("una importacion sin ningun cambio igual guarda el mapeo, el IVA y las bonificaciones")
        void sinCambiosTambienConfigura() throws Exception {
            Proveedor proveedor = proveedor();
            escenario(proveedor, List.of(existente(1L, "AB-1", "100.00", null, true)),
                    lectura(encabezado(), fila(2, "AB-1", "x", "121")));
            guardaImportacion();
            AplicarRequest pedido = new AplicarRequest(
                    PROVEEDOR, mapeo(), true, null, null, null, false, null, conteos(0, 0, 0, 1, 0, 0));

            service.aplicar(ARCHIVO, pedido, USUARIO);

            assertThat(proveedor.getMapeoLista()).isNotNull();
            assertThat(proveedor.isListaIncluyeIva()).isTrue();
        }

        @Test
        @DisplayName("bonificaciones vacias se guardan como null, reemplazando las anteriores")
        void bonificacionesVaciasSeGuardanNull() throws Exception {
            Proveedor proveedor = proveedor();
            proveedor.configurarLista(null, false, new Bonificaciones(bd("40"), bd("10"), bd("5")));
            escenario(proveedor, List.of(existente(1L, "AB-1", "100.00", null, true)),
                    lectura(encabezado(), fila(2, "AB-1", "x", "100")));
            guardaImportacion();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 0, 1, 0, 0)), USUARIO);

            assertThat(proveedor.getBonificacion1()).isNull();
            assertThat(proveedor.getBonificacion2()).isNull();
            assertThat(proveedor.getBonificacion3()).isNull();
        }

        @Test
        @DisplayName("el registro lleva proveedor, ferreteria, usuario, estado y el nombre recortado a 255")
        void registro() throws Exception {
            escenario(proveedor(), List.of(existente(1L, "AB-1", "100.00", null, true)),
                    lectura(encabezado(), fila(2, "AB-1", "x", "120")));
            guardaImportacion();
            ArchivoLista largo = new ArchivoLista("a".repeat(300) + ".xlsx", ARCHIVO.contenido());

            service.aplicar(largo, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            ImportacionLista registro = importacionGuardada();
            assertThat(registro.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(registro.getProveedorId()).isEqualTo(PROVEEDOR);
            assertThat(registro.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(registro.getEstado()).isEqualTo(EstadoImportacion.APLICADA);
            assertThat(registro.getArchivo()).hasSize(255);
        }
    }
}
