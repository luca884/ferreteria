package com.ferreteria.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Si en Railway falta SPRING_PROFILES_ACTIVE, JWT_SECRET o ARCA_CLAVE_MAESTRA, la app no puede arrancar con los datos demo
 * ni con un secreto que esta en el repo: el perfil por defecto es prod y los secretos con default viven solo en el perfil
 * dev (y en el de test, fijos).
 */
class PerfilPorDefectoTest {

    private static Properties yaml(String archivo) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(archivo));
        return factory.getObject();
    }

    @Test
    @DisplayName("sin SPRING_PROFILES_ACTIVE arranca en prod, no en dev")
    void perfilPorDefectoEsProd() {
        assertThat(yaml("application.yml").getProperty("spring.profiles.active"))
                .isEqualTo("${SPRING_PROFILES_ACTIVE:prod}");
    }

    @Test
    @DisplayName("application.yml no trae un secreto JWT por defecto: sin JWT_SECRET no arranca")
    void secretoSinDefault() {
        assertThat(yaml("application.yml").getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET}");
    }

    @Test
    @DisplayName("el perfil dev sigue arrancando sin JWT_SECRET con su secreto de desarrollo")
    void devTieneSecretoPropio() {
        assertThat(yaml("application-dev.yml").getProperty("app.jwt.secret")).startsWith("${JWT_SECRET:dev-secret-");
    }

    @Test
    @DisplayName("la clave maestra de ARCA no tiene default fuera de dev: sin ARCA_CLAVE_MAESTRA no arranca; la anterior es "
            + "opcional")
    void claveMaestraSinDefault() {
        assertThat(yaml("application.yml").getProperty("app.facturacion.clave-maestra")).isEqualTo("${ARCA_CLAVE_MAESTRA}");
        assertThat(yaml("application-prod.yml").getProperty("app.facturacion.clave-maestra"))
                .isEqualTo("${ARCA_CLAVE_MAESTRA}");
        assertThat(yaml("application.yml").getProperty("app.facturacion.clave-maestra-anterior"))
                .isEqualTo("${ARCA_CLAVE_MAESTRA_ANTERIOR:}");
    }

    @Test
    @DisplayName("dev arranca sin ARCA_CLAVE_MAESTRA con su clave de desarrollo, y test con una fija; las dos de 32 bytes")
    void devYTestTienenSuClaveMaestra() {
        String dev = yaml("application-dev.yml").getProperty("app.facturacion.clave-maestra");
        assertThat(dev).startsWith("${ARCA_CLAVE_MAESTRA:").endsWith("}");
        String defaultDeDev = dev.substring("${ARCA_CLAVE_MAESTRA:".length(), dev.length() - 1);
        String deTest = yaml("application-test.yml").getProperty("app.facturacion.clave-maestra");

        assertThat(Base64.getDecoder().decode(defaultDeDev)).hasSize(32);
        assertThat(deTest).doesNotContain("${");
        assertThat(Base64.getDecoder().decode(deTest)).hasSize(32);
        assertThat(deTest).isNotEqualTo(defaultDeDev);
    }
}
