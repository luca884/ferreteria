package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Codigo de barras que trae la lista: hasta 30 caracteres, como en el alta manual. */
class CodigoBarrasDeListaTest {

    @Test
    @DisplayName("un codigo normal se toma tal cual, recortando los espacios")
    void valido() {
        CodigoBarrasDeLista.ResultadoBarras resultado =
            CodigoBarrasDeLista.interpretar(new Celda("  7791234567890 ", null));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.codigo()).isEqualTo("7791234567890");
        assertThat(resultado.mensaje()).isNull();
    }

    @Test
    @DisplayName("una celda numerica de Excel llega con su texto sin notacion cientifica")
    void numerico() {
        Celda celda = new Celda("7791234567890", new BigDecimal("7791234567890"));

        assertThat(CodigoBarrasDeLista.interpretar(celda).codigo()).isEqualTo("7791234567890");
    }

    @Test
    @DisplayName("30 caracteres es el maximo: 30 pasa y 31 es un error")
    void largoMaximo() {
        CodigoBarrasDeLista.ResultadoBarras de30 = CodigoBarrasDeLista.interpretar(new Celda("1".repeat(30), null));
        CodigoBarrasDeLista.ResultadoBarras de31 = CodigoBarrasDeLista.interpretar(new Celda("1".repeat(31), null));

        assertThat(de30.esValor()).isTrue();
        assertThat(de30.codigo()).hasSize(30);
        assertThat(de31.esError()).isTrue();
        assertThat(de31.codigo()).isNull();
        assertThat(de31.mensaje()).isEqualTo("El código de barras no puede superar 30 caracteres");
    }

    @Test
    @DisplayName("los espacios sobrantes no cuentan para el largo")
    void espaciosNoCuentan() {
        Celda celda = new Celda("  " + "1".repeat(30) + "  ", null);

        assertThat(CodigoBarrasDeLista.interpretar(celda).esValor()).isTrue();
    }

    @Test
    @DisplayName("celda vacia o en blanco es sin dato, igual que la columna no mapeada")
    void sinDato() {
        assertThat(CodigoBarrasDeLista.interpretar(Celda.VACIA).esSinDato()).isTrue();
        assertThat(CodigoBarrasDeLista.interpretar(new Celda("   ", null)).esSinDato()).isTrue();
        assertThat(CodigoBarrasDeLista.interpretar(null).esSinDato()).isTrue();
        assertThat(CodigoBarrasDeLista.interpretar(Celda.VACIA).codigo()).isNull();
        assertThat(CodigoBarrasDeLista.interpretar(Celda.VACIA).esError()).isFalse();
    }
}
