package com.ferreteria.cotizacion.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.cotizacion.model.CatalogoCotizaciones.Tipo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El catalogo fijo de tipos de cotizacion: lo usan los adaptadores de las fuentes y la validacion de la configuracion. */
class CatalogoCotizacionesTest {

    @Test
    @DisplayName("conoce los siete tipos de dolar, el euro oficial y el real oficial")
    void conoceLosTiposDelCatalogo() {
        List<String> codigos = CatalogoCotizaciones.codigos();

        assertThat(codigos).containsExactlyInAnyOrder(
                "USD:oficial", "USD:blue", "USD:bolsa", "USD:contadoconliqui", "USD:mayorista", "USD:tarjeta",
                "USD:cripto", "EUR:oficial", "BRL:oficial");
    }

    @Test
    @DisplayName("solo los tipos de USD sirven como dolar de los precios; el euro y el real, no")
    void soloElDolarSirveParaLosPrecios() {
        assertThat(CatalogoCotizaciones.sirveComoDolarDePrecios("USD:blue")).isTrue();
        assertThat(CatalogoCotizaciones.sirveComoDolarDePrecios("USD:bolsa")).isTrue();
        assertThat(CatalogoCotizaciones.sirveComoDolarDePrecios("EUR:oficial")).isFalse();
        assertThat(CatalogoCotizaciones.sirveComoDolarDePrecios("BRL:oficial")).isFalse();
        assertThat(CatalogoCotizaciones.sirveComoDolarDePrecios("USD:inventado")).isFalse();
        assertThat(CatalogoCotizaciones.sirveComoDolarDePrecios(null)).isFalse();
    }

    @Test
    @DisplayName("las casas de dolar son las siete, sin el euro ni el real")
    void casasDeDolar() {
        assertThat(CatalogoCotizaciones.casasDeDolar()).containsExactlyInAnyOrder(
                "oficial", "blue", "bolsa", "contadoconliqui", "mayorista", "tarjeta", "cripto");
    }

    @Test
    @DisplayName("el codigo MONEDA:casa se arma y se parte sin ambiguedad")
    void codigoSeArmaYSeParte() {
        assertThat(CatalogoCotizaciones.codigo("USD", "oficial")).isEqualTo("USD:oficial");
        assertThat(CatalogoCotizaciones.parsear("USD:oficial")).contains(new Tipo("USD", "oficial"));
        assertThat(CatalogoCotizaciones.parsear("EUR:oficial")).contains(new Tipo("EUR", "oficial"));
        assertThat(CatalogoCotizaciones.parsear("USD:contadoconliqui"))
                .contains(new Tipo("USD", "contadoconliqui"));
        assertThat(new Tipo("USD", "blue").codigo()).isEqualTo("USD:blue");
    }

    @Test
    @DisplayName("un codigo mal armado o fuera del catalogo no se reconoce")
    void codigosInvalidos() {
        assertThat(CatalogoCotizaciones.parsear("oficial")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear("USD:")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear(":oficial")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear("USD:oficial:extra")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear("USD:oficial,USD:blue")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear("CLP:oficial")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear("EUR:blue")).isEmpty();
        assertThat(CatalogoCotizaciones.parsear(null)).isEmpty();
        assertThat(CatalogoCotizaciones.conoce("USD:blue")).isTrue();
        assertThat(CatalogoCotizaciones.conoce("USD:otra")).isFalse();
    }

    @Test
    @DisplayName("cada tipo tiene un nombre para mostrar; uno desconocido devuelve el codigo")
    void nombres() {
        assertThat(CatalogoCotizaciones.nombre("USD:oficial")).isEqualTo("Oficial");
        assertThat(CatalogoCotizaciones.nombre("USD:bolsa")).isEqualTo("MEP");
        assertThat(CatalogoCotizaciones.nombre("EUR:oficial")).isEqualTo("Euro");
        assertThat(CatalogoCotizaciones.nombre("BRL:oficial")).isEqualTo("Real");
        assertThat(CatalogoCotizaciones.nombre("USD:rara")).isEqualTo("USD:rara");
    }

    @Test
    @DisplayName("la lista de codigos no se puede modificar desde afuera")
    void listaInmutable() {
        assertThatThrownBy(() -> CatalogoCotizaciones.codigos().add("USD:nuevo"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
