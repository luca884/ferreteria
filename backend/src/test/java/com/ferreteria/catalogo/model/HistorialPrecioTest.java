package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.HoraArgentina;
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
        // Con la hora de la app (Buenos Aires, recortada a microsegundos): LocalDateTime.now() usa la zona de la JVM y,
        // en Linux, trae nanosegundos, así que fallaba en un servidor en UTC.
        LocalDateTime antes = HoraArgentina.ahora();

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.MANUAL, 3L);

        assertThat(historial.getFecha()).isBetween(antes, HoraArgentina.ahora());
    }

    @Test
    @DisplayName("de(): el recalculo automatico por cotizacion no tiene usuario")
    void sinUsuarioEnElRecalculoAutomatico() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(bd("10.00"), bd("10.00"), bd("15730.00"), bd("17310.00"));

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.COTIZACION, null);

        assertThat(historial.getUsuarioId()).isNull();
        assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.COTIZACION);
        assertThat(historial.getPrecioAnterior()).isEqualByComparingTo("15730");
        assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("de(): el recalculo manual por cotizacion queda a nombre del usuario")
    void conUsuarioEnElRecalculoManual() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(bd("10.00"), bd("10.00"), bd("15730.00"), bd("17310.00"));

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.COTIZACION, 3L);

        assertThat(historial.getUsuarioId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("de(): guarda la moneda del articulo: USD para uno en dolares, ARS para uno en pesos")
    void guardaLaMonedaDelArticulo() throws Exception {
        Articulo enPesos = articulo(7L, 55L);
        Articulo enDolares = articulo(7L, 56L);
        enDolares.actualizar(new DatosArticulo("USD-001", null, null, "En dolares", null, null, bd("10"), null,
                AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("0"), null, true, Moneda.USD),
                new ContextoPrecio(null, bd("30"), bd("10"), bd("1000")));
        CambioPrecio cambio = new CambioPrecio(null, bd("10.00"), null, bd("15730.00"));

        assertThat(HistorialPrecio.de(enPesos, cambio, OrigenPrecio.MANUAL, 3L).getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(HistorialPrecio.de(enDolares, cambio, OrigenPrecio.MANUAL, 3L).getMoneda()).isEqualTo(Moneda.USD);
    }

    // --- corte 2: moneda anterior en un cambio de moneda ---

    @Test
    @DisplayName("de() con monedaAnterior guarda las dos monedas: la anterior ARS y la nueva USD")
    void guardaLasDosMonedas() throws Exception {
        Articulo enDolares = articulo(7L, 56L);
        enDolares.actualizar(new DatosArticulo("USD-001", null, null, "En dolares", null, null, bd("10"), null,
                AlicuotaIva.IVA_21, Unidad.UNIDAD, bd("0"), null, true, Moneda.USD),
                new ContextoPrecio(null, bd("30"), bd("10"), bd("1000")));
        CambioPrecio cambio = new CambioPrecio(bd("1000.00"), bd("10.00"), bd("1580.00"), bd("15730.00"));

        HistorialPrecio historial = HistorialPrecio.de(enDolares, cambio, OrigenPrecio.IMPORTACION, 3L, Moneda.ARS);

        assertThat(historial.getMonedaAnterior()).isEqualTo(Moneda.ARS);
        assertThat(historial.getMoneda()).isEqualTo(Moneda.USD);
        assertThat(historial.getCostoAnterior()).isEqualByComparingTo("1000");
        assertThat(historial.getCostoNuevo()).isEqualByComparingTo("10");
        assertThat(historial.getPrecioAnterior()).isEqualByComparingTo("1580");
        assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("15730");
        assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.IMPORTACION);
        assertThat(historial.getUsuarioId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("de() de siempre y de() con monedaAnterior null dejan la moneda anterior en null")
    void sinCambioDeMonedaLaAnteriorEsNull() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(bd("100.00"), bd("200.00"), bd("170.00"), bd("330.00"));

        assertThat(HistorialPrecio.de(articulo, cambio, OrigenPrecio.MANUAL, 3L).getMonedaAnterior()).isNull();
        assertThat(HistorialPrecio.de(articulo, cambio, OrigenPrecio.IMPORTACION, 3L, null).getMonedaAnterior())
                .isNull();
    }

    @Test
    @DisplayName("de() con monedaAnterior copia el resto igual que de() de siempre")
    void conMonedaAnteriorCopiaElResto() throws Exception {
        Articulo articulo = articulo(7L, 55L);
        CambioPrecio cambio = new CambioPrecio(bd("100.00"), bd("200.00"), bd("170.00"), bd("330.00"));

        HistorialPrecio historial = HistorialPrecio.de(articulo, cambio, OrigenPrecio.IMPORTACION, null, Moneda.USD);

        assertThat(historial.getFerreteriaId()).isEqualTo(7L);
        assertThat(historial.getArticuloId()).isEqualTo(55L);
        assertThat(historial.getMoneda()).isEqualTo(Moneda.ARS);
        assertThat(historial.getMonedaAnterior()).isEqualTo(Moneda.USD);
        assertThat(historial.getUsuarioId()).isNull();
    }
}
