package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Moneda de cada fila de la lista: columna mapeada, moneda fija o nada (decisiones 13 y 29). */
class MonedaDeListaTest {

    private static Celda texto(String valor) {
        return new Celda(valor, null);
    }

    private static MonedaDeLista.ResultadoMoneda conColumna(String valor) {
        return MonedaDeLista.interpretar(texto(valor), true, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARG", "ARS", "arg", " ars "})
    @DisplayName("ARG y ARS son pesos, sin distinguir mayusculas ni espacios")
    void pesos(String valor) {
        MonedaDeLista.ResultadoMoneda resultado = conColumna(valor);

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.moneda()).isEqualTo(Moneda.ARS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "usd ", " Usd"})
    @DisplayName("USD son dolares, sin distinguir mayusculas ni espacios")
    void dolares(String valor) {
        MonedaDeLista.ResultadoMoneda resultado = conColumna(valor);

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.moneda()).isEqualTo(Moneda.USD);
    }

    @Test
    @DisplayName("tampoco distingue tildes, y un espacio de no separacion alrededor se recorta")
    void sinTildesNiEspaciosRaros() {
        char noSeparacion = (char) 160;
        assertThat(conColumna(noSeparacion + "USD" + noSeparacion).moneda()).isEqualTo(Moneda.USD);
        assertThat(conColumna("ÁRG").moneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("EUR (u otro texto) con la columna mapeada es un error de la fila: Moneda no reconocida")
    void noReconocida() {
        MonedaDeLista.ResultadoMoneda euro = conColumna("EUR");
        MonedaDeLista.ResultadoMoneda titulo = conColumna("ALEBA CARGADORES");

        assertThat(euro.esError()).isTrue();
        assertThat(euro.mensaje()).startsWith("Moneda no reconocida").contains("EUR");
        assertThat(euro.moneda()).isNull();
        // Un titulo como este nunca llega aca: ZonaDeDatos lo saltea antes (sin costo numerico).
        assertThat(titulo.esError()).isTrue();
        assertThat(titulo.mensaje()).startsWith("Moneda no reconocida");
    }

    @Test
    @DisplayName("celda vacia con la columna mapeada es error \"Falta la moneda\": no se asume pesos (decision 29)")
    void celdaVaciaConColumna() {
        MonedaDeLista.ResultadoMoneda vacia = MonedaDeLista.interpretar(Celda.VACIA, true, null);
        MonedaDeLista.ResultadoMoneda enBlanco = MonedaDeLista.interpretar(texto("   "), true, null);

        assertThat(vacia.esError()).isTrue();
        assertThat(vacia.mensaje()).isEqualTo("Falta la moneda");
        assertThat(enBlanco.esError()).isTrue();
        assertThat(enBlanco.mensaje()).isEqualTo("Falta la moneda");
        assertThat(vacia.moneda()).isNull();
    }

    @Test
    @DisplayName("sin columna y sin moneda fija no hay dato: ARS para los nuevos y la actual para los existentes")
    void sinColumnaNiFija() {
        MonedaDeLista.ResultadoMoneda sinDato = MonedaDeLista.interpretar(Celda.VACIA, false, null);

        assertThat(sinDato.esSinDato()).isTrue();
        assertThat(sinDato.esValor()).isFalse();
        assertThat(sinDato.esError()).isFalse();
        assertThat(sinDato.moneda()).isNull();
        // Sin columna la celda no se mira, aunque tenga texto.
        assertThat(MonedaDeLista.interpretar(texto("USD"), false, null).esSinDato()).isTrue();
    }

    @Test
    @DisplayName("con moneda fija, todas las filas tienen esa moneda, sin mirar la celda")
    void monedaFija() {
        assertThat(MonedaDeLista.interpretar(Celda.VACIA, false, Moneda.USD).moneda()).isEqualTo(Moneda.USD);
        assertThat(MonedaDeLista.interpretar(texto("lo que sea"), false, Moneda.USD).moneda())
            .isEqualTo(Moneda.USD);
        assertThat(MonedaDeLista.interpretar(Celda.VACIA, false, Moneda.ARS).moneda()).isEqualTo(Moneda.ARS);
        assertThat(MonedaDeLista.interpretar(Celda.VACIA, false, Moneda.USD).esValor()).isTrue();
    }
}
