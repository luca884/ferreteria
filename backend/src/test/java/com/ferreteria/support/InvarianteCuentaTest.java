package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El oráculo de la cuenta corriente se prueba a sí mismo (CCT3-208, CC-002, CC-040): con datos sembrados por SQL crudo, una
 * cuenta consistente PASA y cada violación de I1 / I2a–d FALLA con el nombre de la invariante. Un oráculo que nunca falla no
 * sirve para vigilar la sobre-imputación.
 */
class InvarianteCuentaTest extends CuentaCorrienteTestBase {

    private void fallaCon(long cliente, String invariante) {
        assertThatThrownBy(() -> assertInvariante(cliente)).isInstanceOf(AssertionError.class)
                .hasMessageContaining(invariante);
    }

    @Test
    @DisplayName("cuenta consistente: ventas, un cobro imputado en parte y el resto a cuenta → pasa")
    void consistente() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "400.00", "100.00"); // un comprobante con dos pagos CC → debe 500
        ventaEnLibro(f, cliente, "300.00");
        long recibo = cobroEnLibro(f, cliente, "700.00");
        imputar(f, cliente, recibo, venta.comprobanteId(), "450.00"); // 250 a cuenta

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
        // saldo = 800 − 700; pendientes = 50 + 300; a cuenta = 250 → 350 − 250 = 100
        org.assertj.core.api.Assertions.assertThat(saldo(cliente)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("cliente nuevo, sin libro ni recibos: pasa")
    void vacio() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adelanto: saldo −500, 0 pendientes y un recibo de 500 sin imputaciones → pasa")
    void adelantoSinDeuda() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        cobroEnLibro(f, cliente, "500.00");

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(saldo(cliente)).isEqualByComparingTo("-500.00");
    }

    @Test
    @DisplayName("un saldo inicial por AJUSTE (como siembran los tests de tramo 2) no rompe I2c")
    void ajusteNoRompe() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "referencia, motivo) values (?, ?, 'AJUSTE', 900, 0, now(6), ?, 'Saldo inicial de test', "
                + "'Saldo inicial de test')", f.id(), cliente,
                f.usuarioId());
        jdbc.update("update cliente set saldo = 900 where id = ?", cliente);

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("I1: el saldo guardado difiere de Σ(debe − haber) del libro → falla")
    void i1() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        ventaEnLibro(f, cliente, "300.00");
        jdbc.update("update cliente set saldo = saldo + 1 where id = ?", cliente);

        fallaCon(cliente, "I1");
    }

    @Test
    @DisplayName("I2a: lo imputado a un comprobante supera su débito (pendiente negativo) → falla")
    void i2a() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "100.00");
        long recibo = cobroEnLibro(f, cliente, "300.00");
        imputar(f, cliente, recibo, venta.comprobanteId(), "150.00"); // 150 > 100

        fallaCon(cliente, "I2a");
    }

    @Test
    @DisplayName("I2a: una imputación de a un centavo de más se detecta (compareTo, sin redondeo)")
    void i2aUnCentavo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "100.00");
        long recibo = cobroEnLibro(f, cliente, "300.00");
        imputar(f, cliente, recibo, venta.comprobanteId(), "100.01");

        fallaCon(cliente, "I2a");
    }

    @Test
    @DisplayName("I2b: un recibo con Σ imputaciones mayor que su total → falla")
    void i2bImputacionesMayoresQueElTotal() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        long recibo = cobroEnLibro(f, cliente, "100.00");
        imputar(f, cliente, recibo, venta.comprobanteId(), "150.00"); // 150 > 100 del recibo

        fallaCon(cliente, "I2b");
    }

    @Test
    @DisplayName("I2b: un recibo sin su COBRO en el libro → falla")
    void i2bSinCobro() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        ventaEnLibro(f, cliente, "500.00");
        reciboSuelto(f, cliente, "100.00"); // recibo sin movimiento COBRO

        fallaCon(cliente, "I2b");
    }

    @Test
    @DisplayName("I2b: el COBRO del recibo tiene un haber distinto del total → falla")
    void i2bCobroConOtroImporte() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        long recibo = reciboSuelto(f, cliente, "100.00");
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "recibo_id, referencia) values (?, ?, 'COBRO', 0, 90, now(6), ?, ?, 'Recibo N.º test')", f.id(), cliente,
                f.usuarioId(), recibo);
        jdbc.update("update cliente set saldo = saldo - 90 where id = ?", cliente); // I1 se cumple

        fallaCon(cliente, "I2b");
    }

    @Test
    @DisplayName("I2d: una imputación cuyo cliente no es el del recibo → falla")
    void i2dClienteDistintoDelRecibo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        long otro = cliente(f, "1000.00");
        VentaSuelta venta = ventaEnLibro(f, otro, "500.00");
        long recibo = cobroEnLibro(f, cliente, "100.00");
        imputar(f, otro, recibo, venta.comprobanteId(), "50.00"); // recibo de `cliente`, imputación y débito de `otro`

        fallaCon(cliente, "I2d");
        fallaCon(otro, "I2d");
    }

    @Test
    @DisplayName("I2d: una imputación a un comprobante que no es débito del cliente de la imputación → falla")
    void i2dComprobanteAjeno() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        long otro = cliente(f, "1000.00");
        VentaSuelta deOtro = ventaEnLibro(f, otro, "500.00");
        long recibo = cobroEnLibro(f, cliente, "100.00");
        imputar(f, cliente, recibo, deOtro.comprobanteId(), "50.00"); // el comprobante es de `otro`

        fallaCon(cliente, "I2d");
    }

    // ---- CCT3B1-206: vigencia, anulación (I2e) y cliente inactivo (I-inactivo) ----

    @Test
    @DisplayName("anulación bien hecha: cobro imputado a dos comprobantes, recibo ANULADO, imputaciones no vigentes y ANULACION → pasa; el saldo vuelve a la deuda")
    void anulacionBienHechaPasa() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "600.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "400.00");
        long recibo = cobroEnLibro(f, cliente, "1000.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "600.00");
        imputar(f, cliente, recibo, b.comprobanteId(), "400.00");
        assertThatCode(() -> assertInvariante(cliente)).as("antes de anular").doesNotThrowAnyException();

        anularRecibo(f, cliente, recibo);

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(saldo(cliente)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("anulación de un cobro parcial (700, imputó 400 y 300 a cuenta): pasa y el saldo vuelve a 1.000")
    void anulacionParcialPasa() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        long recibo = cobroEnLibro(f, cliente, "700.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "400.00");

        anularRecibo(f, cliente, recibo);

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(saldo(cliente)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("adelanto anulado (saldo −500 → 0, sin imputaciones): pasa")
    void adelantoAnuladoPasa() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long recibo = cobroEnLibro(f, cliente, "500.00");

        anularRecibo(f, cliente, recibo);

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(saldo(cliente)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("mixto: R1 imputa A y B, R2 imputa A; se anula R1 → R2 sigue vigente y I2a mira solo lo vigente (la no vigente de R1 + la de R2 superarían el débito de A)")
    void anulacionMixtaPasa() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "50.00");
        long r1 = cobroEnLibro(f, cliente, "150.00");
        imputar(f, cliente, r1, a.comprobanteId(), "100.00");
        imputar(f, cliente, r1, b.comprobanteId(), "50.00");
        anularRecibo(f, cliente, r1);
        long r2 = cobroEnLibro(f, cliente, "100.00");
        imputar(f, cliente, r2, a.comprobanteId(), "100.00"); // vuelve a cobrar A: 100 no vigentes + 100 vigentes > 100

        assertThatCode(() -> assertInvariante(cliente)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("I2e: una imputación VIGENTE de un recibo ANULADO → falla")
    void i2eImputacionVigenteDeReciboAnulado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "600.00");
        long recibo = cobroEnLibro(f, cliente, "600.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "600.00");
        marcarReciboAnulado(f, recibo);
        anulacionEnLibro(f, cliente, recibo, "600.00"); // pero la imputación sigue vigente

        fallaCon(cliente, "I2e");
    }

    @Test
    @DisplayName("I2e: una ANULACION cuyo recibo no se marcó ANULADO ni dio de baja sus imputaciones (fila corrupta a mano) → falla")
    void i2eAnulacionSinReciboAnulado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long recibo = cobroEnLibro(f, cliente, "100.00");
        anulacionEnLibro(f, cliente, recibo, "100.00"); // I1 ok: saldo 0

        fallaCon(cliente, "I2e");
    }

    @Test
    @DisplayName("I2e: un recibo ANULADO sin su ANULACION en el libro → falla")
    void i2eReciboAnuladoSinAnulacion() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long recibo = cobroEnLibro(f, cliente, "100.00");
        imputar(f, cliente, recibo, a.comprobanteId(), "100.00");
        marcarReciboAnulado(f, recibo);
        jdbc.update("update imputacion set vigente = false where recibo_id = ?", recibo);

        fallaCon(cliente, "I2e");
    }

    @Test
    @DisplayName("I2e: una imputación no vigente cuyo recibo sigue EMITIDO → falla")
    void i2eImputacionNoVigenteConReciboEmitido() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long recibo = cobroEnLibro(f, cliente, "100.00");
        imputarNoVigente(f, cliente, recibo, a.comprobanteId(), "100.00");

        fallaCon(cliente, "I2e");
    }

    @Test
    @DisplayName("I2e: la caja de la anulación es de otra ferretería → falla")
    void i2eCajaDeLaAnulacionDeOtraFerreteria() {
        Ferreteria f = ferreteria();
        Ferreteria otra = ferreteria();
        long cliente = cliente(f, null);
        long recibo = cobroEnLibro(f, cliente, "100.00");
        anularRecibo(f, cliente, recibo);
        jdbc.update("update recibo set caja_anulacion_id = ? where id = ?", cajaCerrada(otra), recibo);

        fallaCon(cliente, "I2e");
    }

    @Test
    @DisplayName("I2b: el recibo ANULADO tiene una ANULACION con debe distinto del total → falla")
    void i2bAnulacionConOtroImporte() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long recibo = cobroEnLibro(f, cliente, "100.00");
        anularRecibo(f, cliente, recibo);
        jdbc.update("update movimiento_cuenta_cliente set debe = 90 where recibo_id = ? and tipo = 'ANULACION'", recibo);
        jdbc.update("update cliente set saldo = saldo - 10 where id = ?", cliente); // I1 se cumple

        fallaCon(cliente, "I2b");
    }

    @Test
    @DisplayName("I-inactivo: un cliente desactivado con saldo distinto de cero → falla")
    void iInactivoConSaldo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        ventaEnLibro(f, cliente, "100.00");
        jdbc.update("update cliente set activo = false where id = ?", cliente);

        fallaCon(cliente, "I-inactivo");
    }

    @Test
    @DisplayName("I-inactivo: un cliente desactivado con saldo 0 (su adelanto fue anulado) pasa; el mismo activo con saldo ≠ 0 también")
    void iInactivoConSaldoCeroPasa() {
        Ferreteria f = ferreteria();
        long anulado = cliente(f, null);
        anularRecibo(f, anulado, cobroEnLibro(f, anulado, "500.00"));
        jdbc.update("update cliente set activo = false where id = ?", anulado);
        long conDeuda = cliente(f, "5000.00");
        ventaEnLibro(f, conDeuda, "100.00");

        assertThatCode(() -> assertInvariante(anulado)).doesNotThrowAnyException();
        assertThatCode(() -> assertInvariante(conDeuda)).doesNotThrowAnyException();
    }
}
