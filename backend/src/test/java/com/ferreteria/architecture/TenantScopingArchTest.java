package com.ferreteria.architecture;

import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.catalogo.service.RecalculoDiarioJob;
import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla estatica de aislamiento (mismo enfoque que Sendo): nadie llama a los finders "pelados"
 * de JpaRepository sobre un repositorio de datos de una ferreteria, porque ignoran ferreteria_id.
 * Se usan findScoped / findAllScoped / metodos *Scoped. Excepcion: codigo con @UnscopedTenantAccess.
 */
class TenantScopingArchTest {

    private static final Set<String> FINDERS_SIN_SCOPE = Set.of(
            "findById", "findAll", "findAllById", "findOne", "findBy", "existsById", "exists", "count",
            "getById", "getOne", "getReferenceById",
            "delete", "deleteById", "deleteAll", "deleteAllById", "deleteAllInBatch", "deleteAllByIdInBatch");

    private final JavaClasses clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ferreteria");

    @Test
    void nadieUsaFindersSinScopeSobreDatosDeUnaFerreteria() {
        DescribedPredicate<JavaMethodCall> finderSinScope = new DescribedPredicate<>(
                "un finder sin scope sobre un TenantScopedRepository, fuera de @UnscopedTenantAccess") {
            @Override
            public boolean test(JavaMethodCall call) {
                boolean esRepoTenant = call.getTargetOwner().isAssignableTo(TenantScopedRepository.class);
                boolean esFinderSinScope = FINDERS_SIN_SCOPE.contains(call.getName());
                boolean excepcionDocumentada = call.getOrigin().isAnnotatedWith(UnscopedTenantAccess.class)
                        || call.getOriginOwner().isAnnotatedWith(UnscopedTenantAccess.class);
                return esRepoTenant && esFinderSinScope && !excepcionDocumentada;
            }
        };

        noClasses().should().callMethodWhere(finderSinScope).check(clases);
    }

    // ---- Cotizacion (R-024, R-022): la del mercado es global a proposito; la configuracion, de cada ferreteria ----

    @Test
    void laConfiguracionDeCotizacionEsUnRepositorioDeTenant() {
        assertThat(TenantScopedRepository.class).isAssignableFrom(ConfiguracionCotizacionRepository.class);
    }

    @Test
    void nadieUsaFindersSinScopeSobreLaConfiguracionDeCotizacion() {
        // Mas estricta que la regla general: aca no vale ni siquiera @UnscopedTenantAccess.
        DescribedPredicate<JavaMethodCall> finderSinScope = new DescribedPredicate<>(
                "un finder sin scope sobre ConfiguracionCotizacionRepository") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(ConfiguracionCotizacionRepository.class)
                        && FINDERS_SIN_SCOPE.contains(call.getName());
            }
        };

        noClasses().should().callMethodWhere(finderSinScope).check(clases);
    }

    @Test
    void laCotizacionDelMercadoEsGlobalPorDisenoYEstaEnSuPaquete() {
        assertThat(TenantScopedRepository.class.isAssignableFrom(CotizacionRepository.class)).isFalse();
        assertThat(CotizacionRepository.class.getPackageName()).isEqualTo("com.ferreteria.cotizacion.repository");

        // La excepcion es explicita: la entidad no tiene ningun campo de ferreteria.
        noFields().that().areDeclaredIn(Cotizacion.class)
                .should().haveNameMatching("(?i).*ferreteria.*")
                .check(clases);
    }

    @Test
    void elRecalculoDiarioNoUsaUnscopedTenantAccess() {
        noClasses().that().areAssignableTo(RecalculoDiarioJob.class)
                .should().beAnnotatedWith(UnscopedTenantAccess.class)
                .check(clases);
        noMethods().that().areDeclaredIn(RecalculoDiarioJob.class)
                .should().beAnnotatedWith(UnscopedTenantAccess.class)
                .check(clases);
    }

    // ---- Importacion flexible (R-132): los repositorios del catalogo son todos de tenant y no hay excepciones ----

    @Test
    void todosLosRepositoriosDelCatalogoSonDeTenant() {
        classes().that().resideInAPackage("com.ferreteria.catalogo.repository..")
                .and().areInterfaces()
                .should().beAssignableTo(TenantScopedRepository.class)
                .check(clases);
    }

    @Test
    void elRepositorioDeImportacionesNoDeclaraFindersSinScope() {
        assertThat(TenantScopedRepository.class).isAssignableFrom(ImportacionListaRepository.class);
        assertThat(ImportacionListaRepository.class.getDeclaredMethods())
                .isNotEmpty()
                .noneMatch(m -> FINDERS_SIN_SCOPE.contains(m.getName()));
    }

    @Test
    void nadaDelCatalogoUsaUnscopedTenantAccess() {
        noClasses().that().resideInAPackage("com.ferreteria.catalogo..")
                .should().beAnnotatedWith(UnscopedTenantAccess.class)
                .check(clases);
        noMethods().that().areDeclaredInClassesThat().resideInAPackage("com.ferreteria.catalogo..")
                .should().beAnnotatedWith(UnscopedTenantAccess.class)
                .check(clases);
    }
}
