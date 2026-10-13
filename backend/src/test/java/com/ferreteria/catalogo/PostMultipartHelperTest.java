package com.ferreteria.catalogo;

import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El helper postMultipart de la base de tests arma un pedido multipart bien formado. */
class PostMultipartHelperTest extends IntegrationTestBase {

    @Test
    void unPostMultipartAUnaRutaSinControladorDa404() throws Exception {
        String token = login("dueno@tornillo.demo");

        postMultipart("/api/no-existe", token, archivo("lista.csv", "a;b".getBytes()), Map.of("clave", "valor"))
                .andExpect(status().isNotFound());
    }

    @Test
    void elArchivoViajaEnLaParteArchivoComoOctetStream() throws Exception {
        MockMultipartFile parte = archivo("lista.xlsx", new byte[] {1, 2, 3});

        assertThat(parte.getName()).isEqualTo("archivo");
        assertThat(parte.getOriginalFilename()).isEqualTo("lista.xlsx");
        assertThat(parte.getContentType()).isEqualTo("application/octet-stream");
        assertThat(parte.getBytes()).containsExactly(1, 2, 3);
    }
}
