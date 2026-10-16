package com.ferreteria.catalogo;

import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/** Limites de subida y batch de Hibernate que necesita la importacion de listas (CU-04). */
class ConfiguracionImportacionTest extends IntegrationTestBase {

    @Autowired
    MultipartProperties multipart;

    @Autowired
    Environment environment;

    @Test
    void elArchivoPuedePesarComoMaximo25MB() {
        assertThat(multipart.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(25));
    }

    @Test
    void elPedidoDejaLugarParaLaParteDeDatos() {
        assertThat(multipart.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(27));
    }

    @Test
    void laRespuestaJsonSeComprimeDesdeLos2KB() {
        assertThat(environment.getProperty("server.compression.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("server.compression.mime-types", String.class))
                .contains("application/json");
        assertThat(environment.getProperty("server.compression.min-response-size", DataSize.class))
                .isEqualTo(DataSize.ofKilobytes(2));
    }

    @Test
    void hibernateAgrupaLasEscrituras() {
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size", Integer.class))
                .isEqualTo(50);
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.order_inserts", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.order_updates", Boolean.class))
                .isTrue();
    }
}
