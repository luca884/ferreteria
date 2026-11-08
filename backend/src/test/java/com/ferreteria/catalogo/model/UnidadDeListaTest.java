package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unidad de venta que trae la lista: abreviaturas habituales, sin distinguir mayusculas. */
class UnidadDeListaTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "u, UNIDAD", "un, UNIDAD", "unidad, UNIDAD", "UNIDAD, UNIDAD", " Un , UNIDAD",
        "m, METRO", "mt, METRO", "metro, METRO", "MT, METRO",
        "kg, KILO", "kilo, KILO", "KG, KILO",
        "l, LITRO", "lt, LITRO", "litro, LITRO", "Lt, LITRO",
    })
    @DisplayName("reconoce las abreviaturas de las cuatro unidades")
    void reconoce(String texto, Unidad esperada) {
        UnidadDeLista.ResultadoUnidad resultado = UnidadDeLista.interpretar(new Celda(texto, null));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.unidad()).isEqualTo(esperada);
        assertThat(resultado.mensaje()).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "Kg., KILO", "KGS, KILO", "Kilos, KILO", "kgs., KILO", "kilogramo, KILO", "KILOGRAMOS, KILO",
        "Mts., METRO", "MTS, METRO", "metros, METRO", "M., METRO",
        "Lts., LITRO", "LTS, LITRO", "litros, LITRO",
        "Unid., UNIDAD", "UND, UNIDAD", "unidades, UNIDAD", "Un., UNIDAD",
    })
    @DisplayName("reconoce el punto final, el plural y otras abreviaturas habituales (Kg., KGS, Kilos)")
    void reconoceVariantes(String texto, Unidad esperada) {
        UnidadDeLista.ResultadoUnidad resultado = UnidadDeLista.interpretar(new Celda(texto, null));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.unidad()).isEqualTo(esperada);
        assertThat(resultado.mensaje()).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Clavo punta París 2\" x kg | KILO",
        "Clavo punta París 1 1/2\" X KILO | KILO",
        "Clavos de acero por kilo | KILO",
        "Alambre galvanizado N.º 14 (x kg.) | KILO",
        "Alambre recocido xkgs | KILO",
        "Cadena patente 3 mm x mt | METRO",
        "Manguera cristal 1/2\" x metro | METRO",
        "Cable taller 2x1 por metro | METRO",
        "Soga trenzada 8 mm X MTS. | METRO",
        "Aguarrás x lt | LITRO",
        "Thinner standard x litro | LITRO",
        "Soda cáustica líquida por litro | LITRO",
    })
    @DisplayName("lee la unidad de la descripción cuando dice x kg, por kilo, x mt, x metro, x lt o por litro")
    void deDescripcion(String descripcion, Unidad esperada) {
        assertThat(UnidadDeLista.deDescripcion(descripcion)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "\"{0}\" no dice unidad")
    @CsvSource(delimiter = '|', value = {
        "Cable unipolar 2,5mm x100m",
        "Cinta aisladora 20m",
        "Pintura latex interior blanco 4L",
        "Caño PVC 1/2\" x 6 m",
        "Tornillo para madera 6x1\"",
        "Bulón M8 x 40",
        "Llave termica 2x20A",
        "Cadena x m",
        "Tarugo nylon 6 mm",
        "Mexicana x kgx",
    })
    @DisplayName("una medida con número (x100m, 20m, 4L, x 6 m) o una letra suelta no es la unidad de venta")
    void descripcionSinUnidad(String descripcion) {
        assertThat(UnidadDeLista.deDescripcion(descripcion)).isNull();
    }

    @Test
    @DisplayName("una descripción vacía o null no dice unidad")
    void descripcionVacia() {
        assertThat(UnidadDeLista.deDescripcion(null)).isNull();
        assertThat(UnidadDeLista.deDescripcion("  ")).isNull();
    }

    @Test
    @DisplayName("un texto que no es una unidad, como caja, es un error: Unidad no reconocida")
    void noReconocida() {
        UnidadDeLista.ResultadoUnidad resultado = UnidadDeLista.interpretar(new Celda("caja", null));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.unidad()).isNull();
        assertThat(resultado.mensaje()).startsWith("Unidad no reconocida").contains("caja");
        assertThat(UnidadDeLista.interpretar(new Celda("pack", null)).esError()).isTrue();
    }

    @Test
    @DisplayName("celda vacia es sin dato (el alta usa UNIDAD)")
    void vacia() {
        UnidadDeLista.ResultadoUnidad resultado = UnidadDeLista.interpretar(Celda.VACIA);

        assertThat(resultado.esSinDato()).isTrue();
        assertThat(resultado.esError()).isFalse();
        assertThat(resultado.esValor()).isFalse();
        assertThat(resultado.unidad()).isNull();
        assertThat(UnidadDeLista.interpretar(new Celda("  ", null)).esSinDato()).isTrue();
    }

    @Test
    @DisplayName("columna no mapeada (celda null) es sin dato")
    void columnaNoMapeada() {
        assertThat(UnidadDeLista.interpretar(null).esSinDato()).isTrue();
    }
}
