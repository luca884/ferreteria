package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.io.ByteArrayOutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import jakarta.persistence.EntityManagerFactory;

/**
 * MEDICION MANUAL de la importacion con las listas REALES (T-157 / T-158). No es parte de la suite: el nombre no
 * termina en Test (surefire no lo toma) y ademas solo corre si se pasa {@code -Dmedicion.listas=/ruta/a/Lista}.
 * Las listas estan en .gitignore y NO se suben a git.
 *
 * <pre>
 * cd backend
 * # tiempos de LISTA 336 (crear todo y despues actualizar todo)
 * ./mvnw test -Dtest='MedicionImportacionReal#lista336*' -Dmedicion.listas=/ruta/a/Lista
 * # memoria del .xls de M-S con un heap acotado
 * ./mvnw test -Dtest='MedicionImportacionReal#msXls*' -Dmedicion.listas=/ruta/a/Lista -DargLine=-Xmx256m
 * </pre>
 *
 * Usa MockMvc contra MySQL real (Testcontainers): pasa por el filtro de seguridad, la lectura, el planificador y la
 * transaccion real. Imprime lineas {@code MEDICION ...}; no afirma tiempos, solo que los pedidos respondan.
 * CUIT propios: 30-78300010-x.
 */
@EnabledIfSystemProperty(named = "medicion.listas", matches = ".+")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MedicionImportacionReal extends CotizacionTestBase {

    private static final String MUESTRA = "/api/importaciones/muestra";
    private static final String VISTA_PREVIA = "/api/importaciones/vista-previa";
    private static final String APLICAR = "/api/importaciones";
    private static final Instant HACE_UN_RATO = Instant.now().minusSeconds(600);

    @Autowired
    private EntityManagerFactory emf;

    private Long ferreteria;
    private String dueno;
    private long proveedor;

    @BeforeEach
    void sembrar() throws Exception {
        ferreteria = ferreteria("30-78300010-1", "Medicion importacion real");
        borrarCotizaciones("t83-");
        cotizacion("USD", "t83-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(ferreteria, c -> {
            c.setCasaPrecios("t83-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t83-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        dueno = login(usuario(ferreteria, "dueno-t83@test.local", "DUENO"));
        String body = postJson("/api/proveedores", dueno, "{\"nombre\":\"" + unico("Real") + "\"}")
                .andReturn().getResponse().getContentAsString();
        proveedor = objectMapper.readTree(body).get("id").asLong();
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t83-");
        borrarDatosDe(ferreteria);
    }

    // ---- medidor ----

    private record Medida(long milis, double picoMb, double baseMb, long gcMilis, long gcCuenta) {
        String texto() {
            return String.format("%d ms | heap base %.0f MB, pico muestreado %.0f MB (+%.0f MB) | GC %d veces, %d ms",
                    milis, baseMb, picoMb, picoMb - baseMb, gcCuenta, gcMilis);
        }
    }

    private static double usadoMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / 1_048_576.0;
    }

    private static long[] gc() {
        long ms = 0;
        long cuenta = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            ms += Math.max(0, b.getCollectionTime());
            cuenta += Math.max(0, b.getCollectionCount());
        }
        return new long[] {ms, cuenta};
    }

    /** Corre la accion con un hilo que muestrea el heap usado cada 5 ms. */
    private static <T> Object[] medir(Callable<T> accion) throws Exception {
        System.gc();
        double base = usadoMb();
        long[] gcAntes = gc();
        AtomicLong picoKb = new AtomicLong((long) (base * 1024));
        Thread muestreador = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                picoKb.accumulateAndGet((long) (usadoMb() * 1024), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        muestreador.setDaemon(true);
        muestreador.start();
        long inicio = System.nanoTime();
        T resultado;
        try {
            resultado = accion.call();
        } finally {
            muestreador.interrupt();
        }
        long milis = (System.nanoTime() - inicio) / 1_000_000;
        long[] gcDespues = gc();
        return new Object[] {resultado,
            new Medida(milis, picoKb.get() / 1024.0, base, gcDespues[0] - gcAntes[0], gcDespues[1] - gcAntes[1])};
    }

    private static int gzip(byte[] datos) throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(salida)) {
            gz.write(datos);
        }
        return salida.size();
    }

    private static void linea(String texto) {
        System.out.println("MEDICION " + texto);
    }

    private Statistics estadisticas() {
        Statistics s = emf.unwrap(SessionFactory.class).getStatistics();
        return s;
    }

    private String sql() {
        Statistics s = estadisticas();
        return String.format("inserts=%d updates=%d sentencias=%d transacciones=%d",
                s.getEntityInsertCount(), s.getEntityUpdateCount(), s.getPrepareStatementCount(),
                s.getTransactionCount());
    }

    // ---- pedidos ----

    private record Respuesta(int estado, String cuerpo) {
        JsonNode json(com.fasterxml.jackson.databind.ObjectMapper om) throws Exception {
            return om.readTree(cuerpo);
        }
    }

    private Respuesta pedir(ResultActions resultado) throws Exception {
        var r = resultado.andReturn().getResponse();
        return new Respuesta(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    private Respuesta muestra(byte[] contenido, String nombre) throws Exception {
        return pedir(postMultipart(MUESTRA, dueno, archivo(nombre, contenido), null));
    }

    private ObjectNode datosDe(JsonNode propuesta) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedor).put("listaIncluyeIva", false);
        datos.set("mapeo", propuesta);
        return datos;
    }

    private Respuesta vistaPrevia(byte[] contenido, String nombre, ObjectNode datos) throws Exception {
        return pedir(postMultipart(VISTA_PREVIA, dueno, archivo(nombre, contenido), datos));
    }

    private Respuesta aplicar(byte[] contenido, String nombre, ObjectNode datos, JsonNode conteos) throws Exception {
        ObjectNode aplicar = datos.deepCopy();
        aplicar.put("crearNuevos", true);
        aplicar.set("conteosEsperados", conteos);
        return pedir(postMultipart(APLICAR, dueno, archivo(nombre, contenido), aplicar));
    }

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void resumen(String que, Respuesta r) throws Exception {
        if (r.estado() >= 300) {
            linea(que + " -> HTTP " + r.estado() + " " + r.cuerpo().substring(0, Math.min(300, r.cuerpo().length())));
            return;
        }
        JsonNode j = r.json(objectMapper);
        linea(que + " -> HTTP " + r.estado() + " | json " + r.cuerpo().getBytes(StandardCharsets.UTF_8).length
                + " bytes, gzip " + gzip(r.cuerpo().getBytes(StandardCharsets.UTF_8)) + " bytes"
                + (j.has("conteos") ? " | conteos " + j.get("conteos") : ""));
    }

    // ---- LISTA 336 ----

    @Test
    void lista336CreaTodoYLuegoActualizaTodo() throws Exception {
        Path ruta = Path.of(System.getProperty("medicion.listas"), "LISTA 336.xlsx");
        byte[] contenido = Files.readAllBytes(ruta);
        linea("archivo " + ruta.getFileName() + " " + contenido.length + " bytes; maxMemory "
                + Runtime.getRuntime().maxMemory() / 1_048_576 + " MB");

        Object[] m = medir(() -> muestra(contenido, "LISTA 336.xlsx"));
        Respuesta muestra = (Respuesta) m[0];
        linea("muestra: " + ((Medida) m[1]).texto());
        resumen("muestra", muestra);
        JsonNode propuesta = muestra.json(objectMapper).get("propuesta");
        linea("hojas=" + muestra.json(objectMapper).get("hojas").size() + " propuesta=" + propuesta);
        ObjectNode datos = datosDe(propuesta);

        // --- pasada 1: crear todo ---
        m = medir(() -> vistaPrevia(contenido, "LISTA 336.xlsx", datos));
        Respuesta previa1 = (Respuesta) m[0];
        linea("vista previa 1 (todo nuevo): " + ((Medida) m[1]).texto());
        resumen("vista previa 1", previa1);
        JsonNode conteos1 = previa1.json(objectMapper).get("conteos");
        estadisticas().clear();
        m = medir(() -> aplicar(contenido, "LISTA 336.xlsx", datos, conteos1));
        linea("APLICAR 1 (crea todo): " + ((Medida) m[1]).texto() + " | " + sql());
        resumen("aplicar 1", (Respuesta) m[0]);
        long articulos = contar("select count(*) from articulo where ferreteria_id = ?", ferreteria);
        linea("articulos creados=" + articulos + " historial="
                + contar("select count(*) from historial_precio where ferreteria_id = ?", ferreteria)
                + " en USD=" + contar("select count(*) from articulo where ferreteria_id = ? and moneda = 'USD'", ferreteria));

        // --- pasada 2: actualizar todo (se baja el costo y el precio a la mitad: la lista sube todo) ---
        jdbc.update("update articulo set costo = round(costo / 2, 2), precio_final = round(precio_final / 2, 0) "
                + "where ferreteria_id = ?", ferreteria);
        m = medir(() -> vistaPrevia(contenido, "LISTA 336.xlsx", datos));
        Respuesta previa2 = (Respuesta) m[0];
        linea("vista previa 2 (todo existente): " + ((Medida) m[1]).texto());
        resumen("vista previa 2", previa2);
        JsonNode conteos2 = previa2.json(objectMapper).get("conteos");
        estadisticas().clear();
        m = medir(() -> aplicar(contenido, "LISTA 336.xlsx", datos, conteos2));
        linea("APLICAR 2 (actualiza todo): " + ((Medida) m[1]).texto() + " | " + sql());
        resumen("aplicar 2", (Respuesta) m[0]);
        linea("historial IMPORTACION=" + contar(
                "select count(*) from historial_precio where ferreteria_id = ? and origen = 'IMPORTACION'", ferreteria)
                + " con ultima_importacion_id=" + contar(
                "select count(*) from articulo where ferreteria_id = ? and ultima_importacion_id is not null", ferreteria));
    }

    // ---- M-S .xls ----

    @Test
    void msXlsMemoriaDeMuestraVistaPreviaYAplicar() throws Exception {
        Path ruta = Path.of(System.getProperty("medicion.listas"),
                "Lista Precios M-S Distribuidora Mayorista 02-10-2026.xls");
        byte[] contenido = Files.readAllBytes(ruta);
        linea("archivo " + ruta.getFileName() + " " + contenido.length + " bytes; maxMemory "
                + Runtime.getRuntime().maxMemory() / 1_048_576 + " MB");

        Respuesta muestra;
        try {
            Object[] m = medir(() -> muestra(contenido, "ms.xls"));
            muestra = (Respuesta) m[0];
            linea("muestra: " + ((Medida) m[1]).texto());
            resumen("muestra", muestra);
        } catch (Throwable t) {
            linea("muestra FALLO: " + t);
            return;
        }
        if (muestra.estado() >= 300) {
            return;
        }
        JsonNode propuesta = muestra.json(objectMapper).get("propuesta");
        linea("hojas=" + muestra.json(objectMapper).get("hojas").size() + " propuesta=" + propuesta);
        ObjectNode datos = datosDe(propuesta);

        Respuesta previa;
        try {
            Object[] m = medir(() -> vistaPrevia(contenido, "ms.xls", datos));
            previa = (Respuesta) m[0];
            linea("vista previa: " + ((Medida) m[1]).texto());
            resumen("vista previa", previa);
        } catch (Throwable t) {
            linea("vista previa FALLO: " + t);
            return;
        }
        if (previa.estado() >= 300) {
            return;
        }
        try {
            Object[] m = medir(() -> aplicar(contenido, "ms.xls", datos, previa.json(objectMapper).get("conteos")));
            linea("APLICAR: " + ((Medida) m[1]).texto() + " | " + sql());
            resumen("aplicar", (Respuesta) m[0]);
            linea("articulos creados=" + contar("select count(*) from articulo where ferreteria_id = ?", ferreteria));
        } catch (Throwable t) {
            linea("aplicar FALLO: " + t);
        }
    }
}
