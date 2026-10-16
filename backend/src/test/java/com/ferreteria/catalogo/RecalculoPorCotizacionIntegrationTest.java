package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.catalogo.service.RecalculoPorCotizacionService;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * «Recalcular ahora» contra MySQL (T-027): 10 USD con margen 30 %, IVA 21 % y multiplo 10 valen 15.730 con el dolar
 * en 1000 y 17.310 con 1100. Ferreteria propia (CUIT 30-77020001-1) y casa propia (t27-): la tabla cotizacion es global.
 */
class RecalculoPorCotizacionIntegrationTest extends CotizacionTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    @Autowired
    RecalculoPorCotizacionService servicio;

    private Long a;
    private Long dueno;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-77020001-1", "Recalculo ahora A");
        dueno = usuarioId(usuario(a, "dueno-t27a@test.local", "DUENO"));
        borrarCotizaciones("t27-");
        cotizacion("USD", "t27-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t27-oficial"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t27-");
        borrarDatosDe(a);
    }

    private RecalculoCotizacionResponse recalcularAhora() {
        TenantContext.setCurrentFerreteriaId(a);
        try {
            return servicio.recalcularAhora(dueno);
        } finally {
            TenantContext.clear();
        }
    }

    private void recalcularAhoraDebeFallarCon(String mensaje) {
        TenantContext.setCurrentFerreteriaId(a);
        try {
            assertThatThrownBy(() -> servicio.recalcularAhora(dueno))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(mensaje);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("con el dolar en 1100 el articulo de 10 USD pasa de 15.730 a 17.310, con COTIZACION a nombre del Dueno")
    void recalculaYDejaHistorialDelDueno() {
        Long usd = articuloUsd(a, "T27-USD", "10.00", "15730.00");

        RecalculoCotizacionResponse respuesta = recalcularAhora();

        assertThat(respuesta.cotizacion()).isEqualByComparingTo("1100");
        assertThat(respuesta.articulosRecalculados()).isEqualTo(1);
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(historialDe(usd)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("COTIZACION");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat(h.get("usuario_id")).isEqualTo(dueno);
            assertThat((BigDecimal) h.get("precio_anterior")).isEqualByComparingTo("15730");
            assertThat((BigDecimal) h.get("precio_nuevo")).isEqualByComparingTo("17310");
            assertThat((BigDecimal) h.get("costo_nuevo")).isEqualByComparingTo("10");
        });
    }

    @Test
    @DisplayName("fija el dolar como vigente de la ferreteria")
    void fijaElVigente() {
        articuloUsd(a, "T27-USD", "10.00", "15730.00");

        recalcularAhora();

        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1100");
        assertThat(configuracionDe(a).getVigenteDesde()).isNotNull();
    }

    @Test
    @DisplayName("con el dolar sin cambio la cantidad es 0, el precio igual y no hay historial (R-030)")
    void sinCambioNoDejaHistorial() {
        Long usd = articuloUsd(a, "T27-USD", "10.00", "17310.00");

        RecalculoCotizacionResponse respuesta = recalcularAhora();

        assertThat(respuesta.articulosRecalculados()).isZero();
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(historialDe(usd)).isEmpty();
    }

    @Test
    @DisplayName("un segundo «Recalcular ahora» con el mismo dolar no vuelve a cambiar ni a escribir historial")
    void segundaVezNoDuplica() {
        Long usd = articuloUsd(a, "T27-USD", "10.00", "15730.00");

        assertThat(recalcularAhora().articulosRecalculados()).isEqualTo(1);
        assertThat(recalcularAhora().articulosRecalculados()).isZero();

        assertThat(historialDe(usd)).hasSize(1);
    }

    @Test
    @DisplayName("el articulo en pesos no se toca y no deja historial")
    void elArticuloEnPesosNoSeToca() {
        Long ars = articulo(a, "T27-ARS", "ARS", "1000.00", "1580.00", true);
        Long usd = articuloUsd(a, "T27-USD", "10.00", "15730.00");

        RecalculoCotizacionResponse respuesta = recalcularAhora();

        assertThat(respuesta.articulosRecalculados()).isEqualTo(1);
        assertThat(precioDe(ars)).isEqualByComparingTo("1580");
        assertThat(historialDe(ars)).isEmpty();
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("el articulo en USD inactivo tambien se recalcula (R-032) y sigue inactivo")
    void incluyeLosInactivos() {
        Long inactivo = articulo(a, "T27-INACTIVO", "USD", "10.00", "15730.00", false);

        RecalculoCotizacionResponse respuesta = recalcularAhora();

        assertThat(respuesta.articulosRecalculados()).isEqualTo(1);
        assertThat(precioDe(inactivo)).isEqualByComparingTo("17310");
        assertThat(historialDe(inactivo)).hasSize(1);
        assertThat(jdbc.queryForObject("select activo from articulo where id = ?", Boolean.class, inactivo)).isFalse();
    }

    @Test
    @DisplayName("el recalculo respeta el lado elegido: oficial compra (1090) deja 10 USD en 16.060 en vez de 17.310")
    void respetaElLadoDeLaConfiguracion() {
        configuracion(a, c -> {
            c.setCasaPrecios("t27-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
        });
        Long usd = articuloUsd(a, "T27-USD", "10.00", "15730.00");

        RecalculoCotizacionResponse respuesta = recalcularAhora();

        // 10 x 1090 = 10900; x 1,30 = 14170; x 1,21 = 17145,70 -> multiplo de 10: 17150
        assertThat(respuesta.cotizacion()).isEqualByComparingTo("1090");
        assertThat(precioDe(usd)).isEqualByComparingTo("17150");
    }

    @Test
    @DisplayName("con la cotizacion apagada responde 409 «La cotización está apagada» y no cambia nada")
    void apagadaDa409() {
        configuracion(a, c -> {
            c.setCasaPrecios("t27-oficial");
            c.setActiva(false);
        });
        Long usd = articuloUsd(a, "T27-USD", "10.00", "15730.00");

        recalcularAhoraDebeFallarCon("La cotización está apagada");

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("sin ninguna cotizacion guardada de su tipo responde 409 y no cambia nada")
    void sinValorDa409() {
        configuracion(a, c -> c.setCasaPrecios("t27-mep"));
        Long usd = articuloUsd(a, "T27-USD", "10.00", "15730.00");

        recalcularAhoraDebeFallarCon("Todavía no hay cotización del dólar. Probá en unos minutos");

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
    }
}
