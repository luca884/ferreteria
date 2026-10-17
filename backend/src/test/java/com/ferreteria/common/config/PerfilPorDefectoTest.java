package com.ferreteria.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Si en Railway falta SPRING_PROFILES_ACTIVE o JWT_SECRET, la app no puede arrancar con los datos demo ni con un
 * secreto que esta en el repo: el perfil por defecto es prod y el secreto con default vive solo en el perfil dev.
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
}
