package com.ferreteria.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.TotalesVenta;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    @DisplayName("la venta y el movimiento de stock se fechan con la hora de Buenos Aires")
    void ventaYMovimiento() {
        TotalesVenta totales = new TotalesVenta(new BigDecimal("121.00"), new BigDecimal("100.00"), new BigDecimal("21.00"));
        esHoraDeBuenosAires(Comprobante.venta(1L, 1, "clave", 1L, totales, null).getFecha());
        esHoraDeBuenosAires(MovimientoStock.ajuste(1L, 1L, BigDecimal.ONE, "Conteo", 1L).getFecha());
    }
}
