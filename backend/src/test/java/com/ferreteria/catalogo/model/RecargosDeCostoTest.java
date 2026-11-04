package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Recargos con nombre sobre el costo del articulo (CU-13, S4/S6). Dominio puro. */
class RecargosDeCostoTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static RecargoCosto recargo(String nombre, String porcentaje) {
        return new RecargoCosto(nombre, bd(porcentaje));
    }

    private static String mensaje(Runnable accion) {
        try {
            accion.run();
        } catch (DatosInvalidosException e) {
            return e.errors().get("recargosCosto");
        }
        throw new AssertionError("Tenia que fallar con DatosInvalidosException");
    }

    @Test
    @DisplayName("costo real: el ejemplo de la factura, 235971.56 con 4.44 % da 246448.70")
    void costoRealDeLaFactura() {
        RecargosDeCosto recargos = new RecargosDeCosto(List.of(recargo("Embalaje y carga", "4.44")));

        assertThat(recargos.aplicar(bd("235971.56"))).isEqualTo(bd("246448.70"));
    }

    @Test
    @DisplayName("costo real: suma los porcentajes (no se encadenan) y redondea HALF_UP a 2 decimales")
    void sumaYRedondea() {
        RecargosDeCosto recargos = new RecargosDeCosto(List.of(recargo("Flete", "3"), recargo("Descuento", "-1.50")));

        // 100.05 x 1.015 = 101.55075 -> 101.55
        assertThat(recargos.aplicar(bd("100.05"))).isEqualTo(bd("101.55"));
        // 0.50 x 1.015 = 0.5075 -> 0.51 (mitad hacia arriba)
        assertThat(recargos.aplicar(bd("0.50"))).isEqualTo(bd("0.51"));
    }

    @Test
    @DisplayName("sin recargos el costo queda tal cual, sin cambiar la escala")
    void sinRecargosNoCambiaElCosto() {
        BigDecimal costo = bd("100.0000");

        assertThat(RecargosDeCosto.NINGUNO.aplicar(costo)).isSameAs(costo);
        assertThat(RecargosDeCosto.NINGUNO.estaVacia()).isTrue();
    }

    @Test
    @DisplayName("el nombre se recorta y va de 1 a 40 caracteres")
    void nombre() {
        assertThat(recargo("  Embalaje  ", "4.44").nombre()).isEqualTo("Embalaje");
        assertThat(recargo("x".repeat(40), "1").nombre()).hasSize(40);

        assertThat(mensaje(() -> recargo("   ", "1"))).isEqualTo("El nombre del recargo es obligatorio.");
        assertThat(mensaje(() -> recargo(null, "1"))).isEqualTo("El nombre del recargo es obligatorio.");
        assertThat(mensaje(() -> recargo("x".repeat(41), "1"))).isEqualTo("El nombre del recargo tiene hasta 40 caracteres.");
    }

    @Test
    @DisplayName("el porcentaje va de -99.99 a 99.99, distinto de 0, con hasta 2 decimales")
    void porcentaje() {
        assertThat(recargo("A", "99.99").porcentaje()).isEqualTo(bd("99.99"));
        assertThat(recargo("A", "-99.99").porcentaje()).isEqualTo(bd("-99.99"));
        assertThat(recargo("A", "4.4").porcentaje()).isEqualTo(bd("4.40"));
        assertThat(recargo("A", "4.440").porcentaje()).isEqualTo(bd("4.44"));

        String rango = "El porcentaje del recargo debe estar entre -99,99 y 99,99, y no puede ser 0.";
        assertThat(mensaje(() -> recargo("A", "100"))).isEqualTo(rango);
        assertThat(mensaje(() -> recargo("A", "-100"))).isEqualTo(rango);
        assertThat(mensaje(() -> recargo("A", "0.00"))).isEqualTo(rango);
        assertThat(mensaje(() -> recargo("A", "4.445"))).isEqualTo("El porcentaje del recargo tiene hasta 2 decimales.");
        assertThat(mensaje(() -> new RecargoCosto("A", null))).isEqualTo(rango);
    }

    @Test
    @DisplayName("hasta 5 recargos por articulo")
    void maximoCinco() {
        List<RecargoCosto> cinco = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            cinco.add(recargo("R" + i, "1"));
        }
        assertThat(new RecargosDeCosto(cinco).lista()).hasSize(5);

        cinco.add(recargo("R6", "1"));
        assertThat(mensaje(() -> new RecargosDeCosto(cinco))).isEqualTo("Un artículo tiene hasta 5 recargos del costo.");
    }

    @Test
    @DisplayName("la suma no puede dejar el costo en 0 o menos")
    void sumaMenorOIgualACien() {
        List<RecargoCosto> lista = List.of(recargo("A", "-60"), recargo("B", "-50"));

        assertThat(mensaje(() -> new RecargosDeCosto(lista)))
            .isEqualTo("Los recargos del costo no pueden sumar -100 % o menos.");
    }
}
