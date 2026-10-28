package com.ferreteria.ferreteria.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    @DisplayName("una ferretería nueva no tiene domicilio comercial, inicio de actividades ni Ingresos Brutos")
    void sinDatosFiscales() {
        Ferreteria f = nueva();

        assertThat(f.getDomicilioComercial()).isNull();
        assertThat(f.getInicioActividades()).isNull();
        assertThat(f.getIngresosBrutos()).isNull();
    }

    @Test
    @DisplayName("actualizarDatosFiscales cambia los cinco datos del emisor, recorta los textos y no toca el CUIT")
    void actualizarDatosFiscales() {
        Ferreteria f = nueva();

        f.actualizarDatosFiscales("  Tuerca SRL ", CondicionIva.MONOTRIBUTISTA, " Av. Luro 3200, Mar del Plata ",
                LocalDate.of(2015, 3, 1), " 901-123456-7 ");

        assertThat(f.getRazonSocial()).isEqualTo("Tuerca SRL");
        assertThat(f.getCondicionIva()).isEqualTo(CondicionIva.MONOTRIBUTISTA);
        assertThat(f.getDomicilioComercial()).isEqualTo("Av. Luro 3200, Mar del Plata");
        assertThat(f.getInicioActividades()).isEqualTo(LocalDate.of(2015, 3, 1));
        assertThat(f.getIngresosBrutos()).isEqualTo("901-123456-7");
        assertThat(f.getCuit()).isEqualTo("30-12345678-1");
    }

    @Test
    @DisplayName("actualizarDatosFiscales guarda un domicilio o unos Ingresos Brutos en blanco como vacíos (null)")
    void datosFiscalesEnBlancoQuedanVacios() {
        Ferreteria f = nueva();
        f.actualizarDatosFiscales("Tornillo SA", CondicionIva.RESPONSABLE_INSCRIPTO, "Av. Luro 3200",
                LocalDate.of(2015, 3, 1), "Convenio Multilateral 901-1");

        f.actualizarDatosFiscales("Tornillo SA", CondicionIva.RESPONSABLE_INSCRIPTO, "   ", null, "");

        assertThat(f.getDomicilioComercial()).isNull();
        assertThat(f.getInicioActividades()).isNull();
        assertThat(f.getIngresosBrutos()).isNull();
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
