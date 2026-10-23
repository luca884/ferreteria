package com.ferreteria.clientes;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Siembra por jdbc la cuenta corriente de un cliente para los tests HTTP: el límite y un saldo inicial con su movimiento
 * AJUSTE en el libro, así la invariante saldo == Σ(debe − haber) se cumple desde el arranque.
 */
final class SemillaCuentaCliente {

    private SemillaCuentaCliente() {}

    /** {@code limite} null deja la cuenta sin habilitar; {@code saldo} positivo es deuda, negativo es saldo a favor. */
    static void sembrar(JdbcTemplate jdbc, long ferreteriaId, long clienteId, String limite, String saldo) {
        if (limite != null) {
            jdbc.update("update cliente set limite_cuenta_corriente = ? where id = ?", new BigDecimal(limite), clienteId);
        }
        BigDecimal importe = new BigDecimal(saldo);
        if (importe.signum() == 0) {
            return;
        }
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class,
                ferreteriaId);
        // V28: todo AJUSTE del libro lleva su ajuste (un Recargo si sube la deuda, un Descuento si la baja)
        long ajuste = SemillaAjuste.ajuste(jdbc, ferreteriaId, clienteId, usuario, importe.signum() > 0 ? "RECARGO" : "DESCUENTO",
                importe.abs());
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "referencia, motivo, ajuste_id) values (?, ?, 'AJUSTE', ?, ?, now(6), ?, 'Saldo inicial de test', "
                + "'Saldo inicial de test', ?)", ferreteriaId, clienteId,
                importe.signum() > 0 ? importe : BigDecimal.ZERO, importe.signum() < 0 ? importe.negate() : BigDecimal.ZERO,
                usuario, ajuste);
        jdbc.update("update cliente set saldo = ? where id = ?", importe, clienteId);
    }
}
