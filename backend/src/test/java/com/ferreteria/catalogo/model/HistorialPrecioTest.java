package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class HistorialPrecioTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    /** El id lo genera la base; para el test se fija por reflexion. */
    private static Articulo articulo(Long ferreteriaId, Long id) throws Exception {
        Articulo articulo = new Articulo(ferreteriaId);
        Field campo = Articulo.class.getDeclaredField("id");
        campo.setAccessible(true);
        campo.set(articulo, id);
        return articulo;
    }

    @Test
    @DisplayName("de(): copia ferreteria y articulo, los cuatro valores, el origen y el usuario")
    void copiaLosDatos() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(bd("100.00"), bd("200.00"), bd("170.00"), bd("330.00"));

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.MANUAL, 3L);

        assertThat(historial.getFerreteriaId()).isEqualTo(7L);
        assertThat(historial.getArticuloId()).isEqualTo(55L);
        assertThat(historial.getCostoAnterior()).isEqualByComparingTo("100");
        assertThat(historial.getCostoNuevo()).isEqualByComparingTo("200");
        assertThat(historial.getPrecioAnterior()).isEqualByComparingTo("170");
        assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("330");
        assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
        assertThat(historial.getUsuarioId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("de(): en el alta los valores anteriores quedan null")
    void altaConAnterioresNull() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(null, bd("100.00"), null, bd("170.00"));

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.MANUAL, 3L);

        assertThat(historial.getCostoAnterior()).isNull();
        assertThat(historial.getPrecioAnterior()).isNull();
        assertThat(historial.getCostoNuevo()).isEqualByComparingTo("100");
        assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("170");
    }

    @Test
    @DisplayName("de(): conserva el origen MASIVO de la cascada de rubro")
    void origenMasivo() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(bd("100.00"), bd("100.00"), bd("170.00"), bd("190.00"));

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.MASIVO, 3L);

        assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.MASIVO);
    }

    @Test
    @DisplayName("de(): la fecha es el momento de creacion")
    void fechaEsAhora() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(null, bd("100.00"), null, bd("170.00"));
        LocalDateTime antes = LocalDateTime.now();

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.MANUAL, 3L);

        assertThat(historial.getFecha()).isBetween(antes, LocalDateTime.now());
    }
}
