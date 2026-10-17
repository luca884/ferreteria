package com.ferreteria.cotizacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.model.DolarAConfirmar;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.cotizacion.web.dto.CotizacionResponse;
import com.ferreteria.cotizacion.web.dto.TipoCotizacionResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El estado de la cotizacion que ve cada ferreteria: con Clock fijo y repositorios falsos, sin base ni red. */
class CotizacionServiceTest {

    /** Sabado 2026-10-17 12:00 en Buenos Aires. */
    private static final Instant AHORA = Instant.parse("2026-10-17T15:00:00Z");
    /** Viernes 2026-10-16 19:00 en Buenos Aires: la ultima fecha de mercado del fin de semana. */
    private static final Instant VIERNES_19 = Instant.parse("2026-10-16T22:00:00Z");

    private final ConfiguracionCotizacionService configuracionService = mock(ConfiguracionCotizacionService.class);
    private final CotizacionRepository cotizaciones = mock(CotizacionRepository.class);
    private final DolarDePreciosService dolar = mock(DolarDePreciosService.class);
    private final CotizacionService servicio = new CotizacionService(configuracionService, cotizaciones, dolar,
            Clock.fixed(AHORA, ZoneOffset.UTC));

    private final Map<String, Cotizacion> almacen = new HashMap<>();
    private ConfiguracionCotizacion configuracion = ConfiguracionCotizacion.porDefecto(7L);

    @BeforeEach
    void preparar() {
        when(configuracionService.leer()).thenAnswer(i -> configuracion);
        when(cotizaciones.findByMonedaAndCasa(any(), any())).thenAnswer(
                i -> Optional.ofNullable(almacen.get(i.getArgument(0) + ":" + i.getArgument(1))));
        when(cotizaciones.ultimaObtenidaEn()).thenAnswer(i -> almacen.values().stream()
                .map(Cotizacion::getObtenidaEn).max(Instant::compareTo));
        when(dolar.vigente()).thenReturn(Optional.of(new BigDecimal("1000.0000")));
    }

    private void guardar(String moneda, String casa, String compra, String venta, Instant mercado, Instant obtenida) {
        almacen.put(moneda + ":" + casa, new Cotizacion(moneda, casa, "Guardado " + casa,
                new BigDecimal(compra), new BigDecimal(venta), "DOLARAPI", mercado, obtenida));
    }

    private static TipoCotizacionResponse tipo(CotizacionResponse r, String codigo) {
        return r.visibles().stream().filter(t -> t.codigo().equals(codigo)).findFirst()
                .orElseThrow(() -> new AssertionError("Falta " + codigo + " en " + r.visibles()));
    }

    // ---- dolar a confirmar (auditoria H09) ----

    @Test
    @DisplayName("con un dolar a confirmar, precios trae el vigente aplicado y aparte el valor nuevo")
    void preciosConDolarAConfirmar() {
        guardar("USD", "oficial", "14", "14.5", AHORA, AHORA);
        when(dolar.vigente()).thenReturn(Optional.of(new BigDecimal("1535.0000")));
        when(dolar.aConfirmar()).thenReturn(Optional.of(DolarAConfirmar.de("oficial", LadoCotizacion.VENTA,
                new BigDecimal("1535.0000"), new BigDecimal("14.5000"))));

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(respuesta.precios().valor()).isEqualByComparingTo("1535");
        assertThat(respuesta.precios().aConfirmar()).isEqualByComparingTo("14.5");
        assertThat(tipo(respuesta, "USD:oficial").venta()).isEqualByComparingTo("14.5");
    }

    @Test
    @DisplayName("sin dolar a confirmar, aConfirmar viene vacio")
    void preciosSinDolarAConfirmar() {
        guardar("USD", "oficial", "950", "1000", AHORA, AHORA);
        when(dolar.aConfirmar()).thenReturn(Optional.empty());

        assertThat(servicio.obtener().precios().aConfirmar()).isNull();
    }

    // ---- desactualizada (R-007) ----

    @Test
    @DisplayName("desactualizada es false a 59 minutos de la ultima respuesta y true a 61; el valor no cambia")
    void desactualizadaAlos59Y61Minutos() {
        guardar("USD", "oficial", "950", "1000", AHORA.minus(Duration.ofMinutes(59)), AHORA.minus(Duration.ofMinutes(59)));
        CotizacionResponse a59 = servicio.obtener();

        guardar("USD", "oficial", "950", "1000", AHORA.minus(Duration.ofMinutes(61)), AHORA.minus(Duration.ofMinutes(61)));
        CotizacionResponse a61 = servicio.obtener();

        assertThat(a59.desactualizada()).isFalse();
        assertThat(a61.desactualizada()).isTrue();
        assertThat(tipo(a59, "USD:oficial").venta()).isEqualByComparingTo("1000");
        assertThat(tipo(a61, "USD:oficial").venta()).isEqualByComparingTo("1000");
        assertThat(a61.precios().valor()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("exactamente una hora todavia no es 'mas de 1 hora'")
    void exactamenteUnaHora() {
        guardar("USD", "oficial", "950", "1000", AHORA.minus(Duration.ofHours(1)), AHORA.minus(Duration.ofHours(1)));

        assertThat(servicio.obtener().desactualizada()).isFalse();
    }

    @Test
    @DisplayName("sabado con fecha de mercado del viernes 19:00 y respuesta exitosa hace 10 minutos: no esta desactualizada")
    void finDeSemanaSeMideConLaRespuestaNoConElMercado() {
        guardar("USD", "oficial", "950", "1000", VIERNES_19, AHORA.minus(Duration.ofMinutes(10)));

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(respuesta.desactualizada()).isFalse();
        assertThat(tipo(respuesta, "USD:oficial").desactualizada()).isFalse();
        assertThat(tipo(respuesta, "USD:oficial").actualizadaEn()).isEqualTo(VIERNES_19);
    }

    @Test
    @DisplayName("ultimaRespuesta es el MAX(obtenida_en) de toda la tabla, no la de un tipo")
    void ultimaRespuestaEsElMaximo() {
        Instant reciente = AHORA.minus(Duration.ofMinutes(3));
        guardar("USD", "oficial", "950", "1000", VIERNES_19, AHORA.minus(Duration.ofMinutes(20)));
        guardar("USD", "blue", "1180", "1200", VIERNES_19, reciente);

        assertThat(servicio.obtener().ultimaRespuesta()).isEqualTo(reciente);
    }

    @Test
    @DisplayName("R-029: cada fila trae su desactualizada; el MEP de hace 2 horas queda marcado aunque el oficial este fresco")
    void desactualizadaPorFila() {
        configuracion.setVisibles(List.of("USD:oficial", "USD:bolsa"));
        guardar("USD", "oficial", "948", "998", AHORA.minus(Duration.ofMinutes(5)), AHORA.minus(Duration.ofMinutes(5)));
        guardar("USD", "bolsa", "1040", "1050", AHORA.minus(Duration.ofHours(2)), AHORA.minus(Duration.ofHours(2)));

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(respuesta.desactualizada()).isFalse();
        assertThat(tipo(respuesta, "USD:oficial").desactualizada()).isFalse();
        assertThat(tipo(respuesta, "USD:bolsa").desactualizada()).isTrue();
        assertThat(tipo(respuesta, "USD:bolsa").venta()).isEqualByComparingTo("1050");
    }

    // ---- visibles y precios ----

    @Test
    @DisplayName("visibles sale de la configuracion, en su orden, con esDePrecios en el tipo elegido")
    void visiblesYEsDePrecios() {
        configuracion.setVisibles(List.of("USD:blue", "USD:oficial"));
        configuracion.setCasaPrecios("oficial");
        guardar("USD", "oficial", "950", "1000", VIERNES_19, AHORA.minusSeconds(60));
        guardar("USD", "blue", "1180", "1200", VIERNES_19, AHORA.minusSeconds(60));
        guardar("USD", "bolsa", "1040", "1050", VIERNES_19, AHORA.minusSeconds(60));

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(respuesta.visibles()).extracting(TipoCotizacionResponse::codigo).containsExactly("USD:blue", "USD:oficial");
        assertThat(tipo(respuesta, "USD:oficial").esDePrecios()).isTrue();
        assertThat(tipo(respuesta, "USD:blue").esDePrecios()).isFalse();
        TipoCotizacionResponse oficial = tipo(respuesta, "USD:oficial");
        assertThat(oficial.compra()).isEqualByComparingTo("950");
        assertThat(oficial.venta()).isEqualByComparingTo("1000");
        assertThat(oficial.fuente()).isEqualTo("DOLARAPI");
        assertThat(oficial.obtenidaEn()).isEqualTo(AHORA.minusSeconds(60));
        assertThat(oficial.nombre()).isEqualTo("Guardado oficial");
    }

    @Test
    @DisplayName("precios trae el tipo, el lado, el valor vigente de la ferreteria y desde cuando")
    void precios() {
        configuracion.setCasaPrecios("bolsa");
        configuracion.setLadoPrecios(LadoCotizacion.COMPRA);
        Instant desde = Instant.parse("2026-10-17T11:00:00Z");
        configuracion.fijarVigente(new BigDecimal("1040.0000"), "bolsa", LadoCotizacion.COMPRA, desde);
        when(dolar.vigente()).thenReturn(Optional.of(new BigDecimal("1040.0000")));
        guardar("USD", "oficial", "950", "1000", VIERNES_19, AHORA.minusSeconds(60));

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(respuesta.precios().codigo()).isEqualTo("USD:bolsa");
        assertThat(respuesta.precios().lado()).isEqualTo(LadoCotizacion.COMPRA);
        assertThat(respuesta.precios().valor()).isEqualByComparingTo("1040");
        assertThat(respuesta.precios().desde()).isEqualTo(desde);
    }

    @Test
    @DisplayName("incluye si la cotizacion de la ferreteria esta activa")
    void incluyeActiva() {
        guardar("USD", "oficial", "950", "1000", VIERNES_19, AHORA.minusSeconds(60));
        assertThat(servicio.obtener().activa()).isTrue();

        configuracion.setActiva(false);
        assertThat(servicio.obtener().activa()).isFalse();
    }

    @Test
    @DisplayName("euro y real aparecen si son visibles")
    void euroYReal() {
        configuracion.setVisibles(List.of("USD:oficial", "EUR:oficial", "BRL:oficial"));
        guardar("USD", "oficial", "950", "1000", VIERNES_19, AHORA.minusSeconds(60));
        guardar("EUR", "oficial", "1100", "1150", VIERNES_19, AHORA.minusSeconds(60));
        guardar("BRL", "oficial", "170", "180", VIERNES_19, AHORA.minusSeconds(60));

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(tipo(respuesta, "EUR:oficial").venta()).isEqualByComparingTo("1150");
        assertThat(tipo(respuesta, "BRL:oficial").compra()).isEqualByComparingTo("170");
        assertThat(tipo(respuesta, "EUR:oficial").esDePrecios()).isFalse();
    }

    @Test
    @DisplayName("sin ninguna cotizacion guardada devuelve los visibles con valor vacio, sin lanzar y sin aviso")
    void sinNingunaCotizacion() {
        configuracion.setVisibles(List.of("USD:oficial", "EUR:oficial"));
        when(dolar.vigente()).thenReturn(Optional.empty());

        CotizacionResponse respuesta = servicio.obtener();

        assertThat(respuesta.visibles()).hasSize(2);
        TipoCotizacionResponse oficial = tipo(respuesta, "USD:oficial");
        assertThat(oficial.compra()).isNull();
        assertThat(oficial.venta()).isNull();
        assertThat(oficial.obtenidaEn()).isNull();
        assertThat(oficial.nombre()).isEqualTo("Oficial");
        assertThat(tipo(respuesta, "EUR:oficial").nombre()).isEqualTo("Euro");
        assertThat(respuesta.ultimaRespuesta()).isNull();
        assertThat(respuesta.desactualizada()).isFalse();
        assertThat(respuesta.precios().valor()).isNull();
    }
}
