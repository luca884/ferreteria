package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * El tick por minuto del recalculo diario (T-031), con dobles: recorre las ferreterias ACTIVAS, cada una con su propio
 * TenantContext, y lo deja limpio pase lo que pase. Las 11:00 UTC son las 08:00 de Buenos Aires.
 */
class RecalculoDiarioJobTest {

    private static final Instant ONCE_UTC = Instant.parse("2026-10-16T11:00:00Z");
    private static final LocalDateTime LAS_OCHO_EN_BUENOS_AIRES = LocalDateTime.of(2026, 10, 16, 8, 0);

    private final FerreteriaRepository ferreterias = mock(FerreteriaRepository.class);
    private final RecalculoConReintento servicio = mock(RecalculoConReintento.class);

    private RecalculoDiarioJob job(Instant instante) {
        return new RecalculoDiarioJob(ferreterias, servicio, Clock.fixed(instante, ZoneOffset.UTC));
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static RecalculoCotizacionResponse respuesta() {
        return new RecalculoCotizacionResponse(new BigDecimal("1100"), 1, java.util.List.of());
    }

    @Test
    @DisplayName("corre cada minuto, en hora de Buenos Aires: cron «0 * * * * *» con zona America/Argentina/Buenos_Aires")
    void cadaMinutoEnHoraDeBuenosAires() throws NoSuchMethodException {
        Scheduled programado = RecalculoDiarioJob.class.getMethod("ejecutar").getAnnotation(Scheduled.class);

        assertThat(programado).isNotNull();
        assertThat(programado.cron()).isEqualTo("0 * * * * *");
        assertThat(programado.zone()).isEqualTo("America/Argentina/Buenos_Aires");
    }

    @Test
    @DisplayName("recorre las ferreterias activas: cada una con SU TenantContext y la hora de Buenos Aires")
    void recorreCadaFerreteriaConSuTenant() {
        when(ferreterias.idsActivas()).thenReturn(List.of(5L, 6L, 7L));
        List<String> vistas = new ArrayList<>();
        when(servicio.recalcularDelDia(any())).thenAnswer(invocacion -> {
            vistas.add(TenantContext.requireFerreteriaId() + "@" + invocacion.getArgument(0));
            return Optional.empty();
        });

        job(ONCE_UTC).ejecutar();

        assertThat(vistas).containsExactly(
                "5@" + LAS_OCHO_EN_BUENOS_AIRES, "6@" + LAS_OCHO_EN_BUENOS_AIRES, "7@" + LAS_OCHO_EN_BUENOS_AIRES);
        assertThat(TenantContext.currentFerreteriaId()).isEmpty();
    }

    @Test
    @DisplayName("la hora se convierte a Buenos Aires: 11:00 UTC son las 08:00 y 02:30 UTC son las 23:30 del dia anterior")
    void conviertEDeUtcABuenosAires() {
        when(ferreterias.idsActivas()).thenReturn(List.of(5L));

        job(ONCE_UTC).ejecutar();
        verify(servicio).recalcularDelDia(LAS_OCHO_EN_BUENOS_AIRES);

        job(Instant.parse("2026-10-17T02:30:00Z")).ejecutar();
        verify(servicio).recalcularDelDia(LocalDateTime.of(2026, 10, 16, 23, 30));
    }

    @Test
    @DisplayName("si falla la ferreteria A, la B igual se recalcula y el TenantContext queda limpio (finally)")
    void unaFallaNoFrenaALasDemas() {
        when(ferreterias.idsActivas()).thenReturn(List.of(5L, 6L));
        List<Long> recalculadas = new ArrayList<>();
        when(servicio.recalcularDelDia(any())).thenAnswer(invocacion -> {
            Long ferreteria = TenantContext.requireFerreteriaId();
            if (ferreteria.equals(5L)) {
                throw new IllegalStateException("fallo A");
            }
            recalculadas.add(ferreteria);
            return Optional.of(respuesta());
        });

        assertThatCode(() -> job(ONCE_UTC).ejecutar()).doesNotThrowAnyException();

        assertThat(recalculadas).containsExactly(6L);
        assertThat(TenantContext.currentFerreteriaId()).isEmpty();
    }

    @Test
    @DisplayName("el tenant de una ferreteria no se arrastra a la siguiente aunque la anterior haya fallado")
    void elTenantNoSeArrastra() {
        when(ferreterias.idsActivas()).thenReturn(List.of(5L, 6L, 7L));
        List<Long> tenantsVistos = new ArrayList<>();
        when(servicio.recalcularDelDia(any())).thenAnswer(invocacion -> {
            tenantsVistos.add(TenantContext.requireFerreteriaId());
            throw new IllegalStateException("todas fallan");
        });

        job(ONCE_UTC).ejecutar();

        assertThat(tenantsVistos).containsExactly(5L, 6L, 7L);
        assertThat(TenantContext.currentFerreteriaId()).isEmpty();
    }

    @Test
    @DisplayName("sin ferreterias activas no recalcula nada")
    void sinFerreteriasNoHaceNada() {
        when(ferreterias.idsActivas()).thenReturn(List.of());

        job(ONCE_UTC).ejecutar();

        verify(servicio, never()).recalcularDelDia(any());
    }

    @Test
    @DisplayName("si falla la lectura de las ferreterias no sale la excepcion: no mata al scheduler")
    void unaFallaAlListarNoSale() {
        when(ferreterias.idsActivas()).thenThrow(new IllegalStateException("la base no responde"));

        assertThatCode(() -> job(ONCE_UTC).ejecutar()).doesNotThrowAnyException();
        verify(servicio, never()).recalcularDelDia(any());
    }
}
