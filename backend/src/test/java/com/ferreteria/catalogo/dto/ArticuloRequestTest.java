package com.ferreteria.catalogo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.Unidad;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La moneda del articulo en el pedido y en la respuesta (T-034). El 400 por una moneda invalida se prueba por HTTP. */
class ArticuloRequestTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static ArticuloRequest pedido(Moneda moneda) {
        return new ArticuloRequest("TORN-001", null, null, "Tornillo", null, null, bd("10"), null, null,
                Unidad.UNIDAD, null, null, null, moneda);
    }

    @Test
    @DisplayName("en el alta, sin moneda el pedido es en pesos (el constructor de siempre y un null explicito)")
    void sinMonedaEnElAltaEsArs() {
        ArticuloRequest sinCampo = new ArticuloRequest("TORN-001", null, null, "Tornillo", null, null, bd("10"), null,
                null, Unidad.UNIDAD, null, null, null);

        assertThat(sinCampo.moneda()).isNull();
        assertThat(sinCampo.toDatos().moneda()).isEqualTo(Moneda.ARS);
        assertThat(pedido(null).toDatos().moneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("en la edicion, sin moneda se conserva la que tenia el articulo (como activo): USD sigue USD y ARS sigue ARS")
    void sinMonedaEnLaEdicionConservaLaActual() {
        assertThat(pedido(null).toDatos(false, Moneda.USD).moneda()).isEqualTo(Moneda.USD);
        assertThat(pedido(null).toDatos(true, Moneda.ARS).moneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("USD y ARS son validas y llegan tal cual a los datos del articulo, en el alta y en la edicion")
    void usdYArsSonValidas() {
        assertThat(pedido(Moneda.USD).toDatos().moneda()).isEqualTo(Moneda.USD);
        assertThat(pedido(Moneda.ARS).toDatos().moneda()).isEqualTo(Moneda.ARS);
        // Con moneda en el pedido manda el pedido, sea cual sea la actual.
        assertThat(pedido(Moneda.USD).toDatos(false, Moneda.ARS).moneda()).isEqualTo(Moneda.USD);
        assertThat(pedido(Moneda.ARS).toDatos(false, Moneda.USD).moneda()).isEqualTo(Moneda.ARS);
    }

    @Test
    @DisplayName("la moneda no cambia el resto de los datos: el costo viaja tal cual, sin convertir")
    void elCostoViajaTalCual() {
        DatosArticulo datos = pedido(Moneda.USD).toDatos();

        assertThat(datos.costo()).isEqualByComparingTo("10");
        assertThat(datos.alicuotaIva()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(datos.descripcion()).isEqualTo("Tornillo");
    }

    @Test
    @DisplayName("la respuesta trae la moneda del articulo: USD para uno en dolares y ARS para uno en pesos")
    void laRespuestaTraeLaMoneda() {
        Articulo enDolares = new Articulo(3L);
        enDolares.actualizar(pedido(Moneda.USD).toDatos(),
                new ContextoPrecio(null, bd("30.00"), bd("10.00"), bd("1000")));
        Articulo enPesos = new Articulo(3L);
        enPesos.actualizar(pedido(Moneda.ARS).toDatos(), new ContextoPrecio(null, bd("30.00"), bd("10.00")));

        ArticuloResponse usd = ArticuloResponse.from(enDolares);
        ArticuloResponse ars = ArticuloResponse.from(enPesos);

        assertThat(usd.moneda()).isEqualTo(Moneda.USD);
        assertThat(usd.costo()).isEqualByComparingTo("10");
        assertThat(usd.precioFinal()).isEqualByComparingTo("15730");
        assertThat(ars.moneda()).isEqualTo(Moneda.ARS);
        // 10 x 1,30 x 1,21 = 15,73 -> multiplo de 10: 20
        assertThat(ars.precioFinal()).isEqualByComparingTo("20");
        assertThat(ArticuloResponse.from(enDolares, bd("3")).moneda()).isEqualTo(Moneda.USD);
    }
}
