package com.ferreteria.ferreteria.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FerreteriaTest {

    private static Ferreteria nueva() {
        return new Ferreteria("Tornillo SA", "30-12345678-1", CondicionIva.RESPONSABLE_INSCRIPTO);
    }

    @Test
    @DisplayName("una ferretería nueva está activa, con margen 30.00 y redondeo 0.00")
    void defaults() {
        Ferreteria f = nueva();

        assertThat(f.isActiva()).isTrue();
        assertThat(f.getEstado()).isEqualTo(EstadoFerreteria.ACTIVA);
        assertThat(f.getMargenDefectoPorcentaje()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(f.getRedondeoMultiplo()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("actualizarDatos cambia razón social y condición de IVA, y no el CUIT")
    void actualizarDatos() {
        Ferreteria f = nueva();

        f.actualizarDatos("Tuerca SRL", CondicionIva.MONOTRIBUTISTA);

        assertThat(f.getRazonSocial()).isEqualTo("Tuerca SRL");
        assertThat(f.getCondicionIva()).isEqualTo(CondicionIva.MONOTRIBUTISTA);
        assertThat(f.getCuit()).isEqualTo("30-12345678-1");
    }

    @Test
    @DisplayName("cambiarActivo suspende y reactiva, y repetirlo no cambia nada")
    void cambiarActivoEsIdempotente() {
        Ferreteria f = nueva();

        f.cambiarActivo(false);
        f.cambiarActivo(false);
        assertThat(f.getEstado()).isEqualTo(EstadoFerreteria.SUSPENDIDA);
        assertThat(f.isActiva()).isFalse();

        f.cambiarActivo(true);
        f.cambiarActivo(true);
        assertThat(f.getEstado()).isEqualTo(EstadoFerreteria.ACTIVA);
        assertThat(f.isActiva()).isTrue();
    }

    @Test
    @DisplayName("la columna cuit no se actualiza nunca (updatable = false)")
    void cuitEsInmutable() throws Exception {
        Column columna = Ferreteria.class.getDeclaredField("cuit").getAnnotation(Column.class);

        assertThat(columna.updatable()).isFalse();
    }
}
