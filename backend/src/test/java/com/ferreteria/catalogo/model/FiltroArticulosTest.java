package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class FiltroArticulosTest {

    @Test
    @DisplayName("q nulo queda null")
    void qNulo() {
        assertThat(new FiltroArticulos(null, null, null, false).q()).isNull();
    }

    @Test
    @DisplayName("q en blanco queda null")
    void qEnBlanco() {
        assertThat(new FiltroArticulos(null, null, "   ", false).q()).isNull();
        assertThat(new FiltroArticulos(null, null, "", false).q()).isNull();
    }

    @Test
    @DisplayName("q se guarda sin espacios sobrantes")
    void qConTrim() {
        assertThat(new FiltroArticulos(null, null, "  cable  ", false).q()).isEqualTo("cable");
    }

    @Test
    @DisplayName("patron: %q% sobre el texto recortado")
    void patronBasico() {
        assertThat(new FiltroArticulos(null, null, " torni ", false).patron()).isEqualTo("%torni%");
    }

    @Test
    @DisplayName("patron: escapa \\, % y _ para que se busquen como texto literal")
    void patronEscapaComodines() {
        assertThat(new FiltroArticulos(null, null, "50%_a\\b", false).patron()).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    @DisplayName("patron: null cuando no hay texto")
    void patronNulo() {
        assertThat(new FiltroArticulos(null, null, null, false).patron()).isNull();
        assertThat(new FiltroArticulos(null, null, "  ", true).patron()).isNull();
    }

    @Test
    @DisplayName("vacio: sin proveedor, rubro ni texto")
    void vacio() {
        assertThat(new FiltroArticulos(null, null, null, false).vacio()).isTrue();
        assertThat(new FiltroArticulos(null, null, "   ", false).vacio()).isTrue();
    }

    @Test
    @DisplayName("vacio: incluirInactivos no cuenta como filtro")
    void vacioIgnoraIncluirInactivos() {
        assertThat(new FiltroArticulos(null, null, null, true).vacio()).isTrue();
    }

    @Test
    @DisplayName("no vacio: alcanza con proveedor, con rubro o con texto")
    void noVacio() {
        assertThat(new FiltroArticulos(1L, null, null, false).vacio()).isFalse();
        assertThat(new FiltroArticulos(null, 2L, null, false).vacio()).isFalse();
        assertThat(new FiltroArticulos(null, null, "cable", false).vacio()).isFalse();
    }

    @Test
    @DisplayName("patronDeBusqueda produce el mismo texto que el listado de articulos")
    void patronDeBusquedaEstatico() {
        assertThat(FiltroArticulos.patronDeBusqueda(null)).isNull();
        assertThat(FiltroArticulos.patronDeBusqueda("   ")).isNull();
        assertThat(FiltroArticulos.patronDeBusqueda(" torni ")).isEqualTo("%torni%");
        assertThat(FiltroArticulos.patronDeBusqueda("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    @DisplayName("el constructor de 4 argumentos deja importacionId en null")
    void cuatroArgumentosSinImportacion() {
        assertThat(new FiltroArticulos(1L, 2L, "cable", true).importacionId()).isNull();
    }

    @Test
    @DisplayName("importacionId se guarda tal cual en el constructor de 5 argumentos")
    void importacionId() {
        assertThat(new FiltroArticulos(null, null, null, false, 9L).importacionId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("no vacio: un filtro con solo importacionId filtra, aunque no tenga proveedor, rubro ni texto")
    void soloImportacionNoEsVacio() {
        assertThat(new FiltroArticulos(null, null, null, false, 9L).vacio()).isFalse();
        assertThat(new FiltroArticulos(null, null, null, true, 9L).vacio()).isFalse();
    }

    @Test
    @DisplayName("vacio: con importacionId null y el resto sin filtros sigue siendo vacio")
    void importacionNulaSigueVacio() {
        assertThat(new FiltroArticulos(null, null, null, false, null).vacio()).isTrue();
        assertThat(new FiltroArticulos(null, null, "   ", true, null).vacio()).isTrue();
    }
}
