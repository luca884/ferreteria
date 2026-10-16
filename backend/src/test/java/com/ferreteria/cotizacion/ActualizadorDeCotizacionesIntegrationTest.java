package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.cotizacion.service.ActualizadorDeCotizaciones;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

/**
 * El upsert del actualizador contra MySQL, con fuentes falsas (sin red). La tabla cotizacion es global y se comparte:
 * este test usa casas con el prefijo t14- y borra lo suyo antes y despues.
 */
class ActualizadorDeCotizacionesIntegrationTest extends IntegrationTestBase {

    private static final Instant AHORA = Instant.parse("2026-10-16T11:30:00Z");
    private static final Instant HACE_2_HORAS = AHORA.minusSeconds(7200);

    @Autowired
    CotizacionRepository repositorio;

    @Autowired
    TransactionOperations transaccion;

    @BeforeEach
    @AfterEach
    void limpiar() {
        jdbc.update("delete from cotizacion where casa like 't14-%'");
    }

    private ActualizadorDeCotizaciones actualizador(FuenteDeCotizaciones... fuentes) {
        return new ActualizadorDeCotizaciones(List.of(fuentes), repositorio, transaccion,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private static FuenteDeCotizaciones fuente(String nombre, List<CotizacionLeida> leidas) {
        return new FuenteDeCotizaciones() {
            @Override
            public String nombre() {
                return nombre;
            }

            @Override
            public List<CotizacionLeida> leer() {
                return leidas;
            }
        };
    }

    private static FuenteDeCotizaciones fuenteCaida(String nombre) {
        return new FuenteDeCotizaciones() {
            @Override
            public String nombre() {
                return nombre;
            }

            @Override
            public List<CotizacionLeida> leer() {
                throw new FuenteNoDisponibleException("caida");
            }
        };
    }

    private static CotizacionLeida leida(String casa, String compra, String venta) {
        return new CotizacionLeida("USD", casa, "Prueba " + casa, new BigDecimal(compra), new BigDecimal(venta), AHORA);
    }

    private void sembrar(String casa, String compra, String venta, Instant obtenida) {
        repositorio.saveAndFlush(new Cotizacion("USD", casa, "Prueba " + casa, new BigDecimal(compra),
                new BigDecimal(venta), "DOLARAPI", obtenida, obtenida));
    }

    private int filasDelTest() {
        return jdbc.queryForObject("select count(*) from cotizacion where casa like 't14-%'", Integer.class);
    }

    @Test
    @DisplayName("R-029: con DolarApi caida y Bluelytics sin MEP, el oficial pasa a 998 y el MEP sigue en 1050 con su fecha vieja")
    void tipoAusenteConservaSuFila() {
        sembrar("t14-oficial", "940", "990", HACE_2_HORAS);
        sembrar("t14-mep", "1040", "1050", HACE_2_HORAS);
        FuenteDeCotizaciones principal = fuenteCaida("DOLARAPI");
        FuenteDeCotizaciones respaldo = fuente("BLUELYTICS", List.of(leida("t14-oficial", "948", "998")));

        assertThat(actualizador(principal, respaldo).actualizar()).contains("BLUELYTICS");

        Cotizacion oficial = repositorio.findByMonedaAndCasa("USD", "t14-oficial").orElseThrow();
        assertThat(oficial.getVenta()).isEqualByComparingTo("998");
        assertThat(oficial.getFuente()).isEqualTo("BLUELYTICS");
        assertThat(oficial.getObtenidaEn()).isEqualTo(AHORA);
        Cotizacion mep = repositorio.findByMonedaAndCasa("USD", "t14-mep").orElseThrow();
        assertThat(mep.getVenta()).isEqualByComparingTo("1050");
        assertThat(mep.getFuente()).isEqualTo("DOLARAPI");
        assertThat(mep.getObtenidaEn()).isEqualTo(HACE_2_HORAS);
        assertThat(filasDelTest()).isEqualTo(2);
    }

    @Test
    @DisplayName("un segundo refresco no duplica filas: el UNIQUE se respeta porque actualiza la existente")
    void segundoRefrescoNoDuplica() {
        FuenteDeCotizaciones primera = fuente("DOLARAPI", List.of(leida("t14-oficial", "950", "1000")));
        FuenteDeCotizaciones segunda = fuente("DOLARAPI", List.of(leida("t14-oficial", "960", "1010")));

        actualizador(primera).actualizar();
        assertThat(filasDelTest()).isEqualTo(1);
        actualizador(segunda).actualizar();

        assertThat(filasDelTest()).isEqualTo(1);
        assertThat(repositorio.findByMonedaAndCasa("USD", "t14-oficial").orElseThrow().getVenta())
                .isEqualByComparingTo("1010");
    }

    @Test
    @DisplayName("obtenida_en solo cambia en lo que llego, no en lo que quedo como estaba")
    void obtenidaEnSoloDeLoQueLlego() {
        sembrar("t14-blue", "1180", "1200", HACE_2_HORAS);
        sembrar("t14-oficial", "940", "990", HACE_2_HORAS);
        FuenteDeCotizaciones fuente = fuente("DOLARAPI", List.of(leida("t14-oficial", "950", "1000")));

        actualizador(fuente).actualizar();

        assertThat(repositorio.findByMonedaAndCasa("USD", "t14-oficial").orElseThrow().getObtenidaEn()).isEqualTo(AHORA);
        assertThat(repositorio.findByMonedaAndCasa("USD", "t14-blue").orElseThrow().getObtenidaEn())
                .isEqualTo(HACE_2_HORAS);
    }

    @Test
    @DisplayName("si las dos fuentes fallan, lo guardado queda exactamente igual")
    void lasDosCaidasNoTocanNada() {
        sembrar("t14-oficial", "940", "990", HACE_2_HORAS);

        assertThat(actualizador(fuenteCaida("DOLARAPI"), fuenteCaida("BLUELYTICS")).actualizar()).isEmpty();

        Cotizacion igual = repositorio.findByMonedaAndCasa("USD", "t14-oficial").orElseThrow();
        assertThat(igual.getVenta()).isEqualByComparingTo("990");
        assertThat(igual.getObtenidaEn()).isEqualTo(HACE_2_HORAS);
    }

    @Test
    @DisplayName("el guardado es atomico: si un renglon no se puede guardar, no queda ninguno")
    void guardadoAtomico() {
        sembrar("t14-oficial", "940", "990", HACE_2_HORAS);
        CotizacionLeida buena = leida("t14-oficial", "950", "1000");
        CotizacionLeida rota = new CotizacionLeida("USD", "t14-rota", "n".repeat(61), new BigDecimal("1"),
                new BigDecimal("2"), AHORA);

        assertThatThrownBy(() -> actualizador(fuente("DOLARAPI", List.of(buena, rota))).actualizar())
                .isInstanceOf(RuntimeException.class);

        assertThat(repositorio.findByMonedaAndCasa("USD", "t14-oficial").orElseThrow().getVenta())
                .isEqualByComparingTo("990");
        assertThat(repositorio.findByMonedaAndCasa("USD", "t14-rota")).isEmpty();
    }
}
