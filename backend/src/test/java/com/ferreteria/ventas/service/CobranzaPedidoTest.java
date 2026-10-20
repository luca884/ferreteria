package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException.DeudaActual;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CCT3-307 (CC-029, CC-030, CC-035, CC-038): el pedido de cobranza, la comparación de «mismo contenido» para la idempotencia
 * y el motivo del rechazo con su estado HTTP. Sin Spring.
 */
class CobranzaPedidoTest {

    private static final String CLAVE = "5a1b0c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d";

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Imputar imputar(long comprobanteId, String importe) {
        return new Imputar(comprobanteId, bd(importe));
    }

    private static ImputacionAsentada asentada(long comprobanteId, String importe) {
        return new ImputacionAsentada(comprobanteId, "Venta N.º " + comprobanteId, bd(importe));
    }

    private static Recibo recibo(long clienteId, MedioPago medio, String total) {
        return Recibo.emitir(1L, 5, CLAVE, clienteId, "Cliente", 9L, 2L, LocalDateTime.of(2026, 10, 19, 10, 0), medio, bd(total));
    }

    private static CobranzaPedido pedido(long clienteId, MedioPago medio, String total, Imputar... imputaciones) {
        return new CobranzaPedido(CLAVE, clienteId, medio, bd(total), List.of(imputaciones));
    }

    @Test
    @DisplayName("mismo cliente, medio y total (10 vs 10.00) e iguales imputaciones: mismo contenido")
    void igual() {
        CobranzaPedido pedido = pedido(7, MedioPago.EFECTIVO, "10", imputar(1, "4"), imputar(2, "6.00"));

        assertThat(pedido.mismoContenido(recibo(7, MedioPago.EFECTIVO, "10.00"), List.of(asentada(1, "4.00"), asentada(2, "6")))).isTrue();
    }

    @Test
    @DisplayName("las imputaciones en otro orden son el mismo contenido (conjunto, no lista)")
    void otroOrden() {
        CobranzaPedido pedido = pedido(7, MedioPago.EFECTIVO, "100", imputar(2, "30"), imputar(1, "70"));

        assertThat(pedido.mismoContenido(recibo(7, MedioPago.EFECTIVO, "100"), List.of(asentada(1, "70"), asentada(2, "30")))).isTrue();
    }

    @Test
    @DisplayName("un adelanto (sin imputaciones) repetido es el mismo contenido")
    void adelantoIgual() {
        assertThat(pedido(7, MedioPago.TRANSFERENCIA, "500").mismoContenido(recibo(7, MedioPago.TRANSFERENCIA, "500.00"), List.of()))
                .isTrue();
    }

    @Test
    @DisplayName("otro total, medio, cliente, importe de un renglón o un comprobante de más: otro contenido")
    void distinto() {
        Recibo recibo = recibo(7, MedioPago.EFECTIVO, "100");
        List<ImputacionAsentada> asentadas = List.of(asentada(1, "70"), asentada(2, "30"));
        CobranzaPedido base = pedido(7, MedioPago.EFECTIVO, "100", imputar(1, "70"), imputar(2, "30"));
        assertThat(base.mismoContenido(recibo, asentadas)).as("control").isTrue();

        assertThat(pedido(7, MedioPago.EFECTIVO, "100.01", imputar(1, "70"), imputar(2, "30")).mismoContenido(recibo, asentadas))
                .as("total").isFalse();
        assertThat(pedido(7, MedioPago.TRANSFERENCIA, "100", imputar(1, "70"), imputar(2, "30")).mismoContenido(recibo, asentadas))
                .as("medio").isFalse();
        assertThat(pedido(8, MedioPago.EFECTIVO, "100", imputar(1, "70"), imputar(2, "30")).mismoContenido(recibo, asentadas))
                .as("cliente").isFalse();
        assertThat(pedido(7, MedioPago.EFECTIVO, "100", imputar(1, "70"), imputar(2, "29.99")).mismoContenido(recibo, asentadas))
                .as("importe de un renglón").isFalse();
        assertThat(pedido(7, MedioPago.EFECTIVO, "100", imputar(1, "70"), imputar(2, "30"), imputar(3, "0.01"))
                .mismoContenido(recibo, asentadas)).as("comprobante de más en el pedido").isFalse();
        assertThat(pedido(7, MedioPago.EFECTIVO, "100", imputar(1, "70")).mismoContenido(recibo, asentadas))
                .as("comprobante de menos en el pedido").isFalse();
        assertThat(pedido(7, MedioPago.EFECTIVO, "100", imputar(1, "70"), imputar(3, "30")).mismoContenido(recibo, asentadas))
                .as("otro comprobante").isFalse();
        assertThat(pedido(7, MedioPago.EFECTIVO, "100").mismoContenido(recibo, asentadas)).as("pedido a cuenta vs recibo imputado")
                .isFalse();
    }

    @Test
    @DisplayName("el estado HTTP sale del motivo: 422 caja cerrada / de otro día / clave con otro contenido; 409 imputación que excede la deuda")
    void estadoHttpPorMotivo() {
        assertThat(Motivo.CAJA_CERRADA.estadoHttp()).isEqualTo(422);
        assertThat(Motivo.CAJA_DE_OTRO_DIA.estadoHttp()).isEqualTo(422);
        assertThat(Motivo.CLAVE_CON_OTRO_CONTENIDO.estadoHttp()).isEqualTo(422);
        assertThat(Motivo.IMPUTACION_EXCEDE_DEUDA.estadoHttp()).isEqualTo(409);
    }

    @Test
    @DisplayName("el rechazo lleva el motivo, el mensaje y las deudas actuales (vacías si no aplica)")
    void rechazoConDeudas() {
        DeudaActual deuda = new DeudaActual(3L, "Venta N.º 3", bd("0.00"));
        CobranzaRechazadaException conDeudas =
                new CobranzaRechazadaException(Motivo.IMPUTACION_EXCEDE_DEUDA, "La deuda cambió.", List.of(deuda));
        CobranzaRechazadaException sinDeudas = new CobranzaRechazadaException(Motivo.CAJA_CERRADA, "Abrí la caja para cobrar.");

        assertThat(conDeudas.motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
        assertThat(conDeudas.getMessage()).isEqualTo("La deuda cambió.");
        assertThat(conDeudas.deudas()).containsExactly(deuda);
        assertThat(sinDeudas.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
        assertThat(sinDeudas.deudas()).isEmpty();
    }

    @Test
    @DisplayName("CobroRegistrado: lo que no se imputó queda «a cuenta» = total − Σ imputaciones (derivado, nunca negativo)")
    void aCuentaDerivado() {
        Recibo recibo = recibo(7, MedioPago.EFECTIVO, "1500.00");

        CobroRegistrado imputado = new CobroRegistrado(recibo, List.of(asentada(1, "1000.00")), "Ana", false);
        CobroRegistrado adelanto = new CobroRegistrado(recibo, List.of(), "Ana", true);

        assertThat(imputado.aCuenta()).isEqualByComparingTo("500");
        assertThat(adelanto.aCuenta()).isEqualByComparingTo("1500");
        assertThat(adelanto.repetida()).isTrue();
        assertThat(imputado.cobradoPor()).isEqualTo("Ana");
    }

    @Test
    @DisplayName("un pedido sin lista de imputaciones (null) es lo mismo que una lista vacía: todo «a cuenta», y se compara sin reventar")
    void imputacionesNulasSonVacias() {
        CobranzaPedido pedido = new CobranzaPedido(CLAVE, 7L, MedioPago.EFECTIVO, bd("500"), null);

        assertThat(pedido.imputaciones()).isEmpty();
        assertThat(pedido.mismoContenido(recibo(7, MedioPago.EFECTIVO, "500.00"), List.of())).isTrue();
    }
}
