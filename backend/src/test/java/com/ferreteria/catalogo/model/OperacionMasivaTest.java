package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class OperacionMasivaTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("el valor se guarda a escala 2: 10 y 10.00 son la misma operacion")
    void valorAEscalaDos() {
        OperacionMasiva sinDecimales = new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10"));
        OperacionMasiva conDecimales = new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10.00"));

        assertThat(sinDecimales.valor().scale()).isEqualTo(2);
        assertThat(sinDecimales).isEqualTo(conDecimales);
    }

    @Test
    @DisplayName("un valor con mas de 2 decimales se rechaza en vez de redondearse")
    void valorConMasDeDosDecimales() {
        assertThatThrownBy(() -> new OperacionMasiva(ModoMasivo.MARGEN, bd("10.555")))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("modo nulo o valor nulo se rechazan")
    void nulosRechazados() {
        assertThatThrownBy(() -> new OperacionMasiva(null, bd("10")))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OperacionMasiva(ModoMasivo.MARGEN, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ModoMasivo tiene exactamente COSTO_PORCENTAJE y MARGEN")
    void modos() {
        assertThat(Arrays.stream(ModoMasivo.values()).map(Enum::name))
            .containsExactly("COSTO_PORCENTAJE", "MARGEN");
    }

    @Test
    @DisplayName("los records del plan guardan sus campos con los nombres de la spec")
    void recordsDelPlan() {
        ConteosMasivos conteos = new ConteosMasivos(5, 2, 0, 2, 1, 0, 0, 3);
        CambioMasivo cambio = new CambioMasivo(1L, "INT-1", "Articulo 1", bd("100.00"), bd("110.00"),
            bd("30.00"), bd("30.00"), bd("160.00"), bd("180.00"), null);
        ErrorMasivo error = new ErrorMasivo(4L, "INT-4", "Articulo 4", "El costo nuevo da 0. Revisá el porcentaje.");
        PlanMasivo plan = new PlanMasivo(conteos, java.util.List.of(cambio), java.util.List.of(error), "abc");

        assertThat(plan.conteos().total()).isEqualTo(5);
        assertThat(plan.conteos().suben()).isEqualTo(2);
        assertThat(plan.conteos().bajan()).isZero();
        assertThat(plan.conteos().sinCambioDePrecio()).isEqualTo(2);
        assertThat(plan.conteos().conError()).isEqualTo(1);
        assertThat(plan.conteos().pasanAMargenPropio()).isZero();
        assertThat(plan.conteos().conAdvertencia()).isZero();
        assertThat(plan.conteos().modificados()).isEqualTo(3);
        assertThat(plan.cambios().get(0).articuloId()).isEqualTo(1L);
        assertThat(plan.cambios().get(0).advertencia()).isNull();
        assertThat(plan.errores().get(0).motivo()).isEqualTo("El costo nuevo da 0. Revisá el porcentaje.");
        assertThat(plan.huella()).isEqualTo("abc");
    }
}
