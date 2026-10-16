package com.ferreteria.cotizacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.web.dto.ConfiguracionCotizacionRequest;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Valores por defecto y validacion de la configuracion de cotizacion de la ferreteria del request. */
class ConfiguracionCotizacionServiceTest {

    private static final Long FERRETERIA = 7L;

    private final ConfiguracionCotizacionRepository repositorio = mock(ConfiguracionCotizacionRepository.class);
    private final ConfiguracionCotizacionService servicio = new ConfiguracionCotizacionService(repositorio);

    /** Lo que hay "en la base": una sola fila, o ninguna. */
    private ConfiguracionCotizacion guardada;

    @BeforeEach
    void repositorioEnMemoria() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        when(repositorio.findPropiaScoped()).thenAnswer(i -> Optional.ofNullable(guardada));
        when(repositorio.save(any())).thenAnswer(i -> {
            guardada = i.getArgument(0);
            return guardada;
        });
    }

    @AfterEach
    void limpiarTenant() {
        TenantContext.clear();
    }

    private static ConfiguracionCotizacionRequest pedido(String dolarPrecios, LadoCotizacion lado, String hora,
                                                         String... visibles) {
        return new ConfiguracionCotizacionRequest(true, dolarPrecios, lado, new ArrayList<>(Arrays.asList(visibles)),
                hora);
    }

    private static ConfiguracionCotizacionRequest valido() {
        return pedido("USD:oficial", LadoCotizacion.VENTA, "08:00", "USD:oficial");
    }

    private List<String> erroresDe(ConfiguracionCotizacionRequest pedido, String campo) {
        DatosInvalidosException e = org.junit.jupiter.api.Assertions.assertThrows(DatosInvalidosException.class,
                () -> servicio.actualizar(pedido));
        assertThat(e.errors()).containsKey(campo);
        return List.of(e.errors().get(campo));
    }

    // ---- obtener ----

    @Test
    @DisplayName("sin fila propia, obtener crea y devuelve activada, oficial venta, 08:00 y visibles USD:oficial")
    void obtenerCreaLosValoresPorDefecto() {
        ConfiguracionCotizacion config = servicio.obtener();

        assertThat(config.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(config.isActiva()).isTrue();
        assertThat(config.getCasaPrecios()).isEqualTo("oficial");
        assertThat(config.getLadoPrecios()).isEqualTo(LadoCotizacion.VENTA);
        assertThat(config.getHoraRecalculo()).isEqualTo(LocalTime.of(8, 0));
        assertThat(config.getVisibles()).containsExactly("USD:oficial");
        assertThat(config.getVigente()).isNull();
        verify(repositorio, times(1)).save(any());
        assertThat(guardada).isSameAs(config);
    }

    @Test
    @DisplayName("con fila propia, obtener devuelve esa misma y no crea otra")
    void obtenerDevuelveLaExistente() {
        guardada = ConfiguracionCotizacion.porDefecto(FERRETERIA);
        guardada.setHoraRecalculo(LocalTime.of(9, 30));

        ConfiguracionCotizacion config = servicio.obtener();

        assertThat(config).isSameAs(guardada);
        assertThat(config.getHoraRecalculo()).isEqualTo(LocalTime.of(9, 30));
        verify(repositorio, never()).save(any());
    }

    @Test
    @DisplayName("leer sin fila propia devuelve los valores por defecto sin guardar nada")
    void leerNoCrea() {
        ConfiguracionCotizacion config = servicio.leer();

        assertThat(config.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(config.isActiva()).isTrue();
        assertThat(config.getCasaPrecios()).isEqualTo("oficial");
        verify(repositorio, never()).save(any());
        assertThat(guardada).isNull();
    }

    // ---- actualizar ----

    @Test
    @DisplayName("actualizar con oficial compra y releer da oficial compra, con el resto de lo pedido")
    void actualizarYReleer() {
        ConfiguracionCotizacionRequest pedido = new ConfiguracionCotizacionRequest(false, "USD:oficial",
                LadoCotizacion.COMPRA, List.of("USD:oficial", "USD:blue"), "09:30");

        servicio.actualizar(pedido);
        ConfiguracionCotizacion releida = servicio.obtener();

        assertThat(releida.getCasaPrecios()).isEqualTo("oficial");
        assertThat(releida.getLadoPrecios()).isEqualTo(LadoCotizacion.COMPRA);
        assertThat(releida.isActiva()).isFalse();
        assertThat(releida.getVisibles()).containsExactly("USD:oficial", "USD:blue");
        assertThat(releida.getHoraRecalculo()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    @DisplayName("el dolar de precios puede ser otro tipo de dolar, por ejemplo el MEP")
    void dolarDePreciosMep() {
        servicio.actualizar(pedido("USD:bolsa", LadoCotizacion.VENTA, "08:00", "USD:oficial", "USD:bolsa"));

        assertThat(servicio.obtener().getCasaPrecios()).isEqualTo("bolsa");
    }

    @Test
    @DisplayName("visibles con USD:oficial y EUR:oficial se acepta")
    void visiblesConEuro() {
        servicio.actualizar(pedido("USD:oficial", LadoCotizacion.VENTA, "08:00", "USD:oficial", "EUR:oficial",
                "BRL:oficial"));

        assertThat(servicio.obtener().getVisibles()).containsExactly("USD:oficial", "EUR:oficial", "BRL:oficial");
    }

    @Test
    @DisplayName("los visibles repetidos se guardan una sola vez y en el orden pedido")
    void visiblesSinRepetir() {
        servicio.actualizar(pedido("USD:oficial", LadoCotizacion.VENTA, "08:00", "USD:blue", "USD:oficial", "USD:blue"));

        assertThat(servicio.obtener().getVisibles()).containsExactly("USD:blue", "USD:oficial");
    }

    @Test
    @DisplayName("la hora se guarda tal cual: 00:00 y 23:59 son horas validas")
    void horasLimite() {
        servicio.actualizar(pedido("USD:oficial", LadoCotizacion.VENTA, "00:00", "USD:oficial"));
        assertThat(servicio.obtener().getHoraRecalculo()).isEqualTo(LocalTime.of(0, 0));

        servicio.actualizar(pedido("USD:oficial", LadoCotizacion.VENTA, "23:59", "USD:oficial"));
        assertThat(servicio.obtener().getHoraRecalculo()).isEqualTo(LocalTime.of(23, 59));
    }

    @Test
    @DisplayName("guardar la configuracion no modifica el dolar vigente (se aplica en el proximo recalculo)")
    void guardarNoTocaElVigente() {
        guardada = ConfiguracionCotizacion.porDefecto(FERRETERIA);
        Instant desde = Instant.parse("2026-10-16T11:00:00Z");
        guardada.fijarVigente(new BigDecimal("1000.0000"), "oficial", LadoCotizacion.VENTA, desde);

        servicio.actualizar(pedido("USD:oficial", LadoCotizacion.COMPRA, "08:00", "USD:oficial"));

        assertThat(guardada.getLadoPrecios()).isEqualTo(LadoCotizacion.COMPRA);
        assertThat(guardada.getVigente()).isEqualByComparingTo("1000");
        assertThat(guardada.getVigenteCasa()).isEqualTo("oficial");
        assertThat(guardada.getVigenteLado()).isEqualTo(LadoCotizacion.VENTA);
        assertThat(guardada.getVigenteDesde()).isEqualTo(desde);
    }

    // ---- validaciones ----

    @Test
    @DisplayName("dos tipos de dolar a la vez para los precios: error en espanol y no cambia nada")
    void dosTiposDeDolar() {
        guardada = ConfiguracionCotizacion.porDefecto(FERRETERIA);

        List<String> mensajes = erroresDe(
                pedido("USD:oficial,USD:blue", LadoCotizacion.VENTA, "09:00", "USD:oficial"), "dolarPrecios");

        assertThat(mensajes).containsExactly("Elegí un solo dólar para los precios.");
        assertThat(guardada.getHoraRecalculo()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    @DisplayName("euro oficial o real oficial como dolar de precios: error, porque solo sirven para mostrar")
    void euroYRealNoSonDolarDePrecios() {
        guardada = ConfiguracionCotizacion.porDefecto(FERRETERIA);

        assertThat(erroresDe(pedido("EUR:oficial", LadoCotizacion.VENTA, "08:00", "USD:oficial"), "dolarPrecios"))
                .containsExactly("El dólar de los precios tiene que ser un tipo de dólar. El euro y el real son solo para mostrar.");
        assertThat(erroresDe(pedido("BRL:oficial", LadoCotizacion.VENTA, "08:00", "USD:oficial"), "dolarPrecios"))
                .containsExactly("El dólar de los precios tiene que ser un tipo de dólar. El euro y el real son solo para mostrar.");
        assertThat(guardada.getCasaPrecios()).isEqualTo("oficial");
    }

    @Test
    @DisplayName("un dolar de precios fuera del catalogo o ausente es un error")
    void dolarDePreciosInvalido() {
        assertThat(erroresDe(pedido("USD:inventado", LadoCotizacion.VENTA, "08:00", "USD:oficial"), "dolarPrecios"))
                .hasSize(1);
        assertThat(erroresDe(pedido(null, LadoCotizacion.VENTA, "08:00", "USD:oficial"), "dolarPrecios"))
                .containsExactly("Elegí el dólar en el que cotizan los precios.");
    }

    @Test
    @DisplayName("hora 25:00 o con otro formato: error en espanol y no cambia nada")
    void horaInvalida() {
        guardada = ConfiguracionCotizacion.porDefecto(FERRETERIA);

        for (String hora : new String[] {"25:00", "8:00", "08:60", "ocho", "", "08:00:30"}) {
            assertThat(erroresDe(pedido("USD:oficial", LadoCotizacion.VENTA, hora, "USD:oficial"), "horaRecalculo"))
                    .containsExactly("La hora tiene que tener el formato HH:mm, por ejemplo 08:00.");
        }
        assertThat(erroresDe(pedido("USD:oficial", LadoCotizacion.VENTA, null, "USD:oficial"), "horaRecalculo"))
                .containsExactly("La hora tiene que tener el formato HH:mm, por ejemplo 08:00.");
        assertThat(guardada.getHoraRecalculo()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    @DisplayName("visibles vacio o ausente: error, hay que elegir al menos una cotizacion")
    void visiblesVacio() {
        assertThat(erroresDe(pedido("USD:oficial", LadoCotizacion.VENTA, "08:00"), "visibles"))
                .containsExactly("Elegí al menos una cotización para mostrar.");
        assertThat(erroresDe(new ConfiguracionCotizacionRequest(true, "USD:oficial", LadoCotizacion.VENTA, null,
                "08:00"), "visibles")).containsExactly("Elegí al menos una cotización para mostrar.");
    }

    @Test
    @DisplayName("un elemento visible fuera del catalogo: error que lo nombra")
    void visibleDesconocido() {
        assertThat(erroresDe(pedido("USD:oficial", LadoCotizacion.VENTA, "08:00", "USD:oficial", "USD:inventado"),
                "visibles")).containsExactly("La cotización «USD:inventado» no existe.");
    }

    @Test
    @DisplayName("sin lado (compra o venta) o sin indicar si esta activada: error")
    void ladoYActivaObligatorios() {
        assertThat(erroresDe(pedido("USD:oficial", null, "08:00", "USD:oficial"), "ladoPrecios"))
                .containsExactly("Elegí si los precios usan la compra o la venta.");
        assertThat(erroresDe(new ConfiguracionCotizacionRequest(null, "USD:oficial", LadoCotizacion.VENTA,
                List.of("USD:oficial"), "08:00"), "activa")).containsExactly("Indicá si la cotización está activada.");
    }

    @Test
    @DisplayName("varios errores juntos se informan todos y nada se guarda")
    void variosErrores() {
        DatosInvalidosException e = org.junit.jupiter.api.Assertions.assertThrows(DatosInvalidosException.class,
                () -> servicio.actualizar(pedido("EUR:oficial", LadoCotizacion.VENTA, "25:00")));

        assertThat(e.errors()).containsOnlyKeys("dolarPrecios", "horaRecalculo", "visibles");
        verify(repositorio, never()).save(any());
        assertThatThrownBy(() -> servicio.actualizar(pedido("EUR:oficial", LadoCotizacion.VENTA, "25:00")))
                .isInstanceOf(DatosInvalidosException.class);
    }

    @Test
    @DisplayName("un pedido valido sobre una ferreteria sin fila crea la fila con lo pedido")
    void actualizarCreaLaFila() {
        servicio.actualizar(valido());

        assertThat(guardada).isNotNull();
        assertThat(guardada.getFerreteriaId()).isEqualTo(FERRETERIA);
    }
}
