package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU-10: apertura, dia de la caja y cierre ciego. Dominio puro. */
class CajaTest {

    private static final LocalDateTime LUNES_9 = LocalDateTime.of(2026, 10, 12, 9, 0);
    private static final LocalDate LUNES = LUNES_9.toLocalDate();

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Caja abierta() {
        return Caja.abrir(3L, 9L, bd("5000.00"), null, LUNES_9);
    }

    @Test
    @DisplayName("abrir deja la caja abierta con el efectivo inicial, el usuario y la hora de apertura")
    void abrir() {
        Caja caja = Caja.abrir(3L, 9L, bd("5000.00"), bd("5000.00"), LUNES_9);

        assertThat(caja.estaAbierta()).isTrue();
        assertThat(caja.getFerreteriaId()).isEqualTo(3L);
        assertThat(caja.getUsuarioAperturaId()).isEqualTo(9L);
        assertThat(caja.getFechaApertura()).isEqualTo(LUNES_9);
        assertThat(caja.getEfectivoInicial()).isEqualByComparingTo("5000");
        assertThat(caja.getFechaCierre()).isNull();
        assertThat(caja.getEfectivoContado()).isNull();
    }

    @Test
    @DisplayName("abrir con otro monto que el contado del ultimo cierre guarda la diferencia (inicial - contado anterior)")
    void diferenciaDeApertura() {
        assertThat(Caja.abrir(3L, 9L, bd("4500.00"), bd("5000.00"), LUNES_9).getDiferenciaApertura())
                .isEqualByComparingTo("-500");
        assertThat(Caja.abrir(3L, 9L, bd("5200.00"), bd("5000.00"), LUNES_9).getDiferenciaApertura())
                .isEqualByComparingTo("200");
        assertThat(Caja.abrir(3L, 9L, bd("5000.00"), bd("5000.00"), LUNES_9).getDiferenciaApertura())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("la primera caja (sin cierre anterior) no tiene diferencia de apertura")
    void primeraCajaSinDiferencia() {
        Caja caja = Caja.abrir(3L, 9L, bd("1000.00"), null, LUNES_9);

        assertThat(caja.getEfectivoCierreAnterior()).isNull();
        assertThat(caja.getDiferenciaApertura()).isNull();
    }

    @Test
    @DisplayName("el efectivo inicial puede ser cero pero no negativo, ni faltar, ni tener mas de 2 decimales")
    void efectivoInicialInvalido() {
        assertThat(Caja.abrir(3L, 9L, BigDecimal.ZERO, null, LUNES_9).estaAbierta()).isTrue();
        assertThatThrownBy(() -> Caja.abrir(3L, 9L, bd("-1"), null, LUNES_9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El efectivo inicial no puede ser negativo.");
        assertThatThrownBy(() -> Caja.abrir(3L, 9L, null, null, LUNES_9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cargá el efectivo inicial.");
        assertThatThrownBy(() -> Caja.abrir(3L, 9L, bd("10.001"), null, LUNES_9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El efectivo inicial admite hasta 2 decimales.");
    }

    @Test
    @DisplayName("la caja abierta hoy esta ABIERTA; la abierta otro dia esta DE_OTRO_DIA (fecha de Buenos Aires)")
    void estadoSegunElDia() {
        assertThat(abierta().estado(LUNES)).isEqualTo(EstadoCaja.ABIERTA);
        assertThat(abierta().estado(LUNES.plusDays(1))).isEqualTo(EstadoCaja.DE_OTRO_DIA);
        Caja ultimoMinuto = Caja.abrir(3L, 9L, bd("0"), null, LocalDateTime.of(2026, 10, 12, 23, 59));
        assertThat(ultimoMinuto.estado(LUNES)).isEqualTo(EstadoCaja.ABIERTA);
    }

    @Test
    @DisplayName("cerrar guarda el contado, el esperado y la diferencia (contado - esperado) y la caja queda CERRADA")
    void cerrar() {
        Caja caja = abierta();
        LocalDateTime cierre = LUNES_9.plusHours(10);

        caja.cerrar(11L, bd("12900.00"), bd("13000.00"), cierre);

        assertThat(caja.estaAbierta()).isFalse();
        assertThat(caja.estado(LUNES)).isEqualTo(EstadoCaja.CERRADA);
        assertThat(caja.getUsuarioCierreId()).isEqualTo(11L);
        assertThat(caja.getFechaCierre()).isEqualTo(cierre);
        assertThat(caja.getEfectivoContado()).isEqualByComparingTo("12900");
        assertThat(caja.getEfectivoEsperado()).isEqualByComparingTo("13000");
        assertThat(caja.getDiferencia()).isEqualByComparingTo("-100");
    }

    @Test
    @DisplayName("una caja cerrada no se vuelve a cerrar: es inmutable")
    void cerradaEsInmutable() {
        Caja caja = abierta();
        caja.cerrar(11L, bd("100"), bd("100"), LUNES_9.plusHours(1));

        assertThatThrownBy(() -> caja.cerrar(11L, bd("200"), bd("100"), LUNES_9.plusHours(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Esta caja ya está cerrada.");
        assertThat(caja.getEfectivoContado()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("el efectivo contado puede ser cero pero no negativo ni faltar")
    void contadoInvalido() {
        assertThatThrownBy(() -> abierta().cerrar(11L, bd("-0.01"), bd("0"), LUNES_9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El efectivo contado no puede ser negativo.");
        assertThatThrownBy(() -> abierta().cerrar(11L, null, bd("0"), LUNES_9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cargá el efectivo contado.");
        Caja caja = abierta();
        caja.cerrar(11L, BigDecimal.ZERO, bd("0"), LUNES_9);
        assertThat(caja.getDiferencia()).isEqualByComparingTo("0");
    }
}
