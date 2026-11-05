package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lee una lista Excel con {@link LectorExcel} en una JVM aparte con poca memoria (lo que cuesta una lectura no depende
 * del heap de la JVM de los tests). Imprime {@code FILAS <n>} o {@code 400 <mensaje>}; si se queda sin memoria, la JVM
 * termina con {@code -XX:+ExitOnOutOfMemoryError}, como el servidor (backend/Dockerfile).
 */
public final class LecturaEnJvmChica {

    private LecturaEnJvmChica() {
    }

    public static void main(String[] args) throws Exception {
        try {
            LecturaLista lectura = new LectorExcel().leer(Files.readAllBytes(Path.of(args[0])));
            System.out.println("FILAS " + lectura.filas().size());
        } catch (BadRequestException e) {
            System.out.println("400 " + e.getMessage());
        }
    }

    /** Lo que imprimio la JVM chica y su codigo de salida. */
    record Resultado(int salida, String texto) {
    }

    /** Corre la lectura de {@code archivo} en una JVM con {@code heap} (por ejemplo "256m"). */
    static Resultado leer(Path archivo, String heap) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> comando = new ArrayList<>(List.of(java, "-Xmx" + heap, "-XX:+ExitOnOutOfMemoryError",
                "-cp", System.getProperty("java.class.path"), LecturaEnJvmChica.class.getName(), archivo.toString()));
        Path salida = Files.createTempFile("lectura-jvm-chica", ".txt");
        try {
            Process proceso = new ProcessBuilder(comando).redirectErrorStream(true).redirectOutput(salida.toFile())
                    .start();
            if (!proceso.waitFor(3, TimeUnit.MINUTES)) {
                proceso.destroyForcibly();
                throw new AssertionError("La lectura en la JVM chica no termino en 3 minutos");
            }
            return new Resultado(proceso.exitValue(), Files.readString(salida));
        } finally {
            Files.deleteIfExists(salida);
        }
    }
}
