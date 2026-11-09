package com.ferreteria.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.service.JwtService;
import com.ferreteria.auth.service.SegundoFactorService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La sesión del super admin sale de un solo lugar (totp-super-admin, S1 y S5b): {@link SegundoFactorService}, después de un
 * código bueno. Nadie más del código de producción llama a {@code JwtService.generarTokenConSegundoFactor} ni a
 * {@code SegundoFactorService.entrar}; el helper de los tests ({@code IntegrationTestBase.login}, S9) no cuenta, porque no
 * se importan los tests.
 */
class SesionDelSuperAdminArchTest {

    private final JavaClasses clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ferreteria");

    @Test
    @DisplayName("S5b: solo el segundo paso emite la sesión del super admin")
    void soloElSegundoPasoEmiteLaSesionDelSuperAdmin() {
        noClasses().that().doNotHaveFullyQualifiedName(SegundoFactorService.class.getName())
                .should().callMethod(JwtService.class, "generarTokenConSegundoFactor", Usuario.class)
                .orShould().callMethod(SegundoFactorService.class, "entrar", Usuario.class)
                .check(clases);
        // la regla no es vacia: el segundo paso la emite
        assertThat(clases.get(SegundoFactorService.class).getMethodCallsFromSelf())
                .anyMatch(c -> c.getTargetOwner().isEquivalentTo(JwtService.class)
                        && c.getName().equals("generarTokenConSegundoFactor"));
    }
}
