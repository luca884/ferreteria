package com.ferreteria.catalogo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.Unidad;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Mapeo entidad a respuesta: dominio puro, sin Spring. */
class ArticuloResponseTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Articulo articulo() {
        Articulo articulo = new Articulo(1L);
        articulo.actualizar(new DatosArticulo("TORN-001", null, null, "Tornillo", null, 20L, bd("100"), null,
                AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true),
                new ContextoPrecio(null, bd("30"), bd("10")));
        return articulo;
    }

    @Test
    @DisplayName("la respuesta trae ajuste1..3, bonificacion1..3 y listaIncluyeIva del articulo")
    void traeLaBaseYLosAjustes() {
        Articulo articulo = articulo();
        articulo.fijarAjustes(new Ajustes(bd("-5"), null, bd("3")));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), bd("5"), null), true);

        ArticuloResponse respuesta = ArticuloResponse.from(articulo);

        assertThat(respuesta.ajuste1()).isEqualByComparingTo("-5");
        assertThat(respuesta.ajuste2()).isNull();
        assertThat(respuesta.ajuste3()).isEqualByComparingTo("3");
        assertThat(respuesta.bonificacion1()).isEqualByComparingTo("10");
        assertThat(respuesta.bonificacion2()).isEqualByComparingTo("5");
        assertThat(respuesta.bonificacion3()).isNull();
        assertThat(respuesta.listaIncluyeIva()).isTrue();
    }

    @Test
    @DisplayName("la variante con el stock vigente conserva los campos nuevos")
    void conStockVigenteConservaLosCamposNuevos() {
        Articulo articulo = articulo();
        articulo.fijarAjustes(new Ajustes(bd("-5"), null, null));
        articulo.registrarBaseDeLista(new Bonificaciones(bd("10"), null, null), false);

        ArticuloResponse respuesta = ArticuloResponse.from(articulo, bd("7"));

        assertThat(respuesta.stockActual()).isEqualByComparingTo("7");
        assertThat(respuesta.ajuste1()).isEqualByComparingTo("-5");
        assertThat(respuesta.bonificacion1()).isEqualByComparingTo("10");
        assertThat(respuesta.listaIncluyeIva()).isFalse();
    }

    @Test
    @DisplayName("un articulo sin base ni ajustes devuelve todo en null")
    void sinBaseTodoNull() {
        ArticuloResponse respuesta = ArticuloResponse.from(articulo());

        assertThat(respuesta.ajuste1()).isNull();
        assertThat(respuesta.ajuste2()).isNull();
        assertThat(respuesta.ajuste3()).isNull();
        assertThat(respuesta.bonificacion1()).isNull();
        assertThat(respuesta.bonificacion2()).isNull();
        assertThat(respuesta.bonificacion3()).isNull();
        assertThat(respuesta.listaIncluyeIva()).isNull();
    }

    @Test
    @DisplayName("los campos son aditivos: van al final y los anteriores siguen en su lugar")
    void camposAditivosAlFinal() {
        String[] nombres = Arrays.stream(ArticuloResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new);

        assertThat(nombres).startsWith("id", "codigoInterno", "codigoBarras", "codigoProveedor", "descripcion", "rubroId",
                "proveedorId", "costo", "moneda", "margenPorcentaje", "alicuotaIva", "precioFinal", "unidad",
                "stockActual", "stockMinimo", "ubicacion", "activo", "precioListaProveedor");
        assertThat(nombres).endsWith("ajuste1", "ajuste2", "ajuste3", "bonificacion1", "bonificacion2", "bonificacion3",
                "listaIncluyeIva", "version", "precioManual", "ajustesPrecio", "costoAl", "oferta");
    }

    @Test
    @DisplayName("la respuesta trae la version del articulo, en los dos from (lote 5b, R-224)")
    void traeLaVersion() {
        Articulo articulo = articulo();
        org.springframework.test.util.ReflectionTestUtils.setField(articulo, "version", 3L);

        assertThat(ArticuloResponse.from(articulo).version()).isEqualTo(3L);
        assertThat(ArticuloResponse.from(articulo, bd("7")).version()).isEqualTo(3L);
    }

    @Test
    @DisplayName("ArticuloRequest NO tiene los ajustes sobre el costo ni bonificaciones: el PUT y el POST no los reciben "
            + "(los ajustes sobre el precio final de CU-14 si)")
    void elRequestNoTieneAjustes() {
        assertThat(Arrays.stream(ArticuloRequest.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(n -> n.matches("ajuste\\d") || n.startsWith("bonificacion") || n.equals("listaIncluyeIva"))
                .contains("ajustesPrecio");
    }
}
