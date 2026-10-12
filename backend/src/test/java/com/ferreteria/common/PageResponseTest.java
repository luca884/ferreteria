package com.ferreteria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class PageResponseTest {

    @Test
    @DisplayName("from() mapea el contenido y copia pagina, tamano y totales")
    void mapeaContenidoYTotales() {
        PageImpl<Integer> page = new PageImpl<>(List.of(21, 22, 23), PageRequest.of(1, 3, Sort.by("x")), 7);

        PageResponse<String> respuesta = PageResponse.from(page, n -> "n" + n);

        assertThat(respuesta.items()).containsExactly("n21", "n22", "n23");
        assertThat(respuesta.page()).isEqualTo(1);
        assertThat(respuesta.size()).isEqualTo(3);
        assertThat(respuesta.totalElements()).isEqualTo(7);
        assertThat(respuesta.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("una pagina vacia da lista vacia y cero paginas")
    void paginaVacia() {
        PageImpl<Integer> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

        PageResponse<String> respuesta = PageResponse.from(page, n -> "n" + n);

        assertThat(respuesta.items()).isEmpty();
        assertThat(respuesta.totalElements()).isZero();
        assertThat(respuesta.totalPages()).isZero();
    }
}
