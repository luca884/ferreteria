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
import com.ferreteria.catalogo.dto.FilaElegida;
import com.ferreteria.catalogo.dto.ImportacionResponse;
import com.ferreteria.catalogo.dto.MapeoRequest;
import com.ferreteria.catalogo.dto.MuestraResponse;
import com.ferreteria.catalogo.dto.VistaPreviaRequest;
import com.ferreteria.catalogo.dto.VistaPreviaResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.ArchivoLista;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.EstadoImportacion;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.ReconocedorDeColumnas;
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
        when(lector.leer(any(byte[].class))).thenReturn(lectura);
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

        /** Una hoja con encabezado reconocible (Codigo, Descripcion, Costo) y `datos` filas de datos. */
        private LecturaLista unaHojaConDatos(int datos) {
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(encabezado());
            for (int n = 2; n <= datos + 1; n++) {
                filas.add(fila(n, "C" + n, "Desc " + n, "10"));
            }
            return new LecturaLista(List.of("Lista"), filas);
        }

        @Test
        @DisplayName("devuelve TODAS las hojas, cada una con TODAS sus filas y su numero original, sin tocar repositorios")
        void todasLasHojasYTodasLasFilas() {
            List<FilaLeida> filas = new ArrayList<>();
            for (int n = 1; n <= 10_000; n++) {
                filas.add(new FilaLeida(0, n * 2, List.of(celda("C" + n), celda("Desc " + n), celda("10"))));
            }
            filas.add(new FilaLeida(1, 5, List.of(celda("X-1"))));
            when(lector.leer(any(byte[].class))).thenReturn(new LecturaLista(List.of("Lista", "Precios"), filas));

            MuestraResponse respuesta = service.muestra(ARCHIVO);

            assertThat(respuesta.hojas()).extracting(MuestraResponse.HojaMuestra::nombre)
                    .containsExactly("Lista", "Precios");
            assertThat(respuesta.hojas()).extracting(MuestraResponse.HojaMuestra::indice).containsExactly(0, 1);
            MuestraResponse.HojaMuestra primera = respuesta.hojas().get(0);
            assertThat(primera.filas()).hasSize(10_000);
            assertThat(primera.filas().get(0).numero()).isEqualTo(2);
            assertThat(primera.filas().get(0).celdas()).containsExactly("C1", "Desc 1", "10");
            assertThat(primera.filas().get(14).numero()).isEqualTo(30);
            assertThat(primera.filas().get(9_999).numero()).isEqualTo(20_000);
            assertThat(primera.columnas()).isEqualTo(3);
            assertThat(respuesta.hojas().get(1).filas()).hasSize(1);
            assertThat(respuesta.hojas().get(1).columnas()).isEqualTo(1);
            verifyNoInteractions(proveedorRepository, rubroRepository, articuloRepository,
                    historialPrecioRepository, importacionListaRepository, configuracionPreciosService, dolarDePrecios);
        }

        @Test
        @DisplayName("lee el libro una sola vez")
        void unaSolaLectura() {
            when(lector.leer(any(byte[].class))).thenReturn(unaHojaConDatos(2));

            service.muestra(ARCHIVO);

            verify(lector, times(1)).leer(any(byte[].class));
        }

        @Test
        @DisplayName("devuelve todas las celdas de cada fila, mas alla de la 30, y informa el ancho de la grilla")
        void todasLasCeldas() {
            List<Celda> ancha = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                ancha.add(celda("c" + i));
            }
            when(lector.leer(any(byte[].class)))
                    .thenReturn(lectura(new FilaLeida(1, ancha), fila(2, "A", "B", "C")));

            MuestraResponse respuesta = service.muestra(ARCHIVO);

            assertThat(respuesta.hojas().get(0).filas().get(0).celdas()).hasSize(35);
            assertThat(respuesta.hojas().get(0).filas().get(0).celdas().get(34)).isEqualTo("c34");
            assertThat(respuesta.hojas().get(0).columnas()).isEqualTo(35);
        }

        @Test
        @DisplayName("tope de seguridad: corta cada fila en el maximo de columnas (200)")
        void topeDeColumnas() {
            assertThat(LimitesDeLista.MAXIMO_COLUMNAS_DE_MUESTRA).isEqualTo(200);
            List<Celda> patologica = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                patologica.add(celda("c" + i));
            }
            when(lector.leer(any(byte[].class)))
                    .thenReturn(lectura(new FilaLeida(1, patologica), fila(2, "A", "B", "C")));

            MuestraResponse respuesta = service.muestra(ARCHIVO);

            assertThat(respuesta.hojas().get(0).filas().get(0).celdas()).hasSize(200);
            assertThat(respuesta.hojas().get(0).filas().get(0).celdas().get(199)).isEqualTo("c199");
            assertThat(respuesta.hojas().get(0).columnas()).isEqualTo(200);
        }

        @Test
        @DisplayName("una hoja sin filas se devuelve vacia, con ancho 0")
        void hojaVacia() {
            when(lector.leer(any(byte[].class))).thenReturn(new LecturaLista(List.of("Vacia", "Lista"),
                    List.of(new FilaLeida(1, 1, List.of(celda("A"), celda("B"))))));

            MuestraResponse respuesta = service.muestra(ARCHIVO);

            assertThat(respuesta.hojas().get(0).filas()).isEmpty();
            assertThat(respuesta.hojas().get(0).columnas()).isZero();
            assertThat(respuesta.hojas().get(0).filaEncabezado()).isNull();
            assertThat(respuesta.hojas().get(1).filas()).hasSize(1);
        }

        @Test
        @DisplayName("un error del lector (archivo invalido) llega tal cual")
        void errorDelLector() {
            when(lector.leer(any(byte[].class)))
                    .thenThrow(new BadRequestException("No se pudo leer el archivo. Verificá que no esté dañado."));

            assertThatThrownBy(() -> service.muestra(ARCHIVO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No se pudo leer el archivo. Verificá que no esté dañado.");
        }

        @Test
        @DisplayName("la propuesta reconoce el encabezado, las columnas y la primera fila de datos del archivo")
        void propuesta() {
            when(lector.leer(any(byte[].class))).thenReturn(unaHojaConDatos(3));

            MuestraResponse respuesta = service.muestra(ARCHIVO);

            ReconocedorDeColumnas.Propuesta propuesta = respuesta.propuesta();
            assertThat(propuesta.hoja()).isZero();
            assertThat(propuesta.filaDesde()).isEqualTo(2);
            assertThat(propuesta.columnaCodigo()).isZero();
            assertThat(propuesta.columnaDescripcion()).isEqualTo(1);
            assertThat(propuesta.columnaCosto()).isEqualTo(2);
            assertThat(propuesta.hojas()).containsExactly(0);
            assertThat(respuesta.hojas().get(0).filaEncabezado()).isEqualTo(1);
        }

        @Test
        @DisplayName("un archivo sin encabezados reconocibles da una propuesta vacia, sin error")
        void sinPropuesta() {
            when(lector.leer(any(byte[].class))).thenReturn(lectura(fila(1, "xx", "yy", "zz"), fila(2, "1", "2", "3")));

            MuestraResponse respuesta = service.muestra(ARCHIVO);

            assertThat(respuesta.propuesta().columnaCodigo()).isNull();
            assertThat(respuesta.propuesta().columnaCosto()).isNull();
            assertThat(respuesta.propuesta().filaDesde()).isNull();
            assertThat(respuesta.propuesta().hojas()).isEmpty();
            assertThat(respuesta.hojas().get(0).filaEncabezado()).isNull();
        }

        @Test
        @DisplayName("la propuesta solo depende del archivo: no mira a ningun proveedor")
        void propuestaSoloDelArchivo() {
            when(lector.leer(any(byte[].class))).thenReturn(unaHojaConDatos(2));

            MuestraResponse uno = service.muestra(ARCHIVO);
            MuestraResponse otro = service.muestra(ARCHIVO);

            assertThat(uno.propuesta()).isEqualTo(otro.propuesta());
            verifyNoInteractions(proveedorRepository);
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
        @DisplayName("rubroId de otra ferreteria: 404 'Rubro no encontrado' (el mismo de aplicar) y no lee el archivo")
        void rubroAjeno() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, null, null, null, RUBRO)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Rubro no encontrado");
            verifyNoInteractions(lector, articuloRepository);
        }

        @Test
        @DisplayName("rubro ajeno con bonificaciones invalidas: 404 (la validacion va despues)")
        void rubroAjenoConBonificacionesInvalidas() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, bd("150"), null, null, RUBRO)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("sin rubroId no busca ningun rubro y el nuevo se calcula con el margen por defecto: 100 da 160")
        void sinRubroId() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Tuerca", "100")), rubro("35"));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            verify(rubroRepository, never()).findScoped(any());
            assertThat(respuesta.nuevos().get(0).precioNuevo()).isEqualByComparingTo("160");
        }

        @Test
        @DisplayName("con rubroId el nuevo sin rubro propio se calcula con el margen del rubro (35 %): 100 da 170")
        void conRubroId() throws Exception {
            Rubro rubro = rubro("35");
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Tuerca", "100")), rubro);
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(rubro));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, null, null, null, RUBRO));

            assertThat(respuesta.nuevos().get(0).precioNuevo()).isEqualByComparingTo("170");
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("lo que muestra la vista previa con un rubro es lo que crea aplicar con el mismo rubro: mismo precio y misma deriva")
        void vistaPreviaYAplicarCoinciden() throws Exception {
            Rubro rubro = rubro("35");
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Tuerca", "100")), rubro);
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(rubro));
            when(articuloRepository.saveAll(any())).thenAnswer(inv -> {
                long id = 100;
                for (Object articulo : (Iterable<?>) inv.getArgument(0)) {
                    fijar(articulo, "id", id++);
                }
                return inv.getArgument(0);
            });
            guardaImportacion();

            VistaPreviaResponse previa = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo(), false, null, null, null, RUBRO));
            service.aplicar(ARCHIVO, new AplicarRequest(
                    PROVEEDOR, mapeo(), false, null, null, null, true, RUBRO, conteos(0, 0, 1, 0, 0, 0)), USUARIO);

            ArgumentCaptor<Iterable<Articulo>> captor = ArgumentCaptor.captor();
            verify(articuloRepository).saveAll(captor.capture());
            List<Articulo> creados = new ArrayList<>();
            captor.getValue().forEach(creados::add);
            assertThat(creados.get(0).getPrecioFinal()).isEqualByComparingTo(previa.nuevos().get(0).precioNuevo());
            assertThat(creados.get(0).getPrecioFinal()).isEqualByComparingTo("170");
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
            when(lector.leer(any(byte[].class))).thenReturn(lectura(encabezado()));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, vistaPreviaRequest()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No hay filas de datos desde la fila 2.");
            verify(articuloRepository, never()).deProveedorScoped(any());
        }

        @Test
        @DisplayName("un archivo sin ninguna fila tambien da 400")
        void archivoSinFilas() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class))).thenReturn(lectura());

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, vistaPreviaRequest()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("No hay filas de datos desde la fila 2.");
        }

        @Test
        @DisplayName("sin encabezado, 20.001 filas de datos: 400 con el mensaje del limite (R-025)")
        void veinteMilUnaFilasDeDatosSinEncabezado() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class))).thenReturn(filasDeDatos(0, 20_001));

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
            when(lector.leer(any(byte[].class))).thenReturn(filasDeDatos(4, 20_001));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, new MapeoRequest(0, 5, 0, 1, 2), false, null, null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La lista tiene más de 20.000 filas. Dividila en archivos más chicos.");
        }

        @Test
        @DisplayName("devuelve el detalle completo, sin recorte: 2.500 filas con error llegan todas y detalleRecortado queda en false")
        void detalleCompleto() throws Exception {
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(encabezado());
            for (int n = 2; n <= 2501; n++) {
                filas.add(fila(n, "C" + n, "x", "abc"));
            }
            escenario(proveedor(), List.of(), new LecturaLista(List.of("Hoja1"), filas));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.conteos().conError()).isEqualTo(2500);
            assertThat(respuesta.errores()).hasSize(2500);
            assertThat(respuesta.errores().get(2499).fila()).isEqualTo(2501);
            assertThat(respuesta.detalleRecortado()).isFalse();
        }

        @Test
        @DisplayName("devuelve los cambios de moneda, las correcciones de IVA y las filas sin cambio en sus listas, con hoja y fila")
        void listasNuevas() throws Exception {
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
            when(dolarDePrecios.activa()).thenReturn(true);
            Articulo aMoneda = existente(1L, "MONEDA", "1000.00", null, true);
            Articulo corrige = existente(2L, "CORRIGE", "10000.00", null, true);
            Articulo igual = existente(3L, "IGUAL", "100.00", null, true);
            escenario(proveedor(), List.of(aMoneda, corrige, igual), lectura(
                    encabezado(),
                    new FilaLeida(2, List.of(celda("MONEDA"), celda("x"), celda("10"), celda("USD"), celda(""))),
                    new FilaLeida(3, List.of(celda("CORRIGE"), celda("x"), celda("10000"), celda("ARG"), celda("10,5"))),
                    new FilaLeida(4, List.of(celda("IGUAL"), celda("x"), celda("100"), celda("ARG"), celda("")))));
            MapeoRequest mapeo = new MapeoRequest(0, 2, 0, 1, 2, null, 3, null, null, null, 4, null, null, null, null,
                    null, null);

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO,
                    new VistaPreviaRequest(PROVEEDOR, mapeo, false, null, null, null));

            assertThat(respuesta.conteos().cambianMoneda()).isEqualTo(1);
            assertThat(respuesta.conteos().corrigen()).isEqualTo(1);
            assertThat(respuesta.conteos().sinCambio()).isEqualTo(1);
            assertThat(respuesta.cambios()).isEmpty();
            assertThat(respuesta.cambiosDeMoneda()).extracting(c -> c.fila()).containsExactly(2);
            assertThat(respuesta.cambiosDeMoneda().get(0).monedaNueva()).isEqualTo(Moneda.USD);
            assertThat(respuesta.correcciones()).extracting(c -> c.fila()).containsExactly(3);
            assertThat(respuesta.correcciones().get(0).alicuotaNueva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(respuesta.sinCambio()).hasSize(1);
            assertThat(respuesta.sinCambio().get(0).id()).isEqualTo(3L);
            assertThat(respuesta.sinCambio().get(0).hoja()).isZero();
            assertThat(respuesta.sinCambio().get(0).fila()).isEqualTo(4);
            assertThat(respuesta.sinCambio().get(0).codigo()).isEqualTo("IGUAL");
            assertThat(respuesta.sinCambio().get(0).descripcion()).isEqualTo("Articulo 3");
        }

        @Test
        @DisplayName("las filas de titulo se cuentan en salteadas y no aparecen en ninguna lista")
        void salteadasSoloCuentan() throws Exception {
            escenario(proveedor(), List.of(), lectura(
                    encabezado(), new FilaLeida(2, List.of(celda("Familia: 001 - Pasadores"))), fila(3, "N-1", "Nuevo", "10")));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.conteos().salteadas()).isEqualTo(1);
            assertThat(respuesta.errores()).isEmpty();
            assertThat(respuesta.nuevos()).hasSize(1);
        }

        @Test
        @DisplayName("2.500 filas nuevas: los contadores y el detalle dicen 2.500")
        void nuevasSinRecorte() throws Exception {
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(encabezado());
            for (int n = 2; n <= 2501; n++) {
                filas.add(fila(n, "N-" + n, "Nuevo " + n, "10"));
            }
            escenario(proveedor(), List.of(), new LecturaLista(List.of("Hoja1"), filas));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            assertThat(respuesta.conteos().nuevos()).isEqualTo(2500);
            assertThat(respuesta.nuevos()).hasSize(2500);
        }
    }

    // ---- preparar: el libro entero (T-134) ----

    @Nested
    @DisplayName("preparar: lectura del libro entero, dolar, rubros y barras")
    class Preparar {

        /** Mapeo de varias hojas: codigo 0, descripcion 1, costo 2, datos desde la fila 2 y, opcionales, moneda 3 y barras 4. */
        private MapeoRequest mapeoDe(List<Integer> hojas, Integer columnaMoneda, Integer columnaBarras) {
            return new MapeoRequest(0, 2, 0, 1, 2, hojas, columnaMoneda, null, null, null, null, null, null, null,
                    columnaBarras, null, null);
        }

        private VistaPreviaRequest pedido(MapeoRequest mapeo) {
            return new VistaPreviaRequest(PROVEEDOR, mapeo, false, null, null, null);
        }

        /** Una hoja con su encabezado y `datos` filas nuevas (codigos H{hoja}-n). */
        private List<FilaLeida> hoja(int hoja, int datos) {
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(new FilaLeida(hoja, 1, List.of(celda("Codigo"), celda("Descripcion"), celda("Costo"))));
            for (int n = 2; n <= datos + 1; n++) {
                filas.add(new FilaLeida(hoja, n, List.of(celda("H" + hoja + "-" + n), celda("Articulo " + n), celda("10"))));
            }
            return filas;
        }

        private LecturaLista libro(List<String> nombres, List<FilaLeida>... hojas) {
            List<FilaLeida> filas = new ArrayList<>();
            for (List<FilaLeida> deLaHoja : hojas) {
                filas.addAll(deLaHoja);
            }
            return new LecturaLista(nombres, filas);
        }

        @Test
        @DisplayName("el libro se lee UNA vez por pedido: la vista previa y aplicar leen una vez cada una")
        @SuppressWarnings("unchecked")
        void unaLecturaPorPedido() throws Exception {
            escenario(proveedor(), List.of(), libro(List.of("A", "B"), hoja(0, 2), hoja(1, 2)));
            guardaImportacion();

            service.vistaPrevia(ARCHIVO, pedido(mapeoDe(List.of(0, 1), null, null)));
            verify(lector, times(1)).leer(any(byte[].class));

            service.aplicar(ARCHIVO, new AplicarRequest(PROVEEDOR, mapeoDe(List.of(0, 1), null, null), false, null, null,
                    null, false, null, conteos(0, 0, 4, 0, 0, 0)), USUARIO);
            verify(lector, times(2)).leer(any(byte[].class));
        }

        @Test
        @DisplayName("dos hojas de 12.000 filas de datos suman 24.000: 400 por demasiadas filas")
        @SuppressWarnings("unchecked")
        void sumaDeLasHojasElegidas() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class))).thenReturn(libro(List.of("A", "B"), hoja(0, 12_000), hoja(1, 12_000)));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, pedido(mapeoDe(List.of(0, 1), null, null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La lista tiene más de 20.000 filas. Dividila en archivos más chicos.");
            verify(articuloRepository, never()).deProveedorScoped(any());
        }

        @Test
        @DisplayName("una hoja de 12.000 filas con la otra sin elegir pasa: el tope cuenta solo las hojas elegidas")
        @SuppressWarnings("unchecked")
        void unaHojaElegidaPasa() throws Exception {
            escenario(proveedor(), List.of(), libro(List.of("A", "B"), hoja(0, 12_000), hoja(1, 12_000)));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeoDe(List.of(0), null, null)));

            assertThat(respuesta.conteos().nuevos()).isEqualTo(12_000);
        }

        @Test
        @DisplayName("una hoja elegida que no existe en el archivo: 400")
        @SuppressWarnings("unchecked")
        void hojaInexistente() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(lector.leer(any(byte[].class))).thenReturn(libro(List.of("A", "B"), hoja(0, 2), hoja(1, 2)));

            assertThatThrownBy(() -> service.vistaPrevia(ARCHIVO, pedido(mapeoDe(List.of(0, 3), null, null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La hoja elegida no existe en el archivo.");
            verify(articuloRepository, never()).deProveedorScoped(any());
        }

        @Test
        @DisplayName("arma los contextos con el dolar vigente de la ferreteria: una fila en USD se calcula con el")
        void contextosConElDolarVigente() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(),
                    new FilaLeida(2, List.of(celda("U-1"), celda("Importado"), celda("10"), celda("USD")))));
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
            when(dolarDePrecios.activa()).thenReturn(true);

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeoDe(null, 3, null)));

            assertThat(respuesta.errores()).isEmpty();
            assertThat(respuesta.nuevos()).hasSize(1);
            // 10 x 1.000 x 1,30 x 1,21 = 15.730
            assertThat(respuesta.nuevos().get(0).precioNuevo()).isEqualByComparingTo("15730");
        }

        @Test
        @DisplayName("pasa si la cotizacion esta activa: apagada, un alta en USD es un error de esa fila y la de pesos se importa")
        void cotizacionApagada() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(),
                    new FilaLeida(2, List.of(celda("U-1"), celda("Importado"), celda("10"), celda("USD"))),
                    new FilaLeida(3, List.of(celda("P-1"), celda("Nacional"), celda("10"), celda("ARG")))));
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd("1000")));
            when(dolarDePrecios.activa()).thenReturn(false);

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeoDe(null, 3, null)));

            assertThat(respuesta.errores()).hasSize(1);
            assertThat(respuesta.errores().get(0).mensaje()).isEqualTo("Activá la cotización para cargar artículos en dólares");
            assertThat(respuesta.nuevos()).extracting(a -> a.codigo()).containsExactly("P-1");
        }

        @Test
        @DisplayName("sin ninguna cotizacion guardada la fila en USD da error y la de pesos sigue")
        void sinCotizacion() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(),
                    new FilaLeida(2, List.of(celda("U-1"), celda("Importado"), celda("10"), celda("USD")))));
            when(dolarDePrecios.vigente()).thenReturn(Optional.empty());
            when(dolarDePrecios.activa()).thenReturn(true);

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeoDe(null, 3, null)));

            assertThat(respuesta.errores().get(0).mensaje())
                    .isEqualTo("Todavía no hay cotización del dólar. Probá en unos minutos");
        }

        @Test
        @DisplayName("los rubros se cargan con listarScoped y la fila los nombra sin distinguir mayusculas ni tildes")
        void rubroPorNombre() throws Exception {
            Rubro buloneria = rubro("35.00");
            escenario(proveedor(), List.of(), lectura(encabezado(),
                    new FilaLeida(2, List.of(celda("N-1"), celda("Tornillo"), celda("1000"), celda("bulonería"))),
                    new FilaLeida(3, List.of(celda("N-2"), celda("Pintura"), celda("1000"), celda("Pinturas")))),
                    buloneria);
            MapeoRequest mapeo = new MapeoRequest(0, 2, 0, 1, 2, null, null, null, null, null, null, null, null, null,
                    null, null, 3);

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeo));

            verify(rubroRepository, times(1)).listarScoped();
            assertThat(respuesta.nuevos()).extracting(a -> a.rubroId()).containsExactly(RUBRO, null);
            // Rubro Buloneria con margen 35: 1.000 x 1,35 x 1,21 = 1.633,50 -> 1.640
            assertThat(respuesta.nuevos().get(0).precioNuevo()).isEqualByComparingTo("1640");
            assertThat(respuesta.advertencias()).extracting(a -> a.mensaje())
                    .containsExactly("El rubro «Pinturas» no existe: el artículo se crea con el rubro elegido al importar, o sin rubro.");
        }

        @Test
        @DisplayName("pregunta por los codigos de barras candidatos con la consulta scoped, y los ocupados son error")
        void barrasOcupadas() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(),
                    new FilaLeida(2, List.of(celda("N-1"), celda("Uno"), celda("10"), celda(""), celda("7791234567890"))),
                    new FilaLeida(3, List.of(celda("N-2"), celda("Dos"), celda("10"), celda(""), celda("7790000000001"))),
                    new FilaLeida(4, List.of(celda("N-3"), celda("Tres"), celda("10"), celda(""), celda("")))));
            when(articuloRepository.codigosBarrasActivosOcupadosScoped(anyCollection()))
                    .thenReturn(List.of("7791234567890"));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeoDe(null, null, 4)));

            verify(articuloRepository).codigosBarrasActivosOcupadosScoped(List.of("7791234567890", "7790000000001"));
            assertThat(respuesta.errores()).hasSize(1);
            assertThat(respuesta.errores().get(0).mensaje())
                    .isEqualTo("Ya existe un artículo activo con el código de barras 7791234567890.");
            assertThat(respuesta.nuevos()).extracting(a -> a.codigo()).containsExactly("N-2", "N-3");
        }

        @Test
        @DisplayName("sin columna de codigo de barras no pregunta por ellos")
        void sinColumnaDeBarras() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Uno", "10")));

            service.vistaPrevia(ARCHIVO, vistaPreviaRequest());

            verify(articuloRepository, never()).codigosBarrasActivosOcupadosScoped(any());
        }

        @Test
        @DisplayName("los codigos internos candidatos salen de la zona de datos de cada hoja, aunque arranque antes de filaDesde")
        void candidatosDeCadaHoja() throws Exception {
            // La hoja 0 es la de referencia (encabezado en la fila 3, datos desde la 4); la hoja 1 tiene el encabezado en la 1.
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(new FilaLeida(0, 1, List.of(celda("Titulo"))));
            filas.add(new FilaLeida(0, 3, List.of(celda("Codigo"), celda("Descripcion"), celda("Costo"))));
            filas.add(new FilaLeida(0, 4, List.of(celda("A-1"), celda("Uno"), celda("10"))));
            filas.add(new FilaLeida(1, 1, List.of(celda("Codigo"), celda("Descripcion"), celda("Costo"))));
            filas.add(new FilaLeida(1, 2, List.of(celda("B-1"), celda("Dos"), celda("10"))));
            escenario(proveedor(), List.of(), new LecturaLista(List.of("A", "B"), filas));
            MapeoRequest mapeo = new MapeoRequest(0, 4, 0, 1, 2, List.of(0, 1), null, null, null, null, null, null, null,
                    null, null, null, null);

            service.vistaPrevia(ARCHIVO, pedido(mapeo));

            verify(articuloRepository).codigosInternosOcupadosScoped(List.of("A-1", "B-1"));
        }

        @Test
        @DisplayName("los avisos llevan la hoja de la que salen")
        @SuppressWarnings("unchecked")
        void avisosConHoja() throws Exception {
            List<FilaLeida> hojaUno = hoja(1, 1);
            hojaUno.add(new FilaLeida(1, 3, List.of(celda("MAL-1"), celda("Sin costo"), celda("abc"))));
            escenario(proveedor(), List.of(), libro(List.of("A", "B"), hoja(0, 1), hojaUno));

            VistaPreviaResponse respuesta = service.vistaPrevia(ARCHIVO, pedido(mapeoDe(List.of(0, 1), null, null)));

            assertThat(respuesta.errores()).hasSize(1);
            assertThat(respuesta.errores().get(0).hoja()).isEqualTo(1);
            assertThat(respuesta.errores().get(0).fila()).isEqualTo(3);
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

    // ---- aplicar (corte 2): escritura de las filas elegidas (T-135) ----

    @Nested
    @DisplayName("aplicar: moneda, IVA, ajustes, ultima importacion y contadores de lo aplicado")
    class AplicarCorte2 {

        private static final Long IMPORTACION = 7L;

        /** Mapeo con las columnas que se piden (indices a mano): moneda, % IVA, ajuste 1 y rubro. */
        private MapeoRequest mapeoCon(Integer moneda, Integer iva, Integer ajuste1, Integer rubro) {
            return new MapeoRequest(0, 2, 0, 1, 2, null, moneda, null, null, null, iva, ajuste1, null, null, null,
                    null, rubro);
        }

        private AplicarRequest pedidoCon(MapeoRequest mapeo, boolean crearNuevos, Long rubroId, ConteosRequest esperados) {
            return new AplicarRequest(PROVEEDOR, mapeo, false, null, null, null, crearNuevos, rubroId, esperados, null);
        }

        private void dolarEn(String valor) {
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd(valor)));
            when(dolarDePrecios.activa()).thenReturn(true);
        }

        /** El save de la importacion devuelve la entidad con el id que le da la base. */
        private void guardaImportacionConId() {
            when(importacionListaRepository.save(any(ImportacionLista.class))).thenAnswer(inv -> {
                fijar(inv.getArgument(0), "id", IMPORTACION);
                return inv.getArgument(0);
            });
        }

        private FilaLeida filaConColumnas(int numero, String... textos) {
            List<Celda> celdas = new ArrayList<>();
            for (String texto : textos) {
                celdas.add(celda(texto));
            }
            return new FilaLeida(numero, celdas);
        }

        private ImportacionLista importacionGuardada() {
            ArgumentCaptor<ImportacionLista> captor = ArgumentCaptor.forClass(ImportacionLista.class);
            verify(importacionListaRepository).save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("la importacion se guarda PRIMERO, con los contadores de lo aplicado, para tener el id antes de tocar los articulos")
        void laImportacionSeGuardaPrimero() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "120")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            InOrder orden = inOrder(importacionListaRepository, historialPrecioRepository);
            orden.verify(importacionListaRepository).save(any(ImportacionLista.class));
            orden.verify(historialPrecioRepository).saveAll(any());
            assertThat(articulo.getUltimaImportacionId()).isEqualTo(IMPORTACION);
        }

        @Test
        @DisplayName("suben, bajan, cambian de moneda, corrigen, sin cambio y los nuevos quedan con ultimaImportacionId")
        void ultimaImportacionEnTodosLosTipos() throws Exception {
            dolarEn("1000");
            Articulo sube = existente(1L, "SUBE", "100.00", null, true);
            Articulo baja = existente(2L, "BAJA", "100.00", null, true);
            Articulo moneda = existente(3L, "MONEDA", "1000.00", null, true);
            Articulo corrige = existente(4L, "CORRIGE", "10000.00", null, true);
            Articulo igual = existente(5L, "IGUAL", "100.00", null, true);
            Articulo ausente = existente(6L, "AUSENTE", "100.00", null, true);
            escenario(proveedor(), List.of(sube, baja, moneda, corrige, igual, ausente), lectura(
                    encabezado(),
                    filaConColumnas(2, "SUBE", "x", "120", "ARG", "", ""),
                    filaConColumnas(3, "BAJA", "x", "80", "ARG", "", ""),
                    filaConColumnas(4, "MONEDA", "x", "10", "USD", "", ""),
                    filaConColumnas(5, "CORRIGE", "x", "10000", "ARG", "10,5", ""),
                    filaConColumnas(6, "IGUAL", "x", "100", "ARG", "", ""),
                    filaConColumnas(7, "NUEVO", "Tuerca", "10", "ARG", "", "")));
            guardaImportacionConId();
            guardaArticulosAsignandoId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(3, 4, null, null), true, null,
                    new ConteosRequest(1, 1, 1, 1, 1, 0, 1, 1, 0)), USUARIO);

            for (Articulo tocado : List.of(sube, baja, moneda, corrige, igual)) {
                assertThat(tocado.getUltimaImportacionId()).as(tocado.getCodigoProveedor()).isEqualTo(IMPORTACION);
            }
            assertThat(ausente.getUltimaImportacionId()).isNull();
            assertThat(articulosGuardados()).extracting(Articulo::getUltimaImportacionId).containsExactly(IMPORTACION);
        }

        @Test
        @DisplayName("cambio de moneda: costo 1.000 a 10 USD y precio 1.580 a 15.730, con las dos monedas en el historial")
        void cambioDeMoneda() throws Exception {
            dolarEn("1000");
            Articulo articulo = existente(1L, "AB-1", "1000.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), filaConColumnas(2, "AB-1", "x", "10", "USD")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(3, null, null, null), false, null,
                    new ConteosRequest(0, 0, 0, 0, 0, 0, 1, 0, 0)), USUARIO);

            assertThat(articulo.getMoneda()).isEqualTo(Moneda.USD);
            assertThat(articulo.getCosto()).isEqualByComparingTo("10.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("15730.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(1);
            HistorialPrecio entrada = historial.get(0);
            assertThat(entrada.getOrigen()).isEqualTo(OrigenPrecio.IMPORTACION);
            assertThat(entrada.getCostoAnterior()).isEqualByComparingTo("1000.00");
            assertThat(entrada.getCostoNuevo()).isEqualByComparingTo("10.00");
            assertThat(entrada.getPrecioAnterior()).isEqualByComparingTo("1580.00");
            assertThat(entrada.getPrecioNuevo()).isEqualByComparingTo("15730.00");
            assertThat(entrada.getMonedaAnterior()).isEqualTo(Moneda.ARS);
            assertThat(entrada.getMoneda()).isEqualTo(Moneda.USD);
        }

        @Test
        @DisplayName("el historial de un cambio dentro de la misma moneda no lleva moneda anterior")
        void sinCambioDeMonedaNoLlevaMonedaAnterior() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "120")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(historialGuardado().get(0).getMonedaAnterior()).isNull();
        }

        @Test
        @DisplayName("correccion de IVA: el articulo pasa a IVA_10_5 y el historial va de 15.730 a 14.370")
        void correccionDeIva() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "10000.00", null, true);
            escenario(proveedor(), List.of(articulo),
                    lectura(encabezado(), filaConColumnas(2, "AB-1", "x", "10000", "", "10,5")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, 4, null, null), false, null,
                    new ConteosRequest(0, 0, 0, 0, 0, 0, 0, 1, 0)), USUARIO);

            assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(articulo.getCosto()).isEqualByComparingTo("10000.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("14370.00");
            List<HistorialPrecio> historial = historialGuardado();
            assertThat(historial).hasSize(1);
            assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("15730.00");
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("14370.00");
            assertThat(historial.get(0).getMonedaAnterior()).isNull();
        }

        @Test
        @DisplayName("decision 37: un IVA que no mueve el precio es 'Corrige': se guarda en el articulo, el precio queda igual y no deja historial")
        void ivaSinCambioDePrecio() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "1.00", null, true);
            BigDecimal precioAntes = articulo.getPrecioFinal();
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), filaConColumnas(2, "AB-1", "x", "1", "", "10,5")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, 4, null, null), false, null,
                    new ConteosRequest(0, 0, 0, 0, 0, 0, 0, 1, 0)), USUARIO);

            assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo(precioAntes);
            assertThat(articulo.getUltimaImportacionId()).isEqualTo(IMPORTACION);
            assertThat(importacionGuardada().getCorrigen()).isEqualTo(1);
            assertThat(importacionGuardada().getSinCambio()).isZero();
            verify(historialPrecioRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("una fila 'sin cambio' solo anota la importacion y el precio de lista: ni alicuota, ni ajustes, ni precio, ni historial")
        void sinCambioNoTocaElArticulo() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            BigDecimal precioAntes = articulo.getPrecioFinal();
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), filaConColumnas(2, "AB-1", "x", "100", "", "21")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, 4, null, null), false, null,
                    new ConteosRequest(0, 0, 0, 1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
            assertThat(articulo.getCosto()).isEqualByComparingTo("100.00");
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo(precioAntes);
            assertThat(articulo.getAjustes()).isEqualTo(Ajustes.NINGUNO);
            assertThat(articulo.getUltimaImportacionId()).isEqualTo(IMPORTACION);
            verify(historialPrecioRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("ajustes: con la columna mapeada manda la celda: un valor se guarda y una celda vacia borra el ajuste")
        void ajustes() throws Exception {
            Articulo borrado = existente(1L, "BORRA", "100.00", null, true);
            Articulo nuevoValor = existente(2L, "NUEVO", "100.00", null, true);
            borrado.fijarAjustes(new Ajustes(bd("-5"), null, null));
            escenario(proveedor(), List.of(borrado, nuevoValor), lectura(
                    encabezado(),
                    filaConColumnas(2, "BORRA", "x", "1000", "", "", "", ""),
                    filaConColumnas(3, "NUEVO", "x", "1000", "", "", "", "-5")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, null, 6, null), false, null,
                    new ConteosRequest(2, 0, 0, 0, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(borrado.getAjuste1()).isNull();
            assertThat(borrado.getCosto()).isEqualByComparingTo("1000.00");
            assertThat(nuevoValor.getAjuste1()).isEqualByComparingTo("-5.00");
            assertThat(nuevoValor.getCosto()).isEqualByComparingTo("950.00");
        }

        @Test
        @DisplayName("sin columnas de ajuste se conservan los del articulo: el costo es 950 con su -5")
        void sinColumnasConservaLosAjustes() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "900.00", null, true);
            articulo.fijarAjustes(new Ajustes(bd("-5"), null, null));
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), fila(2, "AB-1", "x", "1000")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 0, 0)), USUARIO);

            assertThat(articulo.getAjuste1()).isEqualByComparingTo("-5.00");
            assertThat(articulo.getCosto()).isEqualByComparingTo("950.00");
        }

        @Test
        @DisplayName("cada alta usa el contexto de SU rubro: el de la columna, el elegido o ninguno")
        void cadaAltaConSuRubro() throws Exception {
            Rubro buloneria = rubro("35.00");
            escenario(proveedor(), List.of(), lectura(
                    encabezado(),
                    filaConColumnas(2, "N-1", "De la columna", "1000", "", "", "", "", "Buloneria"),
                    filaConColumnas(3, "N-2", "Sin rubro en la columna", "1000", "", "", "", "", ""),
                    filaConColumnas(4, "N-3", "Rubro que no existe", "1000", "", "", "", "", "Pinturas")),
                    buloneria);
            when(rubroRepository.findScoped(RUBRO)).thenReturn(Optional.of(buloneria));
            guardaImportacionConId();
            guardaArticulosAsignandoId();

            // El rubro elegido es el mismo de la primera fila para distinguir: las otras dos caen en el elegido.
            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, null, null, 7), true, RUBRO, conteos9(0, 0, 3, 0, 0, 0, 0, 0, 0)),
                    USUARIO);

            List<Articulo> creados = articulosGuardados();
            assertThat(creados).extracting(Articulo::getRubroId).containsExactly(RUBRO, RUBRO, RUBRO);
            assertThat(creados).extracting(Articulo::getPrecioFinal)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(bd("1640.00"), bd("1640.00"), bd("1640.00"));
            verify(rubroRepository, never()).save(any());
        }

        @Test
        @DisplayName("un rubro de la columna distinto del elegido: el alta va al de la columna y con su margen")
        void rubroDeLaColumnaGanaSobreElElegido() throws Exception {
            Rubro buloneria = rubro("35.00");
            escenario(proveedor(), List.of(), lectura(
                    encabezado(),
                    filaConColumnas(2, "N-1", "De la columna", "1000", "", "", "", "", "Buloneria"),
                    filaConColumnas(3, "N-2", "Sin rubro en la columna", "1000", "", "", "", "", "")),
                    buloneria);
            guardaImportacionConId();
            guardaArticulosAsignandoId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, null, null, 7), true, null, conteos9(0, 0, 2, 0, 0, 0, 0, 0, 0)),
                    USUARIO);

            List<Articulo> creados = articulosGuardados();
            assertThat(creados).extracting(Articulo::getRubroId).containsExactly(RUBRO, null);
            // 1.000 x 1,35 x 1,21 = 1.633,50 -> 1.640 con el rubro; 1.000 x 1,30 x 1,21 = 1.573 -> 1.580 sin rubro
            assertThat(creados.get(0).getPrecioFinal()).isEqualByComparingTo("1640.00");
            assertThat(creados.get(1).getPrecioFinal()).isEqualByComparingTo("1580.00");
        }

        @Test
        @DisplayName("el alta toma moneda, IVA, ajustes, barras y unidad de la fila")
        void altaConTodosLosCampos() throws Exception {
            dolarEn("1000");
            escenario(proveedor(), List.of(), lectura(encabezado(),
                    filaConColumnas(2, "N-1", "Importado", "10", "USD", "10,5", "", "", "7791234567890", "metro")));
            guardaImportacionConId();
            guardaArticulosAsignandoId();
            MapeoRequest mapeo = new MapeoRequest(0, 2, 0, 1, 2, null, 3, null, null, null, 4, null, null, null, 7, 8, null);

            service.aplicar(ARCHIVO, pedidoCon(mapeo, true, null, conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0)), USUARIO);

            Articulo nuevo = articulosGuardados().get(0);
            assertThat(nuevo.getMoneda()).isEqualTo(Moneda.USD);
            assertThat(nuevo.getCosto()).isEqualByComparingTo("10.00");
            assertThat(nuevo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(nuevo.getCodigoBarras()).isEqualTo("7791234567890");
            assertThat(nuevo.getUnidad()).isEqualTo(Unidad.METRO);
            // 10 x 1.000 x 1,30 x 1,105 = 14.365 -> 14.370
            assertThat(nuevo.getPrecioFinal()).isEqualByComparingTo("14370.00");
            assertThat(historialGuardado()).hasSize(1);
            assertThat(historialGuardado().get(0).getMoneda()).isEqualTo(Moneda.USD);
        }

        @Test
        @DisplayName("las filas salteadas no tocan nada y quedan en el contador del registro")
        void salteadas() throws Exception {
            Articulo articulo = existente(1L, "AB-1", "100.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(
                    encabezado(), filaConColumnas(2, "Familia: 001 - Pasadores"), fila(3, "AB-1", "x", "100")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedidoCon(mapeoCon(null, null, null, null), false, null,
                    new ConteosRequest(0, 0, 0, 1, 0, 0, 0, 0, 1)), USUARIO);

            assertThat(importacionGuardada().getSalteadas()).isEqualTo(1);
            assertThat(importacionGuardada().getSinCambio()).isEqualTo(1);
            verify(articuloRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("el registro guarda los contadores de lo aplicado: 200 suben, 2.700 nuevos y 100 sin cambio")
        void contadoresGuardados() throws Exception {
            List<Articulo> existentes = new ArrayList<>();
            List<FilaLeida> filas = new ArrayList<>();
            filas.add(encabezado());
            int numero = 2;
            for (int i = 0; i < 200; i++, numero++) {
                existentes.add(existente(1000L + i, "S-" + i, "100.00", null, true));
                filas.add(fila(numero, "S-" + i, "x", "120"));
            }
            for (int i = 0; i < 100; i++, numero++) {
                existentes.add(existente(2000L + i, "I-" + i, "100.00", null, true));
                filas.add(fila(numero, "I-" + i, "x", "100"));
            }
            for (int i = 0; i < 2700; i++, numero++) {
                filas.add(fila(numero, "N-" + i, "Nuevo " + i, "10"));
            }
            escenario(proveedor(), existentes, new LecturaLista(List.of("Hoja1"), filas));
            guardaImportacionConId();
            guardaArticulosAsignandoId();

            service.aplicar(ARCHIVO, crearNuevosCon(conteos9(200, 0, 2700, 100, 0, 0, 0, 0, 0)), USUARIO);

            ImportacionLista registro = importacionGuardada();
            assertThat(registro.getSuben()).isEqualTo(200);
            assertThat(registro.getNuevos()).isEqualTo(2700);
            assertThat(registro.getSinCambio()).isEqualTo(100);
            assertThat(articulosGuardados()).hasSize(2700);
        }

        @Test
        @DisplayName("el contador de nuevos vale 0 si no se piden crear")
        void nuevosEnCeroSiNoSeCrean() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Uno", "10"), fila(3, "N-2", "Dos", "10")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 2, 0, 0, 0)), USUARIO);

            assertThat(importacionGuardada().getNuevos()).isZero();
            verify(articuloRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("la respuesta trae los contadores nuevos")
        void respuestaConContadoresNuevos() throws Exception {
            dolarEn("1000");
            Articulo articulo = existente(1L, "AB-1", "1000.00", null, true);
            escenario(proveedor(), List.of(articulo), lectura(encabezado(), filaConColumnas(2, "AB-1", "x", "10", "USD"),
                    filaConColumnas(3, "Familia: 002")));
            guardaImportacionConId();

            ImportacionResponse respuesta = service.aplicar(ARCHIVO, pedidoCon(mapeoCon(3, null, null, null), false, null,
                    new ConteosRequest(0, 0, 0, 0, 0, 0, 1, 0, 1)), USUARIO);

            assertThat(respuesta.conteos().cambianMoneda()).isEqualTo(1);
            assertThat(respuesta.conteos().salteadas()).isEqualTo(1);
            assertThat(respuesta.conteos().corrigen()).isZero();
        }

        @Test
        @DisplayName("todo se escribe en una sola transaccion")
        void soloUnaTransaccion() throws Exception {
            assertThat(ImportacionService.class.getMethod("aplicar", ArchivoLista.class, AplicarRequest.class, Long.class)
                    .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        }

        private AplicarRequest crearNuevosCon(ConteosRequest esperados) {
            return new AplicarRequest(PROVEEDOR, mapeo(), false, null, null, null, true, null, esperados, null);
        }

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
    }

    // ---- aplicar (corte 2): seleccion, deriva y mapeo (T-136) ----

    @Nested
    @DisplayName("aplicar: seleccion de filas, deriva y mapeo guardado")
    class AplicarSeleccion {

        private static final Long IMPORTACION = 7L;

        private void guardaImportacionConId() {
            when(importacionListaRepository.save(any(ImportacionLista.class))).thenAnswer(inv -> {
                fijar(inv.getArgument(0), "id", IMPORTACION);
                return inv.getArgument(0);
            });
        }

        private AplicarRequest pedido(boolean crearNuevos, ConteosRequest esperados, FilaElegida... elegidas) {
            return new AplicarRequest(PROVEEDOR, mapeo(), false, null, null, null, crearNuevos, null, esperados,
                    elegidas.length == 0 ? null : List.of(elegidas));
        }

        private AplicarRequest pedidoConLista(ConteosRequest esperados, List<FilaElegida> elegidas) {
            return new AplicarRequest(PROVEEDOR, mapeo(), false, null, null, null, false, null, esperados, elegidas);
        }

        /** Tres existentes que suben (filas 2, 3 y 4) y un nuevo (fila 5). */
        private List<Articulo> tresQueSuben() throws Exception {
            return List.of(existente(1L, "AB-1", "100.00", null, true), existente(2L, "AB-2", "100.00", null, true),
                    existente(3L, "AB-3", "100.00", null, true));
        }

        private LecturaLista listaDeCuatro() {
            return lectura(encabezado(), fila(2, "AB-1", "x", "120"), fila(3, "AB-2", "x", "130"),
                    fila(4, "AB-3", "x", "140"), fila(5, "N-1", "Nuevo", "10"));
        }

        @Test
        @DisplayName("sin seleccion (null) equivale a todas las filas")
        void sinSeleccionEsTodas() throws Exception {
            List<Articulo> articulos = tresQueSuben();
            escenario(proveedor(), articulos, listaDeCuatro());
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(3, 0, 1, 0, 0, 0)), USUARIO);

            assertThat(articulos).extracting(Articulo::getCosto)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(bd("120"), bd("130"), bd("140"));
        }

        @Test
        @DisplayName("una seleccion parcial toca solo lo elegido: lo demas no cambia ni costo, ni moneda, ni IVA, ni ajustes, ni ultima importacion")
        void seleccionParcial() throws Exception {
            List<Articulo> articulos = tresQueSuben();
            escenario(proveedor(), articulos, listaDeCuatro());
            guardaImportacionConId();

            // Se elige solo la fila 2: suben = 1 y nada mas, y de lo que no se eligio los contadores del archivo no cambian.
            service.aplicar(ARCHIVO, pedido(true, conteos9(1, 0, 0, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 2)), USUARIO);

            assertThat(articulos.get(0).getCosto()).isEqualByComparingTo("120.00");
            assertThat(articulos.get(0).getUltimaImportacionId()).isEqualTo(IMPORTACION);
            for (Articulo noElegido : articulos.subList(1, 3)) {
                assertThat(noElegido.getCosto()).isEqualByComparingTo("100.00");
                assertThat(noElegido.getMoneda()).isEqualTo(Moneda.ARS);
                assertThat(noElegido.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
                assertThat(noElegido.getAjustes()).isEqualTo(Ajustes.NINGUNO);
                assertThat(noElegido.getUltimaImportacionId()).isNull();
                assertThat(noElegido.getPrecioListaProveedor()).isNull();
            }
            verify(articuloRepository, never()).saveAll(any());
            assertThat(historialGuardado()).extracting(HistorialPrecio::getArticuloId).containsExactly(1L);
        }

        @Test
        @DisplayName("los nuevos no elegidos no se crean; los elegidos si")
        void nuevosNoElegidosNoSeCrean() throws Exception {
            escenario(proveedor(), tresQueSuben(), listaDeCuatro());
            guardaImportacionConId();
            when(articuloRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            service.aplicar(ARCHIVO, pedido(true, conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 5)), USUARIO);

            ArgumentCaptor<Iterable<Articulo>> captor = ArgumentCaptor.captor();
            verify(articuloRepository).saveAll(captor.capture());
            List<Articulo> creados = new ArrayList<>();
            captor.getValue().forEach(creados::add);
            assertThat(creados).extracting(Articulo::getCodigoInterno).containsExactly("N-1");
        }

        @Test
        @DisplayName("con crearNuevos apagado el nuevo elegido junto con uno que sube no se crea; el que sube si se aplica")
        void crearNuevosApagado() throws Exception {
            List<Articulo> articulos = tresQueSuben();
            escenario(proveedor(), articulos, listaDeCuatro());
            guardaImportacionConId();

            service.aplicar(ARCHIVO,
                    pedido(false, conteos9(1, 0, 1, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 2), new FilaElegida(0, 5)),
                    USUARIO);

            verify(articuloRepository, never()).saveAll(any());
            assertThat(articulos.get(0).getCosto()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("con crearNuevos apagado, elegir solo filas nuevas es 400 'Elegí al menos una fila.' y no se escribe NADA (ni el mapeo)")
        void soloNuevasSinCrearNuevosNoEscribeNada() throws Exception {
            Proveedor proveedor = proveedor();
            escenario(proveedor, tresQueSuben(), listaDeCuatro());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedido(false, conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 5)), USUARIO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Elegí al menos una fila.");

            verificarSinEscrituras();
            assertThat(proveedor.getMapeoLista()).isNull();
        }

        @Test
        @DisplayName("con crearNuevos prendido las mismas filas nuevas se crean (no es 400)")
        void soloNuevasCreandoNuevos() throws Exception {
            escenario(proveedor(), tresQueSuben(), listaDeCuatro());
            guardaImportacionConId();
            when(articuloRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            service.aplicar(ARCHIVO, pedido(true, conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 5)), USUARIO);

            verify(articuloRepository).saveAll(any());
        }

        @Test
        @DisplayName("sin seleccion (cliente viejo) y sin crear nuevos, con solo filas nuevas en la lista, sigue sin ser 400")
        void sinSeleccionSinCrearNuevosEsCompatible() throws Exception {
            escenario(proveedor(), List.of(), lectura(encabezado(), fila(2, "N-1", "Nuevo", "10")));
            guardaImportacionConId();

            ImportacionResponse respuesta = service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 1, 0, 0, 0)), USUARIO);

            assertThat(respuesta.conteos().nuevos()).isZero();
            verify(articuloRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("deriva: si lo elegido ya no da lo que mostro la vista previa, 409 y no se escribe NADA (ni el mapeo del proveedor)")
        void derivaNoEscribeNada() throws Exception {
            Proveedor proveedor = proveedor();
            List<Articulo> articulos = tresQueSuben();
            escenario(proveedor, articulos, listaDeCuatro());

            // La vista previa vio 2 que suben y se elige solo una.
            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedido(true, conteos9(2, 0, 0, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 2)), USUARIO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("La lista cambió desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.");

            verificarSinEscrituras();
            assertThat(proveedor.getMapeoLista()).isNull();
            assertThat(articulos.get(0).getCosto()).isEqualByComparingTo("100.00");
            assertThat(articulos.get(0).getUltimaImportacionId()).isNull();
        }

        @Test
        @DisplayName("una fila no elegida que cambia sin mover los contadores de lo elegido no da 409")
        void filaNoElegidaNoFrena() throws Exception {
            List<Articulo> articulos = tresQueSuben();
            // El AB-3 ahora ya esta en 140: de 'sube' paso a 'sin cambio', pero no se habia elegido.
            articulos.get(2).actualizarCosto(bd("140.00"), bd("140.00"), new ContextoPrecio(null, bd("30.00"), bd("10.00")));
            escenario(proveedor(), articulos, listaDeCuatro());
            guardaImportacionConId();

            service.aplicar(ARCHIVO, pedido(false, conteos9(1, 0, 0, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 2)), USUARIO);

            assertThat(articulos.get(0).getCosto()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("una fila con error elegida es un 400 y no se escribe nada")
        void filaConErrorElegida() throws Exception {
            escenario(proveedor(), tresQueSuben(), lectura(encabezado(), fila(2, "AB-1", "x", "abc"), fila(3, "AB-2", "x", "130")));

            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedido(false, conteos9(1, 0, 0, 0, 1, 1, 0, 0, 0), new FilaElegida(0, 2), new FilaElegida(0, 3)), USUARIO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("tiene un error");

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("una fila que no existe en el archivo (o una hoja que no existe) es un 400")
        void filaInexistente() throws Exception {
            escenario(proveedor(), tresQueSuben(), listaDeCuatro());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedido(false, conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0), new FilaElegida(0, 4000)), USUARIO))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedido(false, conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0), new FilaElegida(3, 2)), USUARIO))
                    .isInstanceOf(BadRequestException.class);

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("una seleccion vacia es un 400 'Elegí al menos una fila'")
        void seleccionVacia() throws Exception {
            escenario(proveedor(), tresQueSuben(), listaDeCuatro());

            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedidoConLista(conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of()), USUARIO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Elegí al menos una fila.");

            verificarSinEscrituras();
        }

        @Test
        @DisplayName("el orden es 404 del proveedor o el rubro, el plan, la seleccion (400 o 409) y recien ahi la primera escritura")
        void ordenDeLasComprobaciones() throws Exception {
            // 404 del proveedor: no se lee el archivo.
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.aplicar(ARCHIVO, pedido(false, conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0),
                    new FilaElegida(0, 4000)), USUARIO)).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(lector);

            // 404 del rubro: tampoco se lee.
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedor()));
            when(rubroRepository.findScoped(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.aplicar(ARCHIVO, new AplicarRequest(PROVEEDOR, mapeo(), false, null, null,
                    null, true, 99L, conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(new FilaElegida(0, 4000))), USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(lector);

            // Con la seleccion mala y los contadores tambien malos, gana el 400 de la clave inexistente.
            escenario(proveedor(), tresQueSuben(), listaDeCuatro());
            assertThatThrownBy(() -> service.aplicar(ARCHIVO,
                    pedido(false, conteos9(9, 9, 9, 9, 9, 9, 9, 9, 9), new FilaElegida(0, 4000)), USUARIO))
                    .isInstanceOf(BadRequestException.class);
            verificarSinEscrituras();
        }

        @Test
        @DisplayName("aplicar dos veces la misma seleccion: la segunda no repite cambios ni duplica el historial")
        void aplicarDosVeces() throws Exception {
            List<Articulo> articulos = tresQueSuben();
            escenario(proveedor(), articulos, lectura(encabezado(), fila(2, "AB-1", "x", "120")));
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 2, 0)), USUARIO);
            // Los mismos contadores ya no valen: el costo esta en 120 y la fila es 'sin cambio'.
            assertThatThrownBy(() -> service.aplicar(ARCHIVO, aplicarRequest(conteos(1, 0, 0, 0, 2, 0)), USUARIO))
                    .isInstanceOf(ConflictException.class);
            // Con los contadores de una nueva vista previa aplica, pero sin historial nuevo.
            service.aplicar(ARCHIVO, aplicarRequest(conteos(0, 0, 0, 1, 2, 0)), USUARIO);

            verify(historialPrecioRepository, times(1)).saveAll(any());
            assertThat(articulos.get(0).getCosto()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("el mapeo se guarda solo al aplicar con exito: en la vista previa y en un 409 el guardado no cambia")
        void mapeoSoloAlAplicar() throws Exception {
            Proveedor proveedor = proveedor();
            escenario(proveedor, tresQueSuben(), listaDeCuatro());

            service.vistaPrevia(ARCHIVO, vistaPreviaRequest());
            assertThat(proveedor.getMapeoLista()).isNull();

            assertThatThrownBy(() -> service.aplicar(ARCHIVO, aplicarRequest(conteos(9, 0, 0, 0, 0, 0)), USUARIO))
                    .isInstanceOf(ConflictException.class);
            assertThat(proveedor.getMapeoLista()).isNull();
        }

        @Test
        @DisplayName("el mapeo que se guarda incluye las hojas, las columnas nuevas y sus encabezados")
        void mapeoNuevoCompleto() throws Exception {
            Proveedor proveedor = proveedor();
            dolarEn("1000");
            escenario(proveedor, List.of(), new LecturaLista(List.of("Hoja1"), List.of(
                    new FilaLeida(1, List.of(celda("Codigo"), celda("Descripcion"), celda("Costo"), celda("Mon."))),
                    new FilaLeida(2, List.of(celda("N-1"), celda("Nuevo"), celda("10"), celda("USD"))))));
            guardaImportacionConId();
            MapeoRequest mapeo = new MapeoRequest(0, 2, 0, 1, 2, List.of(0), 3, null, null, null, null, null, null, null,
                    null, null, null);

            service.aplicar(ARCHIVO, new AplicarRequest(PROVEEDOR, mapeo, false, null, null, null, false, null,
                    conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), null), USUARIO);

            MapeoLista guardado = proveedor.getMapeoLista();
            assertThat(guardado.hojas()).containsExactly(0);
            assertThat(guardado.columnaMoneda()).isEqualTo(3);
            assertThat(guardado.encabezados().moneda()).isEqualTo("Mon.");
            assertThat(guardado.encabezados().codigo()).isEqualTo("Codigo");
        }

        @Test
        @DisplayName("un mapeo viejo se vuelve a guardar sin perder campos viejos y sin inventar los nuevos")
        void mapeoViejoSeConserva() throws Exception {
            Proveedor proveedor = proveedor();
            proveedor.configurarLista(
                    new MapeoLista(0, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Descripcion", "Costo")),
                    false, Bonificaciones.NINGUNA);
            escenario(proveedor, tresQueSuben(), listaDeCuatro());
            guardaImportacionConId();

            service.aplicar(ARCHIVO, aplicarRequest(conteos(3, 0, 1, 0, 0, 0)), USUARIO);

            MapeoLista guardado = proveedor.getMapeoLista();
            assertThat(guardado).isEqualTo(
                    new MapeoLista(0, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Descripcion", "Costo")));
            assertThat(guardado.hojas()).isNull();
            assertThat(guardado.columnaMoneda()).isNull();
            assertThat(guardado.encabezados().moneda()).isNull();
        }

        private void dolarEn(String valor) {
            when(dolarDePrecios.vigente()).thenReturn(Optional.of(bd(valor)));
            when(dolarDePrecios.activa()).thenReturn(true);
        }
    }

    // ---- listar (GET /api/importaciones, T-142) ----

    @Nested
    @DisplayName("listar: el historial de importaciones de la ferreteria")
    class Listar {

        private ImportacionLista importacion(long id, long proveedorId, String archivo, ConteosImportacion conteos)
                throws Exception {
            ImportacionLista registro = ImportacionLista.aplicada(FERRETERIA, proveedorId, USUARIO, archivo, conteos);
            fijar(registro, "id", id);
            return registro;
        }

        private Proveedor proveedorConNombre(long id, String nombre) throws Exception {
            Proveedor proveedor = new Proveedor(FERRETERIA, nombre, null);
            fijar(proveedor, "id", id);
            return proveedor;
        }

        @Test
        @DisplayName("sin filtro devuelve todas en el orden del repositorio, con el nombre del proveedor y el usuario")
        void sinFiltro() throws Exception {
            when(proveedorRepository.findAllScoped())
                    .thenReturn(List.of(proveedorConNombre(7L, "Bulonera"), proveedorConNombre(8L, "Pinturas")));
            when(importacionListaRepository.listarScoped(null)).thenReturn(List.of(
                    importacion(30, 8, "nueva.xlsx", new ConteosImportacion(1, 2, 3, 4, 5, 6, 7, 8, 9)),
                    importacion(20, 7, "vieja.xlsx", new ConteosImportacion(0, 0, 0, 0, 0, 0))));

            List<ImportacionResponse> respuesta = service.listar(null);

            assertThat(respuesta).extracting(ImportacionResponse::id).containsExactly(30L, 20L);
            assertThat(respuesta).extracting(ImportacionResponse::proveedorNombre).containsExactly("Pinturas", "Bulonera");
            assertThat(respuesta).extracting(ImportacionResponse::usuarioId).containsOnly(USUARIO);
            assertThat(respuesta.get(0).archivo()).isEqualTo("nueva.xlsx");
            assertThat(respuesta.get(0).estado()).isEqualTo(EstadoImportacion.APLICADA);
            assertThat(respuesta.get(0).conteos()).isEqualTo(new ConteosImportacion(1, 2, 3, 4, 5, 6, 7, 8, 9));
        }

        @Test
        @DisplayName("con proveedorId filtra por ese proveedor, que tiene que ser de la ferreteria")
        void conFiltro() throws Exception {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.of(proveedorConNombre(PROVEEDOR, "Bulonera")));
            when(importacionListaRepository.listarScoped(PROVEEDOR)).thenReturn(
                    List.of(importacion(20, PROVEEDOR, "vieja.xlsx", new ConteosImportacion(0, 0, 0, 0, 0, 0))));

            List<ImportacionResponse> respuesta = service.listar(PROVEEDOR);

            assertThat(respuesta).extracting(ImportacionResponse::id).containsExactly(20L);
            assertThat(respuesta.get(0).proveedorNombre()).isEqualTo("Bulonera");
            verify(importacionListaRepository).listarScoped(PROVEEDOR);
        }

        @Test
        @DisplayName("un proveedor ajeno o inexistente es un 404 y no lista nada")
        void proveedorAjeno() {
            when(proveedorRepository.findScoped(PROVEEDOR)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listar(PROVEEDOR))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Proveedor no encontrado");
            verifyNoInteractions(importacionListaRepository);
        }

        @Test
        @DisplayName("sin importaciones devuelve la lista vacia")
        void sinImportaciones() {
            when(importacionListaRepository.listarScoped(null)).thenReturn(List.of());

            assertThat(service.listar(null)).isEmpty();
        }
    }

    private static ConteosRequest conteos9(int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError,
                                           int cambianMoneda, int corrigen, int salteadas) {
        return new ConteosRequest(suben, bajan, nuevos, sinCambio, noEstan, conError, cambianMoneda, corrigen, salteadas);
    }
}
