package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CCT3B1-306 (CC-060, CC-069): el rechazo por recibo ya anulado, su estado HTTP y su mensaje, sin Spring. */
class CobranzaRechazadaExceptionTest {

    @Test
    @DisplayName("RECIBO_YA_ANULADO es 409 y los motivos de 3a conservan su estado (422 de caja y clave, 409 de la deuda)")
    void estadosHttp() {
        assertThat(Motivo.RECIBO_YA_ANULADO.estadoHttp()).isEqualTo(409);
        assertThat(Motivo.CAJA_CERRADA.estadoHttp()).isEqualTo(422);
        assertThat(Motivo.CAJA_DE_OTRO_DIA.estadoHttp()).isEqualTo(422);
        assertThat(Motivo.CLAVE_CON_OTRO_CONTENIDO.estadoHttp()).isEqualTo(422);
        assertThat(Motivo.IMPUTACION_EXCEDE_DEUDA.estadoHttp()).isEqualTo(409);
    }

    @Test
    @DisplayName("reciboYaAnulado dice quién y cuándo con fecha dd/MM/yyyy HH:mm y los lleva en campos")
    void mensajeConQuienYCuando() {
        CobranzaRechazadaException e = CobranzaRechazadaException.reciboYaAnulado("Ana", LocalDateTime.of(2026, 10, 5, 9, 7));

        assertThat(e.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO);
        assertThat(e.getMessage()).isEqualTo("Este recibo ya fue anulado por Ana el 05/10/2026 09:07.");
        assertThat(e.anuladoPor()).isEqualTo("Ana");
        assertThat(e.fechaAnulacion()).isEqualTo(LocalDateTime.of(2026, 10, 5, 9, 7));
        assertThat(e.deudas()).isEmpty();
    }

    @Test
    @DisplayName("los rechazos de 3a no llevan anuladoPor ni fechaAnulacion")
    void losDe3aNoLlevanAnulacion() {
        CobranzaRechazadaException e = new CobranzaRechazadaException(Motivo.CAJA_CERRADA, "Abrí la caja para cobrar.");

        assertThat(e.anuladoPor()).isNull();
        assertThat(e.fechaAnulacion()).isNull();
    }

    @Test
    @DisplayName("CobroRegistrado: el constructor de 4 argumentos sigue compilando con anuladoPor = null; el de 5 lo lleva")
    void cobroRegistradoConservaElConstructorViejo() {
        Recibo recibo = Recibo.emitir(3L, 1L, "7c9e6679-7425-40de-944b-e07fc1f90ae7", 5L, "Norte", 40L, 9L,
                LocalDateTime.of(2026, 10, 19, 11, 30), MedioPago.EFECTIVO, new BigDecimal("100.00"));
        List<ImputacionAsentada> sinImputar = List.of();

        assertThat(new CobroRegistrado(recibo, sinImputar, "Ana", false).anuladoPor()).isNull();
        assertThat(new CobroRegistrado(recibo, sinImputar, "Ana", true, "Marta").anuladoPor()).isEqualTo("Marta");
        assertThat(new CobroRegistrado(recibo, sinImputar, "Ana", true, "Marta").repetida()).isTrue();
    }
}
