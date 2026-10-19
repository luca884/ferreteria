package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * La invariante de la cuenta corriente (CC-001, CC-010): {@code cliente.saldo} siempre es Σ(debe − haber) del libro
 * {@code movimiento_cuenta_cliente} del cliente. Se llama al final de CADA escenario de integración y de concurrencia
 * (también tras rollbacks y repetidas), y paso a paso en el test de propiedad.
 */
public final class InvarianteCuenta {

    private InvarianteCuenta() {}

    public static void assertInvariante(JdbcTemplate jdbc, long clienteId) {
        BigDecimal saldo = jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
        BigDecimal libro = jdbc.queryForObject(
                "select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente where cliente_id = ?",
                BigDecimal.class, clienteId);
        assertThat(saldo).as("saldo del cliente %d == Σ(debe − haber) del libro", clienteId).isEqualByComparingTo(libro);
    }
}
