package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El reclamo del recalculo del dia: un UPDATE condicional que hace al job idempotente aunque haya varias instancias.
 * Contra MySQL, con ahora explicito y dos ferreterias propias (CUIT 30-7450001x).
 */
class ReclamoRecalculoDelDiaIntegrationTest extends CotizacionTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");
    private static final LocalDate HOY = LocalDate.of(2026, 10, 16);

    @Autowired
    DolarDePrecios dolar;

    @Autowired
    TransactionTemplate transaccion;

    private Long a;
    private Long b;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-74500011-1", "Reclamo A");
        b = ferreteria("30-74500012-2", "Reclamo B");
        borrarCotizaciones("t19-");
        cotizacion("USD", "t19-oficial", "950", "1000", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t19-oficial"));
        configuracion(b, c -> c.setCasaPrecios("t19-oficial"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t19-");
        borrarConfiguracion(a, b);
    }

    private <T> T como(Long ferreteriaId, Supplier<T> accion) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            return accion.get();
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<BigDecimal> reclamar(Long ferreteriaId, LocalDateTime ahora) {
        return como(ferreteriaId, () -> dolar.reclamarRecalculoDelDia(ahora));
    }

    private static LocalDateTime a(int dia, int hora, int minuto) {
        return LocalDateTime.of(2026, 10, dia, hora, minuto);
    }

    @Test
    @DisplayName("antes de la hora configurada no reclama y no marca el dia")
    void antesDeLaHoraNoReclama() {
        assertThat(reclamar(a, a(16, 7, 59))).isEmpty();

        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();
        assertThat(configuracionDe(a).getVigente()).isNull();
    }

    @Test
    @DisplayName("a la hora configurada reclama, devuelve el valor y marca el dia")
    void aLaHoraReclama() {
        Optional<BigDecimal> valor = reclamar(a, a(16, 8, 0));

        assertThat(valor).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(HOY);
    }

    @Test
    @DisplayName("pasada la hora (arranque tardio del servidor) tambien reclama")
    void pasadaLaHoraReclama() {
        assertThat(reclamar(a, a(16, 15, 42))).isPresent();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(HOY);
    }

    @Test
    @DisplayName("un segundo reclamo el mismo dia devuelve vacio")
    void segundoReclamoDelDia() {
        assertThat(reclamar(a, a(16, 8, 0))).isPresent();

        assertThat(reclamar(a, a(16, 8, 1))).isEmpty();
        assertThat(reclamar(a, a(16, 23, 59))).isEmpty();
    }

    @Test
    @DisplayName("al dia siguiente reclama de nuevo")
    void alDiaSiguienteReclamaDeNuevo() {
        assertThat(reclamar(a, a(16, 8, 0))).isPresent();

        assertThat(reclamar(a, a(17, 8, 0))).isPresent();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    @DisplayName("la hora es la de cada ferreteria: con 09:30 a las 08:00 no reclama y a las 09:30 si")
    void laHoraEsDeCadaFerreteria() {
        configuracion(a, c -> {
            c.setCasaPrecios("t19-oficial");
            c.setHoraRecalculo(LocalTime.of(9, 30));
        });

        assertThat(reclamar(a, a(16, 8, 0))).isEmpty();
        assertThat(reclamar(a, a(16, 9, 29))).isEmpty();
        assertThat(reclamar(a, a(16, 9, 30))).isPresent();
    }

    @Test
    @DisplayName("con la cotizacion apagada no reclama (R-020)")
    void apagadaNoReclama() {
        configuracion(a, c -> {
            c.setCasaPrecios("t19-oficial");
            c.setActiva(false);
        });

        assertThat(reclamar(a, a(16, 8, 0))).isEmpty();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();
    }

    @Test
    @DisplayName("sin ningun valor guardado del tipo devuelve vacio y no marca el dia")
    void sinValorNoMarcaElDia() {
        configuracion(a, c -> c.setCasaPrecios("t19-mep"));

        assertThat(reclamar(a, a(16, 8, 0))).isEmpty();
        assertThat(configuracionDe(a).getUltimoRecalculoDia()).isNull();

        cotizacion("USD", "t19-mep", "1040", "1050", HACE_UN_RATO);
        assertThat(reclamar(a, a(16, 8, 5))).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1050"));
    }

    @Test
    @DisplayName("al reclamar fija el vigente con el ultimo valor de su tipo y lado, y conserva el dia marcado")
    void reclamarFijaElVigente() {
        reclamar(a, a(16, 8, 0));

        ConfiguracionCotizacion config = configuracionDe(a);
        assertThat(config.getVigente()).isEqualByComparingTo("1000");
        assertThat(config.getVigenteCasa()).isEqualTo("t19-oficial");
        assertThat(config.getVigenteLado()).isEqualTo(LadoCotizacion.VENTA);
        assertThat(config.getVigenteDesde()).isNotNull();
        assertThat(config.getUltimoRecalculoDia()).isEqualTo(HOY);
    }

    @Test
    @DisplayName("si la transaccion posterior revierte, el reclamo se revierte y se reintenta")
    void rollbackRevierteElReclamo() {
        assertThatThrownBy(() -> transaccion.executeWithoutResult(estado -> {
            assertThat(reclamar(a, a(16, 8, 0))).isPresent();
            throw new IllegalStateException("fallo el recalculo");
        })).isInstanceOf(IllegalStateException.class);

        ConfiguracionCotizacion config = configuracionDe(a);
        assertThat(config.getUltimoRecalculoDia()).isNull();
        assertThat(config.getVigente()).isNull();
        assertThat(reclamar(a, a(16, 8, 1))).isPresent();
    }

    @Test
    @DisplayName("el reclamo de A no marca el dia de B")
    void elReclamoEsDeLaFerreteria() {
        assertThat(reclamar(a, a(16, 8, 0))).isPresent();

        assertThat(configuracionDe(b).getUltimoRecalculoDia()).isNull();
        assertThat(reclamar(b, a(16, 8, 0))).isPresent();
    }

    @Test
    @DisplayName("una ferreteria sin configuracion propia la recibe con los valores por defecto al reclamar")
    void sinConfiguracionSeCrea() {
        borrarConfiguracion(a);
        cotizacion("USD", "oficial", "950", "1000", HACE_UN_RATO);
        try {
            assertThat(reclamar(a, a(16, 8, 0))).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000"));
            assertThat(configuracionDe(a).getUltimoRecalculoDia()).isEqualTo(HOY);
        } finally {
            jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");
        }
    }
}
