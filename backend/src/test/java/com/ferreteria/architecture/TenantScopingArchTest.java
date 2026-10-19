package com.ferreteria.architecture;

import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.catalogo.service.RecalculoDiarioJob;
import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
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

    // ---- Corte 3 (C-217): el filtro por importacion, findParaEditarScoped y los metodos con ferreteriaId explicito ----

    /** Los metodos de consulta con el id de la ferreteria como parametro solo los llama el propio repositorio. */
    @Test
    void lasConsultasConFerreteriaIdExplicitoSoloLasLlamaElRepositorio() {
        DescribedPredicate<JavaMethodCall> consultaCruda = new DescribedPredicate<>(
                "una consulta abstracta declarada en un repositorio del catalogo, llamada desde afuera de el") {
            @Override
            public boolean test(JavaMethodCall call) {
                JavaClass duenio = call.getTarget().getOwner();
                if (!duenio.getPackageName().equals("com.ferreteria.catalogo.repository")) {
                    return false;
                }
                boolean esConsultaPropia = call.getTarget().resolveMember()
                        .map(m -> m.getOwner().getPackageName().equals("com.ferreteria.catalogo.repository")
                                && m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                        .orElse(false);
                boolean llamaDesdeElMismo = call.getOriginOwner().equals(duenio);
                return esConsultaPropia && !llamaDesdeElMismo;
            }
        };

        noClasses().should().callMethodWhere(consultaCruda).check(clases);
    }

    /** Todo metodo default de un repositorio del catalogo toma la ferreteria de TenantContext (o delega en otro default). */
    @Test
    void losMetodosScopedDelCatalogoTomanLaFerreteriaDelContexto() {
        ArchCondition<JavaMethod> tomaLaFerreteriaDelContexto = new ArchCondition<>(
                "llamar a TenantContext.requireFerreteriaId() o a otro metodo del mismo repositorio") {
            @Override
            public void check(JavaMethod metodo, ConditionEvents eventos) {
                boolean ok = metodo.getMethodCallsFromSelf().stream().anyMatch(c ->
                        (c.getTargetOwner().isEquivalentTo(com.ferreteria.tenant.TenantContext.class)
                                && c.getName().equals("requireFerreteriaId"))
                        || c.getTargetOwner().equals(metodo.getOwner()));
                if (!ok) {
                    eventos.add(SimpleConditionEvent.violated(metodo,
                            metodo.getFullName() + " no toma la ferreteria de TenantContext"));
                }
            }
        };

        methods().that().areDeclaredInClassesThat().resideInAPackage("com.ferreteria.catalogo.repository..")
                .and().areDeclaredInClassesThat().areInterfaces()
                .and().doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
                .and().doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.SYNTHETIC)
                .should(tomaLaFerreteriaDelContexto)
                .allowEmptyShould(false)
                .check(clases);
    }

    // ---- Cuenta corriente de clientes (CC-008): los repositorios nuevos también son de tenant ----

    @Test
    void todosLosRepositoriosDeClientesSonDeTenant() {
        classes().that().resideInAPackage("com.ferreteria.clientes.repository..")
                .and().areInterfaces()
                .and().haveSimpleNameEndingWith("Repository")
                .should().beAssignableTo(TenantScopedRepository.class)
                .check(clases);
        assertThat(TenantScopedRepository.class).isAssignableFrom(com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.class);
    }

    /** Las consultas con la ferretería como parámetro (bloquearCuenta, sumarSaldo, saldoDelLibro…) solo las llama su repositorio. */
    @Test
    void lasConsultasDeClientesConFerreteriaIdExplicitoSoloLasLlamaElRepositorio() {
        DescribedPredicate<JavaMethodCall> consultaCruda = new DescribedPredicate<>(
                "una consulta abstracta de un repositorio de clientes, llamada desde afuera de él") {
            @Override
            public boolean test(JavaMethodCall call) {
                JavaClass duenio = call.getTarget().getOwner();
                // Solo los repositorios: las proyecciones (ClienteRepository.CuentaBloqueada) son del mismo paquete y se leen desde afuera.
                if (!duenio.getPackageName().equals("com.ferreteria.clientes.repository")
                        || !duenio.isAssignableTo(TenantScopedRepository.class)) {
                    return false;
                }
                boolean esConsultaPropia = call.getTarget().resolveMember()
                        .map(m -> m.getOwner().getPackageName().equals("com.ferreteria.clientes.repository")
                                && m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                        .orElse(false);
                return esConsultaPropia && !call.getOriginOwner().equals(duenio);
            }
        };

        noClasses().should().callMethodWhere(consultaCruda).check(clases);
    }

    @Test
    void losMetodosScopedDeClientesTomanLaFerreteriaDelContexto() {
        ArchCondition<JavaMethod> tomaLaFerreteriaDelContexto = new ArchCondition<>(
                "llamar a TenantContext.requireFerreteriaId() o a otro metodo del mismo repositorio") {
            @Override
            public void check(JavaMethod metodo, ConditionEvents eventos) {
                boolean ok = metodo.getMethodCallsFromSelf().stream().anyMatch(c ->
                        (c.getTargetOwner().isEquivalentTo(com.ferreteria.tenant.TenantContext.class)
                                && c.getName().equals("requireFerreteriaId"))
                        || c.getTargetOwner().equals(metodo.getOwner()));
                if (!ok) {
                    eventos.add(SimpleConditionEvent.violated(metodo,
                            metodo.getFullName() + " no toma la ferreteria de TenantContext"));
                }
            }
        };

        methods().that().areDeclaredInClassesThat().resideInAPackage("com.ferreteria.clientes.repository..")
                .and().areDeclaredInClassesThat().areInterfaces()
                .and().doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
                .and().doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.SYNTHETIC)
                .should(tomaLaFerreteriaDelContexto)
                .allowEmptyShould(false)
                .check(clases);
    }
}
