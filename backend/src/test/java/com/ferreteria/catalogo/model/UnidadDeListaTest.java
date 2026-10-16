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
