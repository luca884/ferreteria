package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.service.RecalculoDiarioJob;
import com.ferreteria.catalogo.service.RecalculoConReintento;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El job diario contra MySQL con DOS ferreterias configuradas distinto (T-032, aislamiento): A con oficial venta y
 * hora 08:00, B con oficial compra y hora 09:30, sobre la misma cotizacion guardada (compra 950, venta 1000). Se arma el
 * job a mano con el servicio real y una lista de ferreterias fija, para no recorrer las demas que hay en la base de
 * tests. Ferreterias propias (CUIT 30-77050001-1 y 30-77050002-2) y casa propia (t32-).
 */
class RecalculoDiarioJobIntegrationTest extends CotizacionTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T09:00:00Z");
    /** 08:00 y 09:30 de Buenos Aires (UTC-3). */
    private static final Instant LAS_OCHO = Instant.parse("2026-10-16T11:00:00Z");
    private static final Instant LAS_NUEVE_Y_MEDIA = Instant.parse("2026-10-16T12:30:00Z");

    @Autowired
    RecalculoConReintento servicio;

    @Autowired
    RecalculoDiarioJob jobDelContexto;

    private Long a;
    private Long b;
    private Long articuloA;
    private Long articuloB;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-77050001-1", "Job A");
        b = ferreteria("30-77050002-2", "Job B");
        borrarCotizaciones("t32-");
        cotizacion("USD", "t32-oficial", "950", "1000", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t32-oficial"));
        configuracion(b, c -> {
            c.setCasaPrecios("t32-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
            c.setHoraRecalculo(LocalTime.of(9, 30));
        });
        // Precios viejos a proposito (10000): se distingue "no se toco" de "se recalculo".
        articuloA = articuloUsd(a, "T32-A", "10.00", "10000.00");
        articuloB = articuloUsd(b, "T32-B", "10.00", "10000.00");
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t32-");
        borrarDatosDe(a, b);
    }

    private RecalculoDiarioJob job(RecalculoConReintento recalculo, Instant instante) {
        FerreteriaRepository ferreterias = mock(FerreteriaRepository.class);
        when(ferreterias.idsActivas()).thenReturn(List.of(a, b));
        return new RecalculoDiarioJob(ferreterias, recalculo, Clock.fixed(instante, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("el job existe como bean y en los tests no hay scheduler que lo dispare solo")
    void esUnBean() {
        assertThat(jobDelContexto).isNotNull();
    }

    @Test
    @DisplayName("a las 08:00 solo se recalcula A (15.730) y B no cambia; a las 09:30 B queda en 14.950 con SU lado (compra)")
    void cadaFerreteriaASuHora() {
        job(servicio, LAS_OCHO).ejecutar();

        assertThat(precioDe(articuloA)).isEqualByComparingTo("15730");
        assertThat(precioDe(articuloB)).isEqualByComparingTo("10000");
        assertThat(historialDe(articuloB)).isEmpty();
        assertThat(configuracionDe(b).getUltimoRecalculoDia()).isNull();

        job(servicio, LAS_NUEVE_Y_MEDIA).ejecutar();

        // 10 x 950 = 9500; x 1,30 = 12350; x 1,21 = 14943,50 -> 14950
        assertThat(precioDe(articuloB)).isEqualByComparingTo("14950");
        assertThat(precioDe(articuloA)).isEqualByComparingTo("15730");
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1000");
        assertThat(configuracionDe(b).getVigente()).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("el historial del job queda con COTIZACION, USD y usuario NULL (Automatico), cada uno en su ferreteria")
    void historialSinUsuario() {
        job(servicio, LAS_NUEVE_Y_MEDIA).ejecutar();

        assertThat(historialDe(articuloA)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("COTIZACION");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat(h.get("usuario_id")).isNull();
        });
        assertThat(historialDe(articuloB)).singleElement().satisfies(h -> assertThat(h.get("usuario_id")).isNull());
        assertThat(historialDeLaFerreteria(a)).isEqualTo(1);
        assertThat(historialDeLaFerreteria(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("dos corridas el mismo dia (y al minuto siguiente) no recalculan ni duplican historial")
    void idempotente() {
        job(servicio, LAS_NUEVE_Y_MEDIA).ejecutar();
        cotizacion("USD", "t32-oficial", "1090", "1100", HACE_UN_RATO);

        job(servicio, LAS_NUEVE_Y_MEDIA).ejecutar();
        job(servicio, LAS_NUEVE_Y_MEDIA.plusSeconds(60)).ejecutar();

        assertThat(historialDe(articuloA)).hasSize(1);
        assertThat(historialDe(articuloB)).hasSize(1);
        assertThat(precioDe(articuloA)).isEqualByComparingTo("15730");
        assertThat(precioDe(articuloB)).isEqualByComparingTo("14950");
    }

    @Test
    @DisplayName("si falla A, B se recalcula igual, el reclamo del dia de A se revierte y el TenantContext queda limpio")
    void unaFallaNoFrenaALaOtra() {
        RecalculoConReintento quePierdeEnA = mock(RecalculoConReintento.class);
        when(quePierdeEnA.recalcularDelDia(any())).thenAnswer(invocacion -> {
            if (TenantContext.requireFerreteriaId().equals(a)) {
                throw new IllegalStateException("fallo forzado en A");
            }
            return servicio.recalcularDelDia(invocacion.getArgument(0));
        });

        job(quePierdeEnA, LAS_NUEVE_Y_MEDIA).ejecutar();

        assertThat(precioDe(articuloB)).isEqualByComparingTo("14950");
        assertThat(precioDe(articuloA)).isEqualByComparingTo("10000");
        assertThat(historialDe(articuloA)).isEmpty();
        assertThat(configuracionDe(b).getUltimoRecalculoDia()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(TenantContext.currentFerreteriaId()).isEmpty();

        // Al minuto siguiente A se reintenta (con el servicio real) y B no se repite.
        job(servicio, LAS_NUEVE_Y_MEDIA.plusSeconds(60)).ejecutar();
        assertThat(precioDe(articuloA)).isEqualByComparingTo("15730");
        assertThat(historialDe(articuloB)).hasSize(1);
    }

    @Test
    @DisplayName("una ferreteria con la cotizacion apagada no se recalcula aunque sea su hora")
    void apagadaNoSeRecalcula() {
        configuracion(b, c -> {
            c.setCasaPrecios("t32-oficial");
            c.setActiva(false);
            c.setHoraRecalculo(LocalTime.of(9, 30));
        });

        job(servicio, LAS_NUEVE_Y_MEDIA).ejecutar();

        assertThat(precioDe(articuloB)).isEqualByComparingTo("10000");
        assertThat(precioDe(articuloA)).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("a las 08:00 del dia siguiente vuelve a recalcular")
    void alOtroDiaVuelve() {
        job(servicio, LAS_OCHO).ejecutar();
        cotizacion("USD", "t32-oficial", "1090", "1100", HACE_UN_RATO);

        job(servicio, LAS_OCHO.plusSeconds(24 * 3600)).ejecutar();

        // 10 x 1100 = 11000; x 1,30 = 14300; x 1,21 = 17303 -> 17310
        assertThat(precioDe(articuloA)).isEqualByComparingTo("17310");
        assertThat(historialDe(articuloA)).hasSize(2);
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(LocalDate.of(2026, 10, 17));
    }
}
