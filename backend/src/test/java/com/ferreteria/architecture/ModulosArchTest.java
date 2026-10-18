package com.ferreteria.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fronteras entre modulos (R-022). {@code ventas} y {@code stock} hablan con el catalogo SOLO por el puerto
 * {@code catalogo.api} (tipos del JDK): nunca por sus entidades, repositorios o servicios. {@code stock} no
 * conoce a {@code ventas}. Y todavia no existe {@code facturacion}: solo ese modulo hablara con ARCA y llega
 * en la fase 5.
 *
 * El modulo {@code cotizacion} (R-024) no conoce a catalogo, ventas ni stock; {@code catalogo} lo usa SOLO por
 * {@code cotizacion.api} (tipos del JDK). Y {@code ventas} y {@code stock} ni lo nombran: el mostrador y las ventas
 * leen el precio final en pesos guardado (R-021).
 *
 * Las reglas con {@code that()} fallan si no hay clases que cumplan el filtro (ArchUnit 1.x), asi que si
 * alguien renombra o borra {@code ventas} o {@code stock} el test se pone rojo en vez de pasar en vacio.
 */
class ModulosArchTest {

    private final JavaClasses clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ferreteria");

    @Test
    void ventasNoDependeDelCatalogoSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.ventas..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.catalogo..")
                        .and(not(resideInAPackage("com.ferreteria.catalogo.api.."))))
                .check(clases);
    }

    @Test
    void ventasNoDependeDeClientesSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.ventas..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.clientes..")
                        .and(not(resideInAPackage("com.ferreteria.clientes.api.."))))
                .check(clases);
    }

    @Test
    void clientesNoDependeDeVentasNiDeStock() {
        noClasses().that().resideInAPackage("com.ferreteria.clientes..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.stock.."))
                .check(clases);
    }

    @Test
    void stockNoDependeDelCatalogoSalvoPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.stock..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.catalogo..")
                        .and(not(resideInAPackage("com.ferreteria.catalogo.api.."))))
                .check(clases);
    }

    @Test
    void stockNoDependeDeVentas() {
        noClasses().that().resideInAPackage("com.ferreteria.stock..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.ventas.."))
                .check(clases);
    }

    @Test
    void cotizacionNoDependeDeCatalogoVentasNiStock() {
        noClasses().that().resideInAPackage("com.ferreteria.cotizacion..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.catalogo..", "com.ferreteria.ventas..", "com.ferreteria.stock.."))
                .check(clases);
    }

    @Test
    void catalogoUsaCotizacionSoloPorSuPuerto() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.cotizacion..")
                        .and(not(resideInAPackage("com.ferreteria.cotizacion.api.."))))
                .check(clases);
    }

    @Test
    void ventasYStockNoConocenLaCotizacion() {
        noClasses().that().resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.stock..")
                .should().dependOnClassesThat(resideInAPackage("com.ferreteria.cotizacion.."))
                .check(clases);
    }

    /**
     * {@code common} es la base que todos usan: no conoce a ningun modulo de negocio. Quedan fuera de la regla
     * {@code auth} y {@code tenant}, que son infraestructura transversal (el manejador global de errores traduce
     * {@code CredencialesInvalidasException} y {@code MissingTenantException}), no negocio.
     */
    @Test
    void commonNoDependeDeLosModulosDeNegocio() {
        noClasses().that().resideInAPackage("com.ferreteria.common..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.catalogo..", "com.ferreteria.ventas..", "com.ferreteria.stock..",
                        "com.ferreteria.cotizacion..", "com.ferreteria.ferreteria.."))
                .check(clases);
    }

    /**
     * La regla de arriba no alcanza: javac copia las constantes {@code static final String} en el sitio de uso, asi que
     * el bytecode de {@code common} no registra la dependencia con {@code ArticuloModificado} (lo comprobado al
     * escribirla: daba verde con el import mal puesto). Por eso se revisan tambien los {@code import} del codigo fuente.
     */
    @Test
    void commonNoImportaLosModulosDeNegocioEnElCodigoFuente() throws IOException {
        Pattern prohibido = Pattern.compile(
                "^import\\s+(static\\s+)?com\\.ferreteria\\.(catalogo|ventas|stock|cotizacion|ferreteria)\\.",
                Pattern.MULTILINE);
        try (Stream<Path> archivos = Files.walk(Path.of("src/main/java/com/ferreteria/common"))) {
            List<String> infractores = archivos.filter(a -> a.toString().endsWith(".java"))
                    .filter(a -> prohibido.matcher(leer(a)).find())
                    .map(Path::toString).toList();
            assertThat(infractores).isEmpty();
        }
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void todaviaNoExisteElPaqueteFacturacion() {
        noClasses().should().resideInAPackage("com.ferreteria.facturacion..").check(clases);
    }

    @Test
    void catalogoNoDependeDeVentasNiStock() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo..")
                .should().dependOnClassesThat(resideInAnyPackage("com.ferreteria.ventas..", "com.ferreteria.stock.."))
                .check(clases);
    }

    @Test
    void elDominioDelCatalogoNoDependeDeLaWebNiDeLosDtos() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo.model..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.ferreteria.catalogo.controller..", "com.ferreteria.catalogo.dto..",
                        "org.springframework.web..", "jakarta.servlet.."))
                .check(clases);
    }
}
