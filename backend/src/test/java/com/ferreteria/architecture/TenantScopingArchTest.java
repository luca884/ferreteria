package com.ferreteria.architecture;

import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
}
