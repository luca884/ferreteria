package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.catalogo.service.RecalculoPorCotizacionService;
import com.ferreteria.catalogo.service.RecalculoPreciosService;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * El recalculo del dia de una ferreteria contra MySQL (T-028), con {@code ahora} explicito: se reclama el dia, se recalculan
 * los articulos USD con el dolar de sus precios y el historial queda sin usuario. Ferreteria propia (CUIT 30-77030001-1)
 * y casa propia (t28-).
 */
class RecalculoDelDiaIntegrationTest extends CotizacionTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");
    private static final LocalDate HOY = LocalDate.of(2026, 10, 16);

    @Autowired
    RecalculoPorCotizacionService servicio;

    /** El mismo spy que usa RubroCascadaIntegrationTest: comparten contexto de Spring. */
    @SpyBean
    RecalculoPreciosService recalculoPreciosService;

    private Long a;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-77030001-1", "Recalculo del dia A");
        borrarCotizaciones("t28-");
        cotizacion("USD", "t28-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t28-oficial"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t28-");
        borrarDatosDe(a);
    }

    private static LocalDateTime a(int hora, int minuto) {
        return LocalDateTime.of(2026, 10, 16, hora, minuto);
    }

    private <T> T comoA(Supplier<T> accion) {
        TenantContext.setCurrentFerreteriaId(a);
        try {
            return accion.get();
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<RecalculoCotizacionResponse> recalcularDelDia(LocalDateTime ahora) {
        return comoA(() -> servicio.recalcularDelDia(ahora));
    }

    @Test
    @DisplayName("antes de la hora configurada no hace nada: ni precio, ni historial, ni marca el dia")
    void antesDeLaHoraNoHaceNada() {
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");

        assertThat(recalcularDelDia(a(7, 59))).isEmpty();

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();
    }

    @Test
    @DisplayName("a las 08:00 recalcula los USD de la ferreteria: 15.730 pasa a 17.310 con COTIZACION y usuario NULL")
    void alaHoraRecalculaSinUsuario() {
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");

        Optional<RecalculoCotizacionResponse> respuesta = recalcularDelDia(a(8, 0));

        assertThat(respuesta).hasValueSatisfying(r -> {
            assertThat(r.cotizacion()).isEqualByComparingTo("1100");
            assertThat(r.articulosRecalculados()).isEqualTo(1);
        });
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(historialDe(usd)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("COTIZACION");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat(h.get("usuario_id")).isNull();
            assertThat((BigDecimal) h.get("precio_anterior")).isEqualByComparingTo("15730");
            assertThat((BigDecimal) h.get("precio_nuevo")).isEqualByComparingTo("17310");
        });
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(HOY);
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("una segunda llamada el mismo dia no recalcula ni duplica historial, aunque el dolar haya cambiado")
    void segundaLlamadaDelDiaEsIdempotente() {
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");
        assertThat(recalcularDelDia(a(8, 0))).isPresent();
        cotizacion("USD", "t28-oficial", "1190", "1200", HACE_UN_RATO);

        assertThat(recalcularDelDia(a(8, 1))).isEmpty();
        assertThat(recalcularDelDia(a(23, 59))).isEmpty();

        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(historialDe(usd)).hasSize(1);
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("al dia siguiente vuelve a recalcular con el dolar nuevo")
    void alDiaSiguienteRecalculaDeNuevo() {
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");
        assertThat(recalcularDelDia(a(8, 0))).isPresent();
        cotizacion("USD", "t28-oficial", "1190", "1200", HACE_UN_RATO);

        assertThat(comoA(() -> servicio.recalcularDelDia(LocalDateTime.of(2026, 10, 17, 8, 0)))).isPresent();

        // 10 x 1200 = 12000; x 1,30 = 15600; x 1,21 = 18876 -> 18880
        assertThat(precioDe(usd)).isEqualByComparingTo("18880");
        assertThat(historialDe(usd)).hasSize(2);
    }

    @Test
    @DisplayName("con la cotizacion apagada no toca nada y no deja historial (R-020)")
    void apagadaNoTocaNada() {
        configuracion(a, c -> {
            c.setCasaPrecios("t28-oficial");
            c.setActiva(false);
        });
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");

        assertThat(recalcularDelDia(a(8, 0))).isEmpty();

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();
    }

    @Test
    @DisplayName("los articulos en pesos no se tocan; los USD inactivos si se recalculan")
    void soloLosUsdIncluidosLosInactivos() {
        Long ars = articulo(a, "T28-ARS", "ARS", "1000.00", "1580.00", true);
        Long inactivo = articulo(a, "T28-INACTIVO", "USD", "10.00", "15730.00", false);

        Optional<RecalculoCotizacionResponse> respuesta = recalcularDelDia(a(8, 0));

        assertThat(respuesta).hasValueSatisfying(r -> assertThat(r.articulosRecalculados()).isEqualTo(1));
        assertThat(precioDe(ars)).isEqualByComparingTo("1580");
        assertThat(historialDe(ars)).isEmpty();
        assertThat(precioDe(inactivo)).isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("sin cotizacion guardada del tipo no recalcula ni marca el dia: se reintenta cuando llegue un valor")
    void sinValorNoMarcaElDia() {
        configuracion(a, c -> c.setCasaPrecios("t28-mep"));
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");

        assertThat(recalcularDelDia(a(8, 0))).isEmpty();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();

        cotizacion("USD", "t28-mep", "1040", "1050", HACE_UN_RATO);
        assertThat(recalcularDelDia(a(8, 1))).isPresent();
        // 10 x 1050 = 10500; x 1,30 = 13650; x 1,21 = 16516,50 -> 16520
        assertThat(precioDe(usd)).isEqualByComparingTo("16520");
    }

    @Test
    @DisplayName("si el recalculo falla se revierte TODO, tambien el reclamo del dia, y se reintenta al minuto siguiente")
    void unaFallaRevierteElReclamo() {
        Long usd = articuloUsd(a, "T28-USD", "10.00", "15730.00");
        doAnswer(invocacion -> {
            throw new IllegalStateException("fallo el recalculo");
        }).when(AopTestUtils.<RecalculoPreciosService>getUltimateTargetObject(recalculoPreciosService))
                .porCotizacion(any(), any());

        assertThatThrownBy(() -> recalcularDelDia(a(8, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fallo el recalculo");

        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();
        assertThat(configuracionDe(a).getVigente()).isNull();
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();

        org.mockito.Mockito.reset(AopTestUtils.<RecalculoPreciosService>getUltimateTargetObject(recalculoPreciosService));
        assertThat(recalcularDelDia(a(8, 1))).isPresent();
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(HOY);
    }

    @Test
    @DisplayName("un ajuste que dejaria el precio en 0 o menos no hace fallar el dia: se saltea, se reclama y el resto sigue (S7)")
    void ajusteQueDejaElPrecioEnCeroNoHaceFallarElDia() {
        Long normal = articulo(a, "T28-NORMAL", "USD", "10.00", "15730.00", true);
        Long conAjuste = articulo(a, "T28-AJUSTE", "USD", "10.00", "5730.00", true);
        jdbc.update("update articulo set ajustes_precio = ? where id = ?",
                "[{\"nombre\":null,\"tipo\":\"MONTO\",\"valor\":-20000}]", conAjuste);

        Optional<RecalculoCotizacionResponse> respuesta = recalcularDelDia(a(8, 0));

        assertThat(respuesta).hasValueSatisfying(r -> {
            assertThat(r.articulosRecalculados()).isEqualTo(1);
            assertThat(r.avisos()).hasSize(1);
        });
        assertThat(precioDe(normal)).isEqualByComparingTo("17310");
        assertThat(precioDe(conAjuste)).isEqualByComparingTo("5730");
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNotNull();
    }
}
