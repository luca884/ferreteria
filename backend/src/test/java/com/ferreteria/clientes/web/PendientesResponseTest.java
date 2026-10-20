package com.ferreteria.clientes.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.clientes.service.PendientesCuenta.Pendiente;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CCT3-401 (CC-039): la forma de la respuesta de pendientes, sin Spring. */
class PendientesResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Pendiente pendiente(long id, String referencia, String importe, String imputado, String pendiente) {
        return new Pendiente(id, referencia, LocalDateTime.of(2026, 10, 12, 9, 30), bd(importe), bd(imputado), bd(pendiente));
    }

    @Test
    @DisplayName("lleva saldo, aCuenta y los comprobantes con referencia, fecha, importe, imputado y pendiente, en el mismo orden")
    void forma() {
        PendientesCuenta cuenta = new PendientesCuenta(bd("400.00"), bd("0.00"), List.of(
                pendiente(7L, "Venta N.º 7", "300.00", "100.00", "200.00"),
                pendiente(9L, "Venta N.º 9", "200.00", "0.00", "200.00")));

        PendientesResponse respuesta = PendientesResponse.de(cuenta);

        assertThat(respuesta.saldo()).isEqualByComparingTo("400");
        assertThat(respuesta.aCuenta()).isEqualByComparingTo("0");
        assertThat(respuesta.comprobantes()).extracting(PendientesResponse.Comprobante::comprobanteId).containsExactly(7L, 9L);
        PendientesResponse.Comprobante primero = respuesta.comprobantes().get(0);
        assertThat(primero.referencia()).isEqualTo("Venta N.º 7");
        assertThat(primero.fecha()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 30));
        assertThat(primero.importe()).isEqualByComparingTo("300");
        assertThat(primero.imputado()).isEqualByComparingTo("100");
        assertThat(primero.pendiente()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("todos los importes salen con 2 decimales, aunque el servicio los entregue con otra escala")
    void escalaDos() throws Exception {
        PendientesCuenta cuenta = new PendientesCuenta(bd("-500"), bd("500"),
                List.of(pendiente(7L, "Venta N.º 7", "300", "100", "200")));

        PendientesResponse respuesta = PendientesResponse.de(cuenta);
        String texto = JSON.writeValueAsString(respuesta);

        assertThat(respuesta.saldo().scale()).isEqualTo(2);
        assertThat(texto).contains("\"saldo\":-500.00", "\"aCuenta\":500.00", "\"importe\":300.00", "\"imputado\":100.00",
                "\"pendiente\":200.00");
    }

    @Test
    @DisplayName("sin deuda y sin adelanto: lista vacía, saldo 0 y aCuenta 0; con adelanto: lista vacía, saldo −500, aCuenta 500")
    void sinComprobantes() {
        PendientesResponse sinNada = PendientesResponse.de(new PendientesCuenta(bd("0.00"), bd("0.00"), List.of()));
        PendientesResponse adelanto = PendientesResponse.de(new PendientesCuenta(bd("-500.00"), bd("500.00"), List.of()));

        assertThat(sinNada.comprobantes()).isEmpty();
        assertThat(sinNada.saldo()).isEqualByComparingTo("0");
        assertThat(sinNada.aCuenta()).isEqualByComparingTo("0");
        assertThat(adelanto.comprobantes()).isEmpty();
        assertThat(adelanto.saldo()).isEqualByComparingTo("-500");
        assertThat(adelanto.aCuenta()).isEqualByComparingTo("500");
    }
}
