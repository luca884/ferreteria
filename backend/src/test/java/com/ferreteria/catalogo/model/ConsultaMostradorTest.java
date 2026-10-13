package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class ConsultaMostradorTest {

    @Test
    @DisplayName("q nulo, vacio o en blanco: vacia, sin codigo y con los 5 slots en null")
    void consultaVacia() {
        for (String q : new String[] {null, "", "   ", "\t \n"}) {
            ConsultaMostrador c = ConsultaMostrador.de(q);

            assertThat(c.vacia()).as("vacia con q=[%s]", q).isTrue();
            assertThat(c.codigo()).isNull();
            assertThat(c.patrones()).hasSize(5).containsOnlyNulls();
        }
    }

    @Test
    @DisplayName("el codigo es q sin espacios ni saltos de linea al borde")
    void codigoRecortado() {
        ConsultaMostrador c = ConsultaMostrador.de(" 7790001\n");

        assertThat(c.vacia()).isFalse();
        assertThat(c.codigo()).isEqualTo("7790001");
        assertThat(ConsultaMostrador.de("TOR-014").codigo()).isEqualTo("TOR-014");
    }

    @Test
    @DisplayName("una palabra: %palabra% en el primer slot y los demas en null")
    void unaPalabra() {
        ConsultaMostrador c = ConsultaMostrador.de("tornillo");

        assertThat(c.patron(0)).isEqualTo("%tornillo%");
        assertThat(c.patron(1)).isNull();
        assertThat(c.patron(4)).isNull();
        assertThat(c.patrones()).hasSize(5);
    }

    @Test
    @DisplayName("separa por espacios, tabs y espacios multiples")
    void separaPorCualquierBlanco() {
        ConsultaMostrador c = ConsultaMostrador.de("  tornillo \t  1/4\n hexagonal ");

        assertThat(c.patrones()).containsExactly("%tornillo%", "%1/4%", "%hexagonal%", null, null);
        assertThat(c.codigo()).isEqualTo("tornillo \t  1/4\n hexagonal");
    }

    @Test
    @DisplayName("escapa \\, % y _ para que se busquen como texto literal")
    void escapaComodines() {
        assertThat(ConsultaMostrador.de("50%").patron(0)).isEqualTo("%50\\%%");
        assertThat(ConsultaMostrador.de("a_b").patron(0)).isEqualTo("%a\\_b%");
        assertThat(ConsultaMostrador.de("c:\\t").patron(0)).isEqualTo("%c:\\\\t%");
    }

    @Test
    @DisplayName("cinco palabras: cada una en su slot")
    void cincoPalabras() {
        ConsultaMostrador c = ConsultaMostrador.de("a b c d e");

        assertThat(c.patrones()).containsExactly("%a%", "%b%", "%c%", "%d%", "%e%");
        assertThat(ConsultaMostrador.MAXIMO_PALABRAS).isEqualTo(5);
    }

    @Test
    @DisplayName("patron(i) fuera de 0..4 no es valido")
    void patronFueraDeRango() {
        ConsultaMostrador c = ConsultaMostrador.de("tornillo");

        assertThatThrownBy(() -> c.patron(5)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> c.patron(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("los patrones son inmutables y siempre de largo 5")
    void patronesInmutables() {
        List<String> patrones = ConsultaMostrador.de("tornillo").patrones();

        assertThat(patrones).hasSize(5);
        assertThatThrownBy(() -> patrones.set(0, "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(patrones).containsExactly("%tornillo%", null, null, null, null);
    }

    // --- Desborde: mas de 5 palabras ---

    @Test
    @DisplayName("siete palabras: las 4 primeras van sueltas y de la 5 a la 7 se unen con un espacio en el slot 5")
    void sietePalabrasSeUnenEnElUltimoSlot() {
        ConsultaMostrador c = ConsultaMostrador.de("w1 w2 w3 w4 w5 w6 w7");

        // Documentado: el slot 5 es una subcadena contigua ("w5 w6 w7"), no tres palabras sueltas.
        assertThat(c.patrones()).containsExactly("%w1%", "%w2%", "%w3%", "%w4%", "%w5 w6 w7%");
    }

    @Test
    @DisplayName("el desborde se une con un solo espacio y escapa los comodines")
    void desbordeConUnSoloEspacioYEscapado() {
        ConsultaMostrador c = ConsultaMostrador.de("a  b\tc d e   50%   f_g");

        assertThat(c.patron(4)).isEqualTo("%e 50\\% f\\_g%");
        assertThat(c.patrones().subList(0, 4)).containsExactly("%a%", "%b%", "%c%", "%d%");
    }

    @Test
    @DisplayName("seis palabras: el slot 5 une la quinta y la sexta; el codigo conserva todo q recortado")
    void seisPalabrasYCodigoCompleto() {
        ConsultaMostrador c = ConsultaMostrador.de(" w1 w2 w3 w4 w5 w6 ");

        assertThat(c.patron(4)).isEqualTo("%w5 w6%");
        assertThat(c.codigo()).isEqualTo("w1 w2 w3 w4 w5 w6");
    }
}
