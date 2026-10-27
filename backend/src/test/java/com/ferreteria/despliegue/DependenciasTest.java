package com.ferreteria.despliegue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.junit.jupiter.api.Test;

/**
 * commons-compress (lo trae poi-ooxml) usa metodos de commons-lang3 que el BOM de Spring Boot puede dejar afuera si
 * baja la version. Con commons-lang3 3.14.0, una entrada tar tiraba NoSuchMethodError
 * (SystemProperties.getUserName(String)) y Testcontainers no podia copiar archivos a los contenedores.
 */
class DependenciasTest {

    @Test
    void commonsCompressEncuentraEnCommonsLang3LoQueNecesita() {
        assertThatCode(() -> assertThat(new TarArchiveEntry("lista.xlsx").getName()).isEqualTo("lista.xlsx"))
            .doesNotThrowAnyException();
    }
}
