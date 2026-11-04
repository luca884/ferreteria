package com.ferreteria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ferreteria.common.config.RelojConfig;
import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.TotalesVenta;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * En Railway la JVM corre en UTC: si la hora de la venta sale de la zona de la JVM, el ticket muestra 3 horas de mas
 * y una venta de las 22:00 cae en el dia siguiente. La hora local que se guarda es siempre la de Buenos Aires.
 */
class HoraArgentinaTest {

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private TimeZone original;

    @BeforeEach
    void jvmEnUtc() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restaurar() {
        TimeZone.setDefault(original);
    }

    private static void esHoraDeBuenosAires(LocalDateTime hora) {
        assertThat(Duration.between(LocalDateTime.now(BUENOS_AIRES), hora).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("ahora() es la hora de Buenos Aires aunque la JVM este en UTC")
    void ahora() {
        esHoraDeBuenosAires(HoraArgentina.ahora());
    }

    @Test
    @DisplayName("la venta y el movimiento de stock se fechan con la hora de Buenos Aires del reloj de la aplicacion")
    void ventaYMovimiento() {
        // La venta y el stock toman el instante del Clock (RelojConfig) y lo pasan a la hora de Buenos Aires.
        LocalDateTime ahora = LocalDateTime.ofInstant(new RelojConfig().reloj().instant(), HoraArgentina.ZONA);
        esHoraDeBuenosAires(ahora);
        TotalesVenta totales = new TotalesVenta(new BigDecimal("121.00"), new BigDecimal("100.00"), new BigDecimal("21.00"));
        assertThat(Comprobante.venta(1L, 1, "clave", 1L, totales, null, ahora).getFecha()).isEqualTo(ahora);
        assertThat(MovimientoStock.ajuste(1L, 1L, BigDecimal.ONE, "Conteo", 1L, ahora).getFecha()).isEqualTo(ahora);
    }

    /*
     * En Linux el reloj de Java da nanosegundos y MySQL guarda microsegundos (DATETIME(6)) redondeando: la respuesta de
     * una cobranza o una anulacion no coincidiria con la repetida ni con el GET (CU-12 punto 3). La hora de la aplicacion
     * se recorta a microsegundos antes de usarse.
     */

    @Test
    @DisplayName("enMicrosegundos recorta los nanosegundos sueltos (sin redondear) y conserva la zona")
    void enMicrosegundosRecorta() {
        Clock base = Clock.fixed(Instant.parse("2026-11-04T22:30:06.675534999Z"), BUENOS_AIRES);

        Clock reloj = HoraArgentina.enMicrosegundos(base);

        assertThat(reloj.instant()).isEqualTo(Instant.parse("2026-11-04T22:30:06.675534Z"));
        assertThat(reloj.getZone()).isEqualTo(BUENOS_AIRES);
    }

    @Test
    @DisplayName("el reloj recortado cumple el contrato de Clock: millis() funciona y coincide con instant()")
    void millisDelReloj() {
        Clock fijo = HoraArgentina.enMicrosegundos(
                Clock.fixed(Instant.parse("2026-11-04T22:30:06.675534999Z"), BUENOS_AIRES));
        Clock reloj = new RelojConfig().reloj();

        assertThat(fijo.millis()).isEqualTo(Instant.parse("2026-11-04T22:30:06.675Z").toEpochMilli());
        assertThatCode(reloj::millis).doesNotThrowAnyException();
        assertThatCode(() -> Clock.offset(reloj, Duration.ofSeconds(1)).millis()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("withZone cambia la zona y conserva el recorte")
    void withZoneConservaElRecorte() {
        Clock base = Clock.fixed(Instant.parse("2026-11-04T22:30:06.675534999Z"), BUENOS_AIRES);

        Clock enUtc = HoraArgentina.enMicrosegundos(base).withZone(ZoneOffset.UTC);

        assertThat(enUtc.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(enUtc.instant()).isEqualTo(Instant.parse("2026-11-04T22:30:06.675534Z"));
    }

    @Test
    @DisplayName("el reloj de la aplicacion y ahora() no tienen nanosegundos sueltos")
    void sinNanosegundosSueltos() {
        Clock reloj = new RelojConfig().reloj();

        assertThat(reloj.getZone()).isEqualTo(BUENOS_AIRES);
        for (int i = 0; i < 1_000; i++) {
            assertThat(reloj.instant().getNano() % 1_000).isZero();
            assertThat(HoraArgentina.ahora().getNano() % 1_000).isZero();
        }
    }
}
