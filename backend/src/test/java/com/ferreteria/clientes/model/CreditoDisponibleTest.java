package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.model.CreditoDisponible.Aplicacion;
import com.ferreteria.clientes.model.CreditoDisponible.Credito;
import com.ferreteria.clientes.model.Imputacion.Origen;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GDS-20, G59: el crédito disponible de un cliente (lo que cada recibo o Descuento tiene sin imputar) y cómo lo consume una
 * deuda nueva: FIFO por el id del movimiento del libro, solo lo que hay y solo hasta la deuda. Dominio puro.
 */
class CreditoDisponibleTest {

    private static Credito recibo(long id, long movimiento, String importe, String imputado) {
        return new Credito(Origen.recibo(id), movimiento, new BigDecimal(importe), new BigDecimal(imputado));
    }

    private static Credito descuento(long id, long movimiento, String importe, String imputado) {
        return new Credito(Origen.ajuste(id), movimiento, new BigDecimal(importe), new BigDecimal(imputado));
    }

    @Test
    @DisplayName("el disponible de cada origen es lo que tiene menos lo imputado vigente; el total suma solo los que tienen algo")
    void totalDisponible() {
        CreditoDisponible credito = new CreditoDisponible(
                List.of(recibo(1, 10, "500", "200"), descuento(2, 20, "100", "100"), recibo(3, 30, "50.50", "0")));

        assertThat(credito.total()).isEqualByComparingTo("350.50");
        assertThat(new CreditoDisponible(List.of()).total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("L10: el disponible de un origen puntual (una nota de crédito): lo que le queda, 0 si está agotado o no está")
    void disponibleDeUnOrigen() {
        CreditoDisponible credito = new CreditoDisponible(List.of(recibo(1, 10, "500", "200"),
                new Credito(Origen.notaDeCredito(7L), 20L, new BigDecimal("242"), new BigDecimal("142")),
                new Credito(Origen.notaDeCredito(8L), 30L, new BigDecimal("100"), new BigDecimal("100"))));

        assertThat(credito.disponibleDe(Origen.notaDeCredito(7L))).isEqualByComparingTo("100");
        assertThat(credito.disponibleDe(Origen.notaDeCredito(8L))).isEqualByComparingTo("0");
        assertThat(credito.disponibleDe(Origen.notaDeCredito(9L))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("aplica FIFO por id de movimiento, sin importar el orden en que llegan, y reparte entre varios orígenes")
    void fifo() {
        CreditoDisponible credito = new CreditoDisponible(
                List.of(descuento(2, 20, "100", "0"), recibo(1, 10, "300", "0"), recibo(3, 30, "400", "0")));

        List<Aplicacion> aplicadas = credito.aplicar(new BigDecimal("350"));

        assertThat(aplicadas).extracting(Aplicacion::origen).containsExactly(Origen.recibo(1L), Origen.ajuste(2L));
        assertThat(aplicadas).extracting(Aplicacion::importe).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300"), new BigDecimal("50"));
    }

    @Test
    @DisplayName("nunca aplica más que la deuda ni más que el crédito: con crédito de sobra queda un resto, con crédito corto se aplica todo")
    void tope() {
        CreditoDisponible credito = new CreditoDisponible(List.of(recibo(1, 10, "300", "0"), recibo(2, 20, "100", "0")));

        assertThat(credito.aplicar(new BigDecimal("120"))).extracting(Aplicacion::importe)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("120"));
        assertThat(credito.aplicar(new BigDecimal("1000"))).extracting(Aplicacion::importe)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("300"), new BigDecimal("100"));
        assertThat(credito.aplicar(new BigDecimal("400"))).hasSize(2);
    }

    @Test
    @DisplayName("salta los orígenes agotados o negativos y devuelve vacío sin crédito o sin deuda")
    void agotadosYVacios() {
        CreditoDisponible credito = new CreditoDisponible(
                List.of(recibo(1, 10, "100", "100"), recibo(2, 20, "0", "0"), descuento(3, 30, "40", "0")));

        assertThat(credito.aplicar(new BigDecimal("25"))).extracting(Aplicacion::origen).containsExactly(Origen.ajuste(3L));
        assertThat(new CreditoDisponible(List.of()).aplicar(BigDecimal.TEN)).isEmpty();
        assertThat(credito.aplicar(BigDecimal.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("la deuda no puede ser negativa ni nula, y un crédito necesita su origen, su movimiento y sus importes")
    void argumentosMalFormados() {
        CreditoDisponible credito = new CreditoDisponible(List.of(recibo(1, 10, "100", "0")));

        assertThatThrownBy(() -> credito.aplicar(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credito.aplicar(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreditoDisponible(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Credito(null, 10L, BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Credito(Origen.recibo(1L), null, BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Credito(Origen.recibo(1L), 10L, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
