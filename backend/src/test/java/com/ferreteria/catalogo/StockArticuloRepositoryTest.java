package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.dto.AplicarActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.AplicarRequest;
import com.ferreteria.catalogo.dto.ActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.ArticuloRequest;
import com.ferreteria.catalogo.dto.ConteosRequest;
import com.ferreteria.catalogo.dto.MapeoRequest;
import com.ferreteria.catalogo.dto.VistaPreviaRequest;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.ArchivoLista;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.service.ActualizacionMasivaService;
import com.ferreteria.catalogo.service.ArticuloService;
import com.ferreteria.catalogo.service.ImportacionService;
import com.ferreteria.catalogo.service.RecalculoPreciosService;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.ListasDePrueba;
import com.ferreteria.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stock atomico (R-014, C-003): el descuento es un UPDATE relativo con scope de ferreteria y ningun
 * escritor de la entidad puede pisar stock_actual con un valor viejo. La carrera se arma sin hilos:
 * la entidad se carga en una transaccion (stock 10), otra transaccion REQUIRES_NEW descuenta 3 y
 * confirma, y la primera guarda lo suyo. El stock tiene que quedar en 7.
 */
class StockArticuloRepositoryTest extends IntegrationTestBase {

    private static final ContextoPrecio CONTEXTO = new ContextoPrecio(null, new BigDecimal("30.00"), new BigDecimal("10.00"));

    private static final java.util.concurrent.atomic.AtomicInteger SECUENCIA = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    ArticuloRepository articuloRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    ArticuloService articuloService;

    @Autowired
    RecalculoPreciosService recalculoPreciosService;

    @Autowired
    ActualizacionMasivaService actualizacionMasivaService;

    @Autowired
    ImportacionService importacionService;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- el UPDATE nativo ----

    @Test
    @DisplayName("sumarStock escribe stock_actual aunque el atributo sea updatable=false")
    void sumarStockEscribe() {
        Datos f = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());

        int filas = enTransaccion(() -> articuloRepository.sumarStockScoped(f.articuloId(), new BigDecimal("-3")));

        assertThat(filas).isEqualTo(1);
        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("sumarStock acepta deltas positivos y decimales (3 decimales)")
    void sumarStockAceptaPositivosYDecimales() {
        Datos f = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());

        enTransaccion(() -> articuloRepository.sumarStockScoped(f.articuloId(), new BigDecimal("2.500")));
        enTransaccion(() -> articuloRepository.sumarStockScoped(f.articuloId(), new BigDecimal("-0.001")));

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("12.499");
    }

    @Test
    @DisplayName("un id de otra ferreteria afecta 0 filas y no se descuenta")
    void sumarStockNoTocaArticulosAjenos() {
        Datos a = ferreteriaConArticulo("10");
        Datos b = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(b.ferreteriaId());

        int filas = enTransaccion(() -> articuloRepository.sumarStockScoped(a.articuloId(), new BigDecimal("-3")));

        assertThat(filas).isZero();
        assertThat(stockEnBase(a.articuloId())).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("el metamodelo marca stockActual como no actualizable y el resto de los campos si")
    void stockActualNoEsActualizable() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EntityPersister persister = sessionFactory.getMappingMetamodel().getEntityDescriptor(Articulo.class);
        boolean[] actualizable = persister.getPropertyUpdateability();
        String[] nombres = persister.getPropertyNames();

        assertThat(actualizable[indiceDe(nombres, "stockActual")]).isFalse();
        assertThat(actualizable[indiceDe(nombres, "descripcion")]).isTrue();
        assertThat(actualizable[indiceDe(nombres, "stockMinimo")]).isTrue();
    }

    @Test
    @DisplayName("porIdsScoped trae solo los de la ferreteria, por id ascendente")
    void porIdsScoped() {
        Datos a = ferreteriaConArticulo("10");
        Long segundo = insertarArticulo(a.ferreteriaId(), "SEG-1", "5");
        Datos b = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(a.ferreteriaId());

        List<Articulo> articulos = enTransaccion(() -> articuloRepository.porIdsScoped(
                List.of(segundo, b.articuloId(), a.articuloId())));

        assertThat(articulos).extracting(Articulo::getId).containsExactly(a.articuloId(), segundo);
    }

    @Test
    @DisplayName("stockDeScoped devuelve el stock actual por id y omite los ajenos")
    void stockDeScoped() {
        Datos a = ferreteriaConArticulo("10");
        Long segundo = insertarArticulo(a.ferreteriaId(), "SEG-2", "2.500");
        Datos b = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(a.ferreteriaId());

        enTransaccion(() -> articuloRepository.sumarStockScoped(a.articuloId(), new BigDecimal("-4")));
        var stock = enTransaccion(() -> articuloRepository.stockDeScoped(
                List.of(a.articuloId(), segundo, b.articuloId())));

        assertThat(stock).containsOnlyKeys(a.articuloId(), segundo);
        assertThat(stock.get(a.articuloId())).isEqualByComparingTo("6");
        assertThat(stock.get(segundo)).isEqualByComparingTo("2.5");
    }

    // ---- la carrera: la entidad vieja flusheada no pisa el stock ----

    /** Cada escritor de la entidad: que hace sobre el articulo viejo y que columna tiene que cambiar. */
    record Escritor(String nombre, BiConsumer<Articulo, Long> muta, String columna) {
        @Override
        public String toString() {
            return nombre;
        }
    }

    static List<Escritor> escritores() {
        return List.of(
                new Escritor("actualizar", (a, f) -> a.actualizar(new DatosArticulo(
                        a.getCodigoInterno(), null, null, "Descripcion nueva", null, null, a.getCosto(), null,
                        AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true), CONTEXTO), "descripcion"),
                new Escritor("actualizarCosto", (a, f) ->
                        a.actualizarCosto(new BigDecimal("250.00"), new BigDecimal("300.00"), CONTEXTO), "costo"),
                new Escritor("ajustarCosto", (a, f) -> a.ajustarCosto(new BigDecimal("260.00"), CONTEXTO), "costo"),
                new Escritor("fijarMargen", (a, f) -> a.fijarMargen(new BigDecimal("50.00"), CONTEXTO),
                        "margen_porcentaje"),
                new Escritor("recalcularPrecio", (a, f) -> a.recalcularPrecio(
                        new ContextoPrecio(null, new BigDecimal("100.00"), new BigDecimal("10.00"))), "precio_final"),
                new Escritor("desactivar", (a, f) -> a.desactivar(), "activo"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escritores")
    @DisplayName("una entidad cargada antes de la venta y guardada despues no pisa el stock")
    void laEntidadViejaNoPisaElStock(Escritor escritor) {
        Datos f = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());
        Object antes = columnaEnBase(f.articuloId(), escritor.columna());

        enTransaccion(() -> {
            Articulo viejo = articuloRepository.findScoped(f.articuloId()).orElseThrow();
            assertThat(viejo.getStockActual()).isEqualByComparingTo("10");
            descontarEnOtraTransaccion(f, "3");
            escritor.muta().accept(viejo, f.ferreteriaId());
            return null;
        });

        assertThat(stockEnBase(f.articuloId())).as("stock tras guardar la entidad vieja").isEqualByComparingTo("7");
        assertThat(columnaEnBase(f.articuloId(), escritor.columna())).as(escritor.columna() + " se guardo")
                .isNotEqualTo(antes);
    }

    // ---- la carrera con cada escritor externo, pasando por el servicio ----

    @Test
    @DisplayName("ArticuloService.actualizar: editar la descripcion mientras se vende no pisa el stock")
    void articuloServiceActualizarNoPisaElStock() {
        Datos f = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());

        enTransaccion(() -> {
            articuloRepository.findScoped(f.articuloId()).orElseThrow();
            descontarEnOtraTransaccion(f, "3");
            articuloService.actualizar(f.articuloId(), requestDeEdicion(f, "Editado durante la venta"), f.usuarioId());
            return null;
        });

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("7");
        assertThat(columnaEnBase(f.articuloId(), "descripcion")).isEqualTo("Editado durante la venta");
    }

    @Test
    @DisplayName("ArticuloService.desactivar: dar de baja mientras se vende no pisa el stock")
    void articuloServiceDesactivarNoPisaElStock() {
        Datos f = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());

        enTransaccion(() -> {
            articuloRepository.findScoped(f.articuloId()).orElseThrow();
            descontarEnOtraTransaccion(f, "3");
            articuloService.desactivar(f.articuloId());
            return null;
        });

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("7");
        assertThat(columnaEnBase(f.articuloId(), "activo")).isEqualTo(false);
    }

    @Test
    @DisplayName("RecalculoPreciosService: la cascada de margen de rubro no pisa el stock")
    void recalculoPreciosNoPisaElStock() {
        Datos f = ferreteriaConArticulo("10");
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, 'Rubro race', 30.00)",
                f.ferreteriaId());
        Long rubroId = jdbc.queryForObject(
                "select id from rubro where ferreteria_id = ?", Long.class, f.ferreteriaId());
        jdbc.update("update articulo set rubro_id = ?, margen_porcentaje = null where id = ?", rubroId, f.articuloId());
        Object precioAntes = columnaEnBase(f.articuloId(), "precio_final");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());

        enTransaccion(() -> {
            articuloRepository.findScoped(f.articuloId()).orElseThrow();
            descontarEnOtraTransaccion(f, "3");
            recalculoPreciosService.porCambioDeMargenDeRubro(
                    new RubroConId(f.ferreteriaId(), rubroId, new BigDecimal("100.00")).rubro(), f.usuarioId());
            return null;
        });

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("7");
        assertThat(columnaEnBase(f.articuloId(), "precio_final")).isNotEqualTo(precioAntes);
    }

    @Test
    @DisplayName("ActualizacionMasivaService: aplicar un cambio de costo no pisa el stock")
    void actualizacionMasivaNoPisaElStock() {
        Datos f = ferreteriaConArticulo("10");
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());
        Object costoAntes = columnaEnBase(f.articuloId(), "costo");

        enTransaccion(() -> {
            articuloRepository.findScoped(f.articuloId()).orElseThrow();
            descontarEnOtraTransaccion(f, "3");
            ActualizacionMasivaRequest previa = new ActualizacionMasivaRequest(
                    null, null, f.codigoInterno(), false, "COSTO_PORCENTAJE", new BigDecimal("10"));
            String huella = actualizacionMasivaService.vistaPrevia(previa).huella();
            actualizacionMasivaService.aplicar(new AplicarActualizacionMasivaRequest(
                    null, null, f.codigoInterno(), false, "COSTO_PORCENTAJE", new BigDecimal("10"), huella),
                    f.usuarioId());
            return null;
        });

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("7");
        assertThat(columnaEnBase(f.articuloId(), "costo")).isNotEqualTo(costoAntes);
    }

    @Test
    @DisplayName("ImportacionService: aplicar una lista no pisa el stock")
    void importacionNoPisaElStock() {
        Datos f = ferreteriaConArticulo("10");
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor race')", f.ferreteriaId());
        Long proveedorId = jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ?", Long.class, f.ferreteriaId());
        jdbc.update("update articulo set proveedor_id = ?, codigo_proveedor = ? where id = ?",
                proveedorId, "PROV-" + f.codigoInterno(), f.articuloId());
        ArchivoLista lista = new ArchivoLista("lista.xlsx", ListasDePrueba.xlsx(List.of(
                List.of("Codigo", "Descripcion", "Costo"),
                java.util.Arrays.asList("PROV-" + f.codigoInterno(), "Con otro costo", new BigDecimal("500.00")))));
        MapeoRequest mapeo = new MapeoRequest(0, 2, 0, 1, 2);
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());
        Object costoAntes = columnaEnBase(f.articuloId(), "costo");

        enTransaccion(() -> {
            articuloRepository.findScoped(f.articuloId()).orElseThrow();
            descontarEnOtraTransaccion(f, "3");
            ConteosImportacion c = importacionService.vistaPrevia(
                    lista, new VistaPreviaRequest(proveedorId, mapeo, false, null, null, null)).conteos();
            importacionService.aplicar(lista, new AplicarRequest(proveedorId, mapeo, false, null, null, null, false, null,
                    new ConteosRequest(c.suben(), c.bajan(), c.nuevos(), c.sinCambio(), c.noEstan(), c.conError())),
                    f.usuarioId());
            return null;
        });

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("7");
        assertThat(columnaEnBase(f.articuloId(), "costo")).isNotEqualTo(costoAntes);
    }

    // ---- ventas simultaneas ----

    @Test
    @DisplayName("dos ventas simultaneas de 3 sobre stock 10 dejan 4")
    void dosDescuentosSimultaneosNoSePierden() throws Exception {
        Datos f = ferreteriaConArticulo("10");
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> resultados = List.of(
                    pool.submit(() -> descontarSimultaneo(f, salida)),
                    pool.submit(() -> descontarSimultaneo(f, salida)));
            salida.countDown();
            for (Future<Integer> resultado : resultados) {
                assertThat(resultado.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(stockEnBase(f.articuloId())).isEqualByComparingTo("4");
    }

    private int descontarSimultaneo(Datos f, CountDownLatch salida) throws InterruptedException {
        TenantContext.setCurrentFerreteriaId(f.ferreteriaId());
        try {
            salida.await();
            return enTransaccion(() -> articuloRepository.sumarStockScoped(f.articuloId(), new BigDecimal("-3")));
        } finally {
            TenantContext.clear();
        }
    }

    // ---- apoyo ----

    /** Una "venta" aparte: transaccion nueva que descuenta y confirma antes de que la primera guarde. */
    private void descontarEnOtraTransaccion(Datos f, String cantidad) {
        TransactionTemplate nueva = new TransactionTemplate(transactionManager);
        nueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer filas = nueva.execute(status ->
                articuloRepository.sumarStockScoped(f.articuloId(), new BigDecimal("-" + cantidad)));
        assertThat(filas).isEqualTo(1);
    }

    private <T> T enTransaccion(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(transactionManager).execute(status -> trabajo.get());
    }

    private ArticuloRequest requestDeEdicion(Datos f, String descripcion) {
        return new ArticuloRequest(f.codigoInterno(), null, null, descripcion, null, null, new BigDecimal("100.00"),
                null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, null);
    }

    private static int indiceDe(String[] nombres, String nombre) {
        return List.of(nombres).indexOf(nombre);
    }

    private BigDecimal stockEnBase(Long articuloId) {
        return jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, articuloId);
    }

    private Object columnaEnBase(Long articuloId, String columna) {
        Object valor = jdbc.queryForObject("select " + columna + " from articulo where id = ?", Object.class, articuloId);
        return valor instanceof BigDecimal d ? d.stripTrailingZeros() : valor;
    }

    private record Datos(Long ferreteriaId, Long usuarioId, Long articuloId, String codigoInterno) {}

    private record RubroConId(Long ferreteriaId, Long id, BigDecimal margen) {
        Rubro rubro() {
            Rubro rubro = new Rubro(ferreteriaId, "Rubro race", margen);
            try {
                var campo = Rubro.class.getDeclaredField("id");
                campo.setAccessible(true);
                campo.set(rubro, id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return rubro;
        }
    }

    private Datos ferreteriaConArticulo(String stock) {
        String cuit = String.format("30-%08d-%d", 79500000 + SECUENCIA.incrementAndGet(), 1);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Stock " + cuit, cuit);
        Long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "stock-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno', 'DUENO')", ferreteriaId, email);
        Long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        String codigo = "STK-" + ferreteriaId;
        Long articuloId = insertarArticulo(ferreteriaId, codigo, stock);
        return new Datos(ferreteriaId, usuarioId, articuloId, codigo);
    }

    private Long insertarArticulo(Long ferreteriaId, String codigoInterno, String stock) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, stock_actual) "
                + "values (?, ?, 'Articulo de stock', 100.00, 170.00, ?)", ferreteriaId, codigoInterno, stock);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigoInterno);
    }
}
