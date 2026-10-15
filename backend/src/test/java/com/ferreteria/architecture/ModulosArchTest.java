package com.ferreteria.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fronteras entre modulos (R-022). {@code ventas} y {@code stock} hablan con el catalogo SOLO por el puerto
 * {@code catalogo.api} (tipos del JDK): nunca por sus entidades, repositorios o servicios. {@code stock} no
 * conoce a {@code ventas}. Y todavia no existe {@code facturacion}: solo ese modulo hablara con ARCA y llega
 * en la fase 5.
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
    void todaviaNoExisteElPaqueteFacturacion() {
        noClasses().should().resideInAPackage("com.ferreteria.facturacion..").check(clases);
    }
}
