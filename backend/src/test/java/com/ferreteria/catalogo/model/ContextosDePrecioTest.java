package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class ContextosDePrecioTest {

    private static final BigDecimal MARGEN_DEFECTO = new BigDecimal("30.00");
    private static final BigDecimal MULTIPLO = new BigDecimal("10.00");

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Rubro rubro(long id, String margen) throws Exception {
        Rubro rubro = new Rubro(1L, "Rubro " + id, bd(margen));
        Field campo = Rubro.class.getDeclaredField("id");
        campo.setAccessible(true);
        campo.set(rubro, id);
        return rubro;
    }

    @Test
    @DisplayName("un contexto por rubro, con el margen del rubro, el margen por defecto y el multiplo")
    void unContextoPorRubro() throws Exception {
        ContextosDePrecio contextos = ContextosDePrecio.armar(
            List.of(rubro(7, "40.00"), rubro(9, "25.50")), MARGEN_DEFECTO, MULTIPLO);

        assertThat(contextos.de(7L)).isEqualTo(new ContextoPrecio(bd("40.00"), MARGEN_DEFECTO, MULTIPLO));
        assertThat(contextos.de(9L)).isEqualTo(new ContextoPrecio(bd("25.50"), MARGEN_DEFECTO, MULTIPLO));
        assertThat(contextos.porRubro()).containsOnlyKeys(7L, 9L);
    }

    @Test
    @DisplayName("de(null) devuelve el contexto sin rubro: sin margen de rubro")
    void sinRubro() throws Exception {
        ContextosDePrecio contextos = ContextosDePrecio.armar(List.of(rubro(7, "40.00")), MARGEN_DEFECTO, MULTIPLO);

        assertThat(contextos.de(null)).isEqualTo(new ContextoPrecio(null, MARGEN_DEFECTO, MULTIPLO));
        assertThat(contextos.sinRubro()).isEqualTo(new ContextoPrecio(null, MARGEN_DEFECTO, MULTIPLO));
    }

    @Test
    @DisplayName("sin rubros en la ferreteria: solo existe el contexto sin rubro")
    void sinRubrosEnLaFerreteria() {
        ContextosDePrecio contextos = ContextosDePrecio.armar(List.of(), MARGEN_DEFECTO, MULTIPLO);

        assertThat(contextos.porRubro()).isEmpty();
        assertThat(contextos.de(null).margenDefecto()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("un rubro desconocido es un error de programacion: IllegalStateException con el id")
    void rubroDesconocido() throws Exception {
        ContextosDePrecio contextos = ContextosDePrecio.armar(List.of(rubro(7, "40.00")), MARGEN_DEFECTO, MULTIPLO);

        assertThatThrownBy(() -> contextos.de(99L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Falta el contexto de precio del rubro 99");
    }

    @Test
    @DisplayName("el mapa es inmutable")
    void mapaInmutable() throws Exception {
        ContextosDePrecio contextos = ContextosDePrecio.armar(List.of(rubro(7, "40.00")), MARGEN_DEFECTO, MULTIPLO);

        assertThatThrownBy(() -> contextos.porRubro().put(8L, contextos.sinRubro()))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
