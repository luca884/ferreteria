package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.model.PartidasAbiertas.Debito;
import com.ferreteria.clientes.model.PartidasAbiertas.Partida;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GDS-20, G59: un Recargo es una partida pendiente como un comprobante (su pendiente = importe − Σ imputado). Las partidas
 * siguen en orden FIFO por el id del primer movimiento, mezclando comprobantes y Recargos. Dominio puro; los casos de solo
 * comprobantes están en {@link PartidasAbiertasTest} y no cambian.
 */
class PartidasConRecargosTest {

    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 3, 2, 10, 0);

    private static Debito venta(long comprobante, long movimiento, String debe) {
        return new Debito(comprobante, movimiento, "Venta N.º " + comprobante, FECHA.plusMinutes(movimiento), new BigDecimal(debe));
    }

    private static Debito recargo(long ajuste, long movimiento, String debe) {
        return Debito.deRecargo(ajuste, movimiento, "Recargo N.º " + ajuste, FECHA.plusMinutes(movimiento), new BigDecimal(debe));
    }

    @Test
    @DisplayName("un Recargo trae su ajusteId y ningún comprobanteId, y un comprobante lo contrario")
    void laPartidaDeUnRecargo() {
        PartidasAbiertas partidas = new PartidasAbiertas(List.of(venta(10, 1, "300"), recargo(81, 2, "50")), Map.of(),
                Map.of(81L, new BigDecimal("20")));

        Partida deVenta = partidas.abiertas().get(0);
        Partida deRecargo = partidas.abiertas().get(1);
        assertThat(deVenta.comprobanteId()).isEqualTo(10L);
        assertThat(deVenta.ajusteId()).isNull();
        assertThat(deRecargo.comprobanteId()).isNull();
        assertThat(deRecargo.ajusteId()).isEqualTo(81L);
        assertThat(deRecargo.referencia()).isEqualTo("Recargo N.º 81");
        assertThat(deRecargo.importe()).isEqualByComparingTo("50.00");
        assertThat(deRecargo.imputado()).isEqualByComparingTo("20.00");
        assertThat(deRecargo.pendiente()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("el orden FIFO mezcla comprobantes y Recargos por el id del primer movimiento")
    void fifoMezclado() {
        PartidasAbiertas partidas = new PartidasAbiertas(
                List.of(recargo(81, 5, "50"), venta(10, 1, "300"), recargo(82, 9, "10"), venta(20, 7, "100")), Map.of(), Map.of());

        assertThat(partidas.abiertas()).extracting(Partida::referencia)
                .containsExactly("Venta N.º 10", "Recargo N.º 81", "Venta N.º 20", "Recargo N.º 82");
    }

    @Test
    @DisplayName("un Recargo saldado no aparece en las abiertas; pendienteDeAjuste lo da con 0 y el total suma comprobantes y Recargos")
    void saldadoYTotal() {
        PartidasAbiertas partidas = new PartidasAbiertas(List.of(venta(10, 1, "300"), recargo(81, 2, "50"), recargo(82, 3, "70")),
                Map.of(10L, new BigDecimal("100")), Map.of(81L, new BigDecimal("50")));

        assertThat(partidas.abiertas()).extracting(Partida::referencia).containsExactly("Venta N.º 10", "Recargo N.º 82");
        assertThat(partidas.pendienteDeAjuste(81L)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("0"));
        assertThat(partidas.pendienteDeAjuste(99L)).isEmpty();
        assertThat(partidas.pendienteDe(10L)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("200"));
        assertThat(partidas.totalPendiente()).isEqualByComparingTo("270.00");
    }

    @Test
    @DisplayName("el id de un comprobante y el de un Recargo son espacios distintos: el comprobante 5 y el Recargo 5 conviven")
    void idsEnEspaciosDistintos() {
        PartidasAbiertas partidas = new PartidasAbiertas(List.of(venta(5, 1, "100"), recargo(5, 2, "40")),
                Map.of(5L, new BigDecimal("10")), Map.of(5L, new BigDecimal("4")));

        assertThat(partidas.pendienteDe(5L)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("90"));
        assertThat(partidas.pendienteDeAjuste(5L)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("36"));
    }

    @Test
    @DisplayName("excesos mira comprobantes (por id) y después Recargos (por id), y trae la deuda actual de cada uno")
    void excesosMezclados() {
        PartidasAbiertas partidas = new PartidasAbiertas(List.of(venta(10, 1, "300"), recargo(81, 2, "50"), recargo(82, 3, "70")),
                Map.of(), Map.of(82L, new BigDecimal("20")));

        List<Partida> excesos = partidas.excesos(Map.of(10L, new BigDecimal("301")),
                Map.of(82L, new BigDecimal("51"), 81L, new BigDecimal("50")));

        assertThat(excesos).extracting(Partida::referencia).containsExactly("Venta N.º 10", "Recargo N.º 82");
        assertThat(excesos.get(1).pendiente()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("un Recargo sin débito en la cuenta, un importe no positivo o un Recargo repetido en los débitos son argumentos mal formados")
    void mal() {
        PartidasAbiertas partidas = new PartidasAbiertas(List.of(recargo(81, 2, "50")), Map.of(), Map.of());

        assertThatThrownBy(() -> partidas.excesos(Map.of(), Map.of(99L, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("99");
        assertThatThrownBy(() -> partidas.excesos(Map.of(), Map.of(81L, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartidasAbiertas(List.of(recargo(81, 2, "50"), recargo(81, 3, "10")), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("81");
        assertThatThrownBy(() -> new PartidasAbiertas(List.of(venta(1, 1, "10")), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el constructor de solo comprobantes y excesos(Map) siguen valiendo, sin Recargos")
    void laFormaVieja() {
        PartidasAbiertas partidas = new PartidasAbiertas(List.of(new Debito(10L, 1L, "Venta N.º 10", FECHA, new BigDecimal("300"))),
                Map.of(10L, new BigDecimal("100")));

        assertThat(partidas.abiertas()).hasSize(1);
        assertThat(partidas.abiertas().get(0).ajusteId()).isNull();
        assertThat(partidas.excesos(Map.of(10L, new BigDecimal("201")))).hasSize(1);
        assertThat(partidas.excesos(Map.of(10L, new BigDecimal("200")))).isEmpty();
    }
}
