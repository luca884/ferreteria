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

    @Test
    @DisplayName("I2c: un contra-asiento ANULACION (que 3a no escribe) rompe la igualdad global en vez de pasar en silencio")
    void i2cAnulacionSinSoporte() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long recibo = cobroEnLibro(f, cliente, "100.00");
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "recibo_id, referencia, motivo) values (?, ?, 'ANULACION', 100, 0, now(6), ?, ?, 'Anulación test', "
                + "'Cobro mal cargado')", f.id(),
                cliente, f.usuarioId(), recibo);
        jdbc.update("update cliente set saldo = saldo + 100 where id = ?", cliente); // I1 ok: saldo 0

        assertThatCode(() -> org.assertj.core.api.Assertions.assertThat(saldo(cliente)).isEqualByComparingTo(BigDecimal.ZERO))
                .doesNotThrowAnyException();
        fallaCon(cliente, "I2c");
    }
}
