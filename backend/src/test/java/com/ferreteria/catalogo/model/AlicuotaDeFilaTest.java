package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** IVA de cada fila: la columna de % IVA tal cual, o derivado de los precios sin y con IVA (decision 7). */
class AlicuotaDeFilaTest {

    private static final String VALIDAS = "Las alícuotas válidas son 0, 2,5, 5, 10,5, 21 y 27.";

    private static Celda texto(String valor) {
        return new Celda(valor, null);
    }

    private static Celda num(String valor) {
        return new Celda(valor, new BigDecimal(valor));
    }

    private static AlicuotaDeFila.ResultadoAlicuota porcentaje(Celda celda) {
        return AlicuotaDeFila.interpretar(celda, null, null);
    }

    private static AlicuotaDeFila.ResultadoAlicuota par(Celda sin, Celda con) {
        return AlicuotaDeFila.interpretar(null, sin, con);
    }

    // ---- columna % IVA ----

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {"10.5 | IVA_10_5", "10,5 | IVA_10_5", "21 | IVA_21", "21 % | IVA_21",
        "21,00 | IVA_21", "0 | IVA_0", "2,5 | IVA_2_5", "5 | IVA_5", "27 | IVA_27", "+21 | IVA_21"})
    @DisplayName("% IVA: se toma tal cual si es una de las seis alicuotas legales")
    void porcentajeLegal(String valor, AlicuotaIva esperada) {
        AlicuotaDeFila.ResultadoAlicuota resultado = porcentaje(texto(valor));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.alicuota()).isEqualTo(esperada);
        assertThat(resultado.advertencia()).isNull();
    }

    @Test
    @DisplayName("% IVA = 19 es un error que dice cuales son las validas, sin acercarla a la mas cercana")
    void porcentajeNoLegal() {
        AlicuotaDeFila.ResultadoAlicuota resultado = porcentaje(num("19"));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.mensaje()).isEqualTo("El IVA 19 no es válido. " + VALIDAS);
        assertThat(resultado.alicuota()).isNull();
        assertThat(porcentaje(texto("10,4")).mensaje()).isEqualTo("El IVA 10,4 no es válido. " + VALIDAS);
    }

    @Test
    @DisplayName("% IVA = abc es un error de la fila")
    void porcentajeNoNumerico() {
        AlicuotaDeFila.ResultadoAlicuota resultado = porcentaje(texto("abc"));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.mensaje()).startsWith("El IVA «abc» no es un número.").contains(VALIDAS);
    }

    @Test
    @DisplayName("0,21 escrito como numero (sin formato %) no es legal: error, nunca se adivina")
    void fraccionSinFormato() {
        AlicuotaDeFila.ResultadoAlicuota resultado = porcentaje(new Celda("0.21", new BigDecimal("0.21"), false));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.mensaje()).isEqualTo("El IVA 0,21 no es válido. " + VALIDAS);
    }

    @Test
    @DisplayName("0,21 con formato % de Excel se toma como se ve: 21 -> IVA_21")
    void fraccionConFormato() {
        AlicuotaDeFila.ResultadoAlicuota resultado = porcentaje(new Celda("0.21", new BigDecimal("0.21"), true));

        assertThat(resultado.alicuota()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(porcentaje(new Celda("0.105", new BigDecimal("0.105"), true)).alicuota())
            .isEqualTo(AlicuotaIva.IVA_10_5);
    }

    @Test
    @DisplayName("% IVA vacio y sin otra columna de IVA es sin dato")
    void porcentajeVacio() {
        assertThat(porcentaje(Celda.VACIA).esSinDato()).isTrue();
        assertThat(porcentaje(texto("  ")).esSinDato()).isTrue();
        assertThat(porcentaje(Celda.VACIA).alicuota()).isNull();
    }

    @Test
    @DisplayName("sin ninguna columna de IVA mapeada no hay dato")
    void sinColumnas() {
        AlicuotaDeFila.ResultadoAlicuota resultado = AlicuotaDeFila.interpretar(null, null, null);

        assertThat(resultado.esSinDato()).isTrue();
        assertThat(resultado.esError()).isFalse();
        assertThat(resultado.esValor()).isFalse();
    }

    // ---- par sin IVA / con IVA ----

    @ParameterizedTest(name = "sin {0} con {1} -> {2}")
    @CsvSource({"1000, 1105, IVA_10_5", "1000, 1210, IVA_21", "1000, 1000, IVA_0", "1000, 1215, IVA_21",
        "18, 21.78, IVA_21", "47.5, 52.4875, IVA_10_5"})
    @DisplayName("par: la alicuota mas cercana a con / sin, sin advertencia si la diferencia no pasa de 0,5")
    void parExacto(String sin, String con, AlicuotaIva esperada) {
        AlicuotaDeFila.ResultadoAlicuota resultado = par(num(sin), num(con));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.alicuota()).isEqualTo(esperada);
        assertThat(resultado.advertencia()).isNull();
    }

    @Test
    @DisplayName("par: con IVA 1.216 sobre 1.000 es 21 con advertencia que dice el valor calculado y la alicuota usada")
    void parConAdvertencia() {
        AlicuotaDeFila.ResultadoAlicuota resultado = par(num("1000"), num("1216"));

        assertThat(resultado.alicuota()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(resultado.advertencia()).contains("21,6").contains("21");
        assertThat(par(num("1000"), num("1190")).alicuota()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(par(num("1000"), num("1190")).advertencia()).contains("19").contains("21");
        assertThat(par(num("1000"), num("900")).alicuota()).isEqualTo(AlicuotaIva.IVA_0);
        assertThat(par(num("1000"), num("900")).advertencia()).contains("-10").contains("0");
    }

    @Test
    @DisplayName("par: un empate exacto (15,75 entre 10,5 y 21) usa la menor y advierte")
    void parEmpate() {
        AlicuotaDeFila.ResultadoAlicuota resultado = par(num("1000"), num("1157.5"));

        assertThat(resultado.alicuota()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(resultado.advertencia()).contains("15,75").contains("10,5");
    }

    @Test
    @DisplayName("par: con IVA vacio es sin dato, igual que sin IVA vacio")
    void parIncompleto() {
        assertThat(par(num("1000"), Celda.VACIA).esSinDato()).isTrue();
        assertThat(par(Celda.VACIA, num("1210")).esSinDato()).isTrue();
        assertThat(par(Celda.VACIA, Celda.VACIA).esSinDato()).isTrue();
    }

    @Test
    @DisplayName("par: sin IVA en cero no se puede dividir y queda sin dato (el costo cero lo informa otra regla)")
    void parConSinIvaEnCero() {
        assertThat(par(num("0"), num("0")).esSinDato()).isTrue();
    }

    @Test
    @DisplayName("par: un precio que no es numero es un error de la fila")
    void parNoNumerico() {
        AlicuotaDeFila.ResultadoAlicuota conTexto = par(num("1000"), texto("abc"));
        AlicuotaDeFila.ResultadoAlicuota sinTexto = par(texto("xyz"), num("1210"));

        assertThat(conTexto.esError()).isTrue();
        assertThat(conTexto.mensaje()).isEqualTo("El precio con IVA no es un número.");
        assertThat(sinTexto.esError()).isTrue();
        assertThat(sinTexto.mensaje()).isEqualTo("El precio sin IVA no es un número.");
    }

    @Test
    @DisplayName("par: acepta importes con formato argentino")
    void parConFormatoArgentino() {
        AlicuotaDeFila.ResultadoAlicuota resultado = par(texto("$ 1.000,00"), texto("$ 1.105,00"));

        assertThat(resultado.alicuota()).isEqualTo(AlicuotaIva.IVA_10_5);
    }

    // ---- prioridad ----

    @Test
    @DisplayName("si hay % IVA y tambien el par, manda % IVA")
    void mandaElPorcentaje() {
        AlicuotaDeFila.ResultadoAlicuota resultado =
            AlicuotaDeFila.interpretar(num("10.5"), num("1000"), num("1210"));

        assertThat(resultado.alicuota()).isEqualTo(AlicuotaIva.IVA_10_5);
        assertThat(resultado.advertencia()).isNull();
    }

    @Test
    @DisplayName("un % IVA invalido no se tapa con el par: sigue siendo error")
    void porcentajeInvalidoNoSeTapa() {
        AlicuotaDeFila.ResultadoAlicuota resultado =
            AlicuotaDeFila.interpretar(num("19"), num("1000"), num("1210"));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.mensaje()).startsWith("El IVA 19 no es válido.");
    }

    @Test
    @DisplayName("si la celda de % IVA esta vacia, se usa el par de la fila")
    void porcentajeVacioUsaElPar() {
        AlicuotaDeFila.ResultadoAlicuota resultado =
            AlicuotaDeFila.interpretar(Celda.VACIA, num("1000"), num("1105"));

        assertThat(resultado.alicuota()).isEqualTo(AlicuotaIva.IVA_10_5);
    }
}
