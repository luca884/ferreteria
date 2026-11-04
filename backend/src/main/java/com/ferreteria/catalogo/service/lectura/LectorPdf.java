package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lee un PDF con texto: {@link ExtractorPdf} saca las palabras con su posicion y {@link TablaDePdf} arma las filas y las
 * columnas. Un PDF es una sola hoja, la 0, con todas las paginas seguidas. Un PDF escaneado no tiene texto y no se puede
 * leer.
 *
 * <p>PDFBox no tiene topes propios: un PDF armado a proposito puede agotar la memoria o la CPU de quien lo lee. Por eso
 * el PDF se lee en una JVM aparte, con su memoria y su tiempo ({@link Topes}); si los pasa, se la mata y la lista es
 * demasiado grande, sin tocar al servidor compartido. Se lee un PDF por vez, y cada ferreteria espera uno solo. En el jar empaquetado de Spring Boot la JVM
 * aparte arranca con su {@code PropertiesLauncher}; con un classpath comun (tests, {@code spring-boot:run}), directo.
 */
@Component
public class LectorPdf implements LectorDeLista {

    private static final Logger log = LoggerFactory.getLogger(LectorPdf.class);

    static final int MAXIMO_PALABRAS = 500_000;

    /**
     * Topes de la JVM que lee: palabras que devuelve, memoria (como {@code -Xmx}), tiempo para leer y espera por turno.
     */
    record Topes(int palabras, String memoria, Duration tiempo, Duration espera) {
    }

    static final Topes TOPES = new Topes(MAXIMO_PALABRAS, "256m", Duration.ofSeconds(30), Duration.ofSeconds(30));

    private static final String HOJA = "PDF";
    private static final String ARCHIVO_DANADO = "No se pudo leer el archivo. Verificá que no esté dañado.";
    private static final String SIN_TEXTO =
        "El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no sea escaneado.";
    private static final String OCUPADO = "Ya se está leyendo otra lista en PDF. Probá de nuevo en un momento.";
    private static final String LANZADOR_DEL_JAR = "org.springframework.boot.loader.launch.PropertiesLauncher";
    /** Opciones del entorno que la JVM suma a las de la linea de comandos (y {@code _JAVA_OPTIONS} las pisa). */
    private static final List<String> OPCIONES_DEL_ENTORNO = List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS");

    /** Donde suele estar {@code nice}; con una ruta fija, como el {@code java}, el PATH no elige que se ejecuta. */
    private static final List<Path> NICE = List.of(Path.of("/usr/bin/nice"), Path.of("/bin/nice"));

    private final Topes topes;
    private final Class<?> principal;
    private final Optional<Path> nice;
    private final TurnoDeLectura turno;

    public LectorPdf() {
        this(TOPES, ExtractorPdf.class);
    }

    /** Con otros topes o con otro proceso en lugar de {@link ExtractorPdf}, para probarlos. */
    LectorPdf(Topes topes, Class<?> principal) {
        this(topes, principal, NICE.stream().filter(Files::isExecutable).findFirst());
    }

    /** Sin {@code nice} (una maquina que no lo tiene), para probar que la JVM aparte arranca igual. */
    LectorPdf(Topes topes, Class<?> principal, Optional<Path> nice) {
        this.topes = topes;
        this.principal = principal;
        this.nice = nice;
        this.turno = new TurnoDeLectura(topes.espera(), OCUPADO);
    }

    @Override
    public LecturaLista leer(byte[] contenido) {
        List<FilaLeida> filas = TablaDePdf.armar(palabras(contenido));
        return new LecturaLista(List.of(HOJA), filas);
    }

    /**
     * Las palabras con posicion de un PDF con texto, leidas en la JVM aparte con sus topes y su turno. Es el camino que
     * comparten la lista de precios y {@link LectorDeFacturaPdf}: un solo turno y los mismos avisos para un PDF
     * escaneado, protegido, danado o demasiado grande.
     */
    List<PalabraPdf> palabras(byte[] contenido) {
        // Un PDF por vez, y cada ferreteria espera uno solo (TurnoDeLectura).
        List<PalabraPdf> palabras = turno.conTurno(() -> leerAparte(contenido));
        if (palabras.isEmpty()) {
            throw new BadRequestException(SIN_TEXTO);
        }
        return palabras;
    }

    private List<PalabraPdf> leerAparte(byte[] contenido) {
        Path pdf = null;
        Path palabras = null;
        try {
            pdf = Files.createTempFile("lista-", ".pdf");
            palabras = Files.createTempFile("lista-", ".palabras");
            Files.write(pdf, contenido);
            ProcessBuilder proceso = new ProcessBuilder(comando(pdf, palabras))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> entorno = proceso.environment();
            OPCIONES_DEL_ENTORNO.forEach(entorno::remove);
            int salida = esperar(proceso.start());
            return switch (salida) {
                case ExtractorPdf.SALIDA_BIEN -> palabrasDe(palabras);
                case ExtractorPdf.SALIDA_CON_CONTRASENA ->
                    throw new BadRequestException("El archivo está protegido con contraseña.");
                case ExtractorPdf.SALIDA_DEMASIADO_GRANDE, ExtractorPdf.SALIDA_SIN_MEMORIA ->
                    throw LimitesDeLista.demasiadoGrande();
                default -> {
                    // Un PDF que hace caer a PDFBox, o un proceso que ni arranca: sin el codigo no se distinguen.
                    log.warn("El proceso que lee el PDF terminó con el código {}", salida);
                    throw new BadRequestException(ARCHIVO_DANADO);
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo lanzar el proceso que lee el PDF", e);
        } finally {
            borrar(pdf);
            borrar(palabras);
        }
    }

    /**
     * La JVM aparte corre con la prioridad mas baja y ve un solo procesador: si un PDF la tiene ocupada, el servidor le
     * gana la CPU. Un tope duro de CPU (cgroups) no se puede poner desde aca; el de tiempo sigue.
     */
    private List<String> comando(Path pdf, Path palabras) {
        List<String> comando = new ArrayList<>();
        nice.ifPresent(ruta -> comando.addAll(List.of(ruta.toString(), "-n", "19")));
        comando.addAll(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx" + topes.memoria(),
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+UseSerialGC",
            "-XX:ActiveProcessorCount=1",
            "-Djava.awt.headless=true",
            "-Dlogback.configurationFile=lector-pdf-logback.xml",
            "-cp", System.getProperty("java.class.path")));
        if (enJarEmpaquetado()) {
            comando.add("-Dloader.main=" + principal.getName());
            comando.add(LANZADOR_DEL_JAR);
        } else {
            comando.add(principal.getName());
        }
        comando.addAll(List.of(pdf.toString(), palabras.toString(), String.valueOf(topes.palabras()),
            String.valueOf(topes.tiempo().toSeconds())));
        return comando;
    }

    /** En el jar de Spring Boot las clases de la app no estan en el classpath de la JVM sino adentro del jar. */
    private static boolean enJarEmpaquetado() {
        return ClassLoader.getSystemClassLoader().getResource(LANZADOR_DEL_JAR.replace('.', '/') + ".class") != null;
    }

    /** La salida del proceso; si no termina a tiempo, se lo mata y la lista es demasiado grande. */
    private int esperar(Process proceso) {
        try {
            if (proceso.waitFor(topes.tiempo().toMillis(), TimeUnit.MILLISECONDS)) {
                return proceso.exitValue();
            }
            proceso.destroyForcibly().waitFor();
            return ExtractorPdf.SALIDA_DEMASIADO_GRANDE;
        } catch (InterruptedException e) {
            proceso.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la lectura del PDF", e);
        }
    }

    private List<PalabraPdf> palabrasDe(Path palabras) {
        try {
            return ExtractorPdf.leer(palabras, topes.palabras());
        } catch (IOException e) {
            throw new BadRequestException(ARCHIVO_DANADO);
        }
    }

    private static void borrar(Path archivo) {
        if (archivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            archivo.toFile().deleteOnExit();
        }
    }
}
