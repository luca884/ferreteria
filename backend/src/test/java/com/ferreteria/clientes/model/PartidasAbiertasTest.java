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
 * CC-030, CC-031, CC-032, CC-051: el pendiente de cada comprobante (Σ debe VENTA − Σ imputaciones) y la validación de lo que
 * se quiere imputar, sin Spring ni base. Los casos T1–T9 del apéndice A de la spec que le tocan al dominio (el orden FIFO de
 * la lista y la comparación por valor con 2 decimales; el reparto sugerido es del front).
 */
class PartidasAbiertasTest {

    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 3, 2, 10, 0);

    private static Debito debito(long comprobante, long primerMovimiento, String debe) {
        return new Debito(comprobante, primerMovimiento, "Venta N.º " + comprobante, FECHA.plusMinutes(primerMovimiento),
                new BigDecimal(debe));
    }

    private static PartidasAbiertas de(Map<Long, BigDecimal> imputado, Debito... debitos) {
        return new PartidasAbiertas(List.of(debitos), imputado);
    }

    private static PartidasAbiertas de(Debito... debitos) {
        return de(Map.of(), debitos);
    }

    @Test
    @DisplayName("T1: las abiertas salen en orden FIFO por el id del primer movimiento, lleguen como lleguen")
    void fifoPorPrimerMovimiento() {
        PartidasAbiertas partidas = de(debito(30, 9, "400"), debito(10, 1, "300"), debito(20, 5, "500"));

        assertThat(partidas.abiertas()).extracting(Partida::comprobanteId).containsExactly(10L, 20L, 30L);
    }

    @Test
    @DisplayName("T9: el orden FIFO no depende del importe: A 500 (id 1) antes que B 100 (id 2), y al revés si el id manda")
    void elOrdenNoDependeDelImporte() {
        assertThat(de(debito(1, 1, "500"), debito(2, 2, "100")).abiertas()).extracting(Partida::comprobanteId)
                .containsExactly(1L, 2L);
        assertThat(de(debito(1, 2, "500"), debito(2, 1, "100")).abiertas()).extracting(Partida::comprobanteId)
                .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("la partida trae importe, imputado y pendiente (importe − imputado) con escala 2, y la referencia y la fecha del débito")
    void laPartidaTrae() {
        PartidasAbiertas partidas = de(Map.of(10L, new BigDecimal("500")), debito(10, 1, "2000"));

        Partida partida = partidas.abiertas().get(0);

        assertThat(partida.comprobanteId()).isEqualTo(10L);
        assertThat(partida.referencia()).isEqualTo("Venta N.º 10");
        assertThat(partida.fecha()).isEqualTo(FECHA.plusMinutes(1));
        assertThat(partida.importe()).isEqualByComparingTo("2000.00");
        assertThat(partida.imputado()).isEqualByComparingTo("500.00");
        assertThat(partida.pendiente()).isEqualByComparingTo("1500.00");
        assertThat(partida.pendiente().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("un comprobante saldado (pendiente 0) no aparece en las abiertas pero pendienteDe lo devuelve con 0")
    void saldadoNoApareceYPendienteDeDaCero() {
        PartidasAbiertas partidas = de(Map.of(10L, new BigDecimal("300.00")), debito(10, 1, "300.00"),
                debito(20, 2, "100.00"));

        assertThat(partidas.abiertas()).extracting(Partida::comprobanteId).containsExactly(20L);
        assertThat(partidas.pendienteDe(10L)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("0"));
        assertThat(partidas.pendienteDe(20L)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("100"));
    }

    @Test
    @DisplayName("un comprobante sin débito VENTA en la cuenta: pendienteDe es vacío (el servicio lo traduce a 404)")
    void sinDebitoEsVacio() {
        PartidasAbiertas partidas = de(debito(10, 1, "300"));

        assertThat(partidas.pendienteDe(99L)).isEmpty();
    }

    @Test
    @DisplayName("el imputado de un comprobante que no está en la cuenta se ignora (no aparece ni suma)")
    void imputadoAjenoSeIgnora() {
        PartidasAbiertas partidas = de(Map.of(77L, new BigDecimal("50")), debito(10, 1, "300"));

        assertThat(partidas.abiertas()).hasSize(1);
        assertThat(partidas.totalPendiente()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("totalPendiente suma los pendientes de las abiertas (el saldado no suma)")
    void totalPendiente() {
        PartidasAbiertas partidas = de(Map.of(10L, new BigDecimal("100"), 30L, new BigDecimal("400")),
                debito(10, 1, "300"), debito(20, 2, "500"), debito(30, 3, "400"));

        assertThat(partidas.totalPendiente()).isEqualByComparingTo("700.00");
        assertThat(de().totalPendiente()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("sin débitos (cliente sin deuda, adelanto): no hay abiertas ni excesos para una lista vacía")
    void sinDeudaNoHayAbiertas() {
        PartidasAbiertas partidas = de();

        assertThat(partidas.abiertas()).isEmpty();
        assertThat(partidas.excesos(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("imputar exactamente el pendiente no es exceso")
    void exactoNoEsExceso() {
        PartidasAbiertas partidas = de(Map.of(10L, new BigDecimal("500")), debito(10, 1, "1000"));

        assertThat(partidas.excesos(Map.of(10L, new BigDecimal("500.00")))).isEmpty();
    }

    @Test
    @DisplayName("CC-030/051: un exceso de UN centavo se detecta y trae la deuda actual (pendiente) del comprobante")
    void excesoDeUnCentavo() {
        PartidasAbiertas partidas = de(debito(10, 1, "1000.00"));

        List<Partida> excesos = partidas.excesos(Map.of(10L, new BigDecimal("1000.01")));

        assertThat(excesos).hasSize(1);
        assertThat(excesos.get(0).comprobanteId()).isEqualTo(10L);
        assertThat(excesos.get(0).referencia()).isEqualTo("Venta N.º 10");
        assertThat(excesos.get(0).pendiente()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("la comparación es por valor y no por escala: pendiente 100,10 con renglón 100,1 y pendiente 10 con renglón 10.00 no son exceso")
    void compareToNoEquals() {
        assertThat(de(debito(10, 1, "100.10")).excesos(Map.of(10L, new BigDecimal("100.1")))).isEmpty();
        assertThat(de(debito(10, 1, "10")).excesos(Map.of(10L, new BigDecimal("10.00")))).isEmpty();
        assertThat(de(debito(10, 1, "10.00")).excesos(Map.of(10L, new BigDecimal("10")))).isEmpty();
    }

    @Test
    @DisplayName("imputar contra un comprobante saldado (pendiente 0) es exceso, y la deuda actual que trae es 0")
    void contraSaldadoEsExcesoConPendienteCero() {
        PartidasAbiertas partidas = de(Map.of(10L, new BigDecimal("300")), debito(10, 1, "300"));

        List<Partida> excesos = partidas.excesos(Map.of(10L, BigDecimal.ONE));

        assertThat(excesos).hasSize(1);
        assertThat(excesos.get(0).pendiente()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("excesos devuelve TODOS los comprobantes excedidos, ordenados por id, no solo el primero; los válidos no figuran")
    void excesosDevuelveTodos() {
        PartidasAbiertas partidas = de(debito(30, 3, "400"), debito(10, 1, "300"), debito(20, 2, "500"));

        List<Partida> excesos = partidas.excesos(Map.of(30L, new BigDecimal("401"), 10L, new BigDecimal("301"),
                20L, new BigDecimal("500")));

        assertThat(excesos).extracting(Partida::comprobanteId).containsExactly(10L, 30L);
        assertThat(excesos).extracting(Partida::pendiente).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300"), new BigDecimal("400"));
    }

    @Test
    @DisplayName("T8: pendiente 100,00 con 33,33 + 33,33 + 33,34 sucesivos no excede nunca; con 33,34 + 33,34 + 33,33 la tercera excede (100,01)")
    void redondeoDeCentavosSucesivos() {
        BigDecimal debe = new BigDecimal("100.00");
        BigDecimal acumulado = BigDecimal.ZERO;
        for (String importe : List.of("33.33", "33.33", "33.34")) {
            PartidasAbiertas partidas = de(Map.of(10L, acumulado), debito(10, 1, debe.toPlainString()));
            assertThat(partidas.excesos(Map.of(10L, new BigDecimal(importe)))).as("imputar %s tras %s", importe, acumulado)
                    .isEmpty();
            acumulado = acumulado.add(new BigDecimal(importe));
        }
        assertThat(de(Map.of(10L, acumulado), debito(10, 1, "100.00")).abiertas()).as("quedó saldado").isEmpty();

        acumulado = BigDecimal.ZERO;
        for (String importe : List.of("33.34", "33.34")) {
            assertThat(de(Map.of(10L, acumulado), debito(10, 1, "100.00")).excesos(Map.of(10L, new BigDecimal(importe))))
                    .isEmpty();
            acumulado = acumulado.add(new BigDecimal(importe));
        }
        List<Partida> excesos = de(Map.of(10L, acumulado), debito(10, 1, "100.00"))
                .excesos(Map.of(10L, new BigDecimal("33.33")));
        assertThat(excesos).hasSize(1);
        assertThat(excesos.get(0).pendiente()).isEqualByComparingTo("33.32");
    }

    @Test
    @DisplayName("T4: centavos .01 y .99 se manejan exactos: 0,01 + 0,99 saldan un comprobante de 1,00 sin exceso")
    void centavos() {
        PartidasAbiertas partidas = de(debito(10, 1, "0.01"), debito(20, 2, "0.99"));

        assertThat(partidas.excesos(Map.of(10L, new BigDecimal("0.01"), 20L, new BigDecimal("0.99")))).isEmpty();
        assertThat(partidas.excesos(Map.of(10L, new BigDecimal("0.02")))).extracting(Partida::comprobanteId)
                .containsExactly(10L);
        assertThat(partidas.totalPendiente()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("excesos de un comprobante que no está en la cuenta es un error de programación (el servicio ya lo pasó a 404)")
    void excesosDeComprobanteDesconocido() {
        PartidasAbiertas partidas = de(debito(10, 1, "300"));

        assertThatThrownBy(() -> partidas.excesos(Map.of(99L, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("99");
    }

    @Test
    @DisplayName("importes a imputar nulos, cero o negativos, y débitos repetidos, son argumentos mal formados")
    void argumentosMalFormados() {
        PartidasAbiertas partidas = de(debito(10, 1, "300"));

        assertThatThrownBy(() -> partidas.excesos(Map.of(10L, BigDecimal.ZERO))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> partidas.excesos(Map.of(10L, new BigDecimal("-1")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartidasAbiertas(List.of(debito(10, 1, "300"), debito(10, 2, "100")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10");
        assertThatThrownBy(() -> new PartidasAbiertas(null, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
