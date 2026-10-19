package com.ferreteria.clientes;

import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.InvarianteCuenta;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;

/**
 * Datos de apoyo de los tests de integración de la cuenta corriente (corte 2): ferretería con su Dueño, clientes con y sin
 * límite, y ventas sueltas (comprobante + pago a cuenta corriente) para colgar los movimientos del libro. CUIT 30-8302NNNN-1.
 */
public abstract class CuentaCorrienteTestBase extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    /** Una ferretería de prueba con su usuario Dueño. */
    protected record Ferreteria(long id, long usuarioId) {}

    /** El comprobante y el pago a cuenta corriente de una venta suelta. */
    protected record VentaSuelta(long comprobanteId, long pagoId, long numero) {}

    @AfterEach
    void limpiarTenant() {
        TenantContext.clear();
    }

    protected Ferreteria ferreteria() {
        int n = SECUENCIA.incrementAndGet();
        String cuit = "30-8302%04d-1".formatted(n);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Cuenta corriente " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "cc-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno CC', 'DUENO')", id, email);
        long usuario = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
                + "values (?, 'Cuenta corriente', 'CUENTA_CORRIENTE', 0, 1, 5)", id);
        return new Ferreteria(id, usuario);
    }

    /** Un cliente de la ferretería con ese límite (null = sin cuenta corriente) y saldo 0. */
    protected long cliente(Ferreteria f, String limite) {
        String razon = "Cliente " + SECUENCIA.incrementAndGet();
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, ?)", f.id(),
                razon, limite == null ? null : new BigDecimal(limite));
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class, f.id(),
                razon);
    }

    /** Un comprobante y un pago a cuenta corriente de ese monto (sin tocar el libro ni el saldo). */
    protected VentaSuelta ventaSuelta(Ferreteria f, long clienteId, String monto) {
        int n = SECUENCIA.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, cliente_id, usuario_id, "
                + "neto, iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, ?, 0, 0, ?, ?)", f.id(), n,
                "00000000-0000-4000-8000-%012d".formatted(n), clienteId, f.usuarioId(), new BigDecimal(monto),
                new BigDecimal(monto));
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?",
                Long.class, f.id(), n);
        long forma = jdbc.queryForObject(
                "select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'", Long.class, f.id());
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                + "values (?, ?, 'CUENTA_CORRIENTE', ?, ?, 'Cuenta corriente')", f.id(), comprobante, new BigDecimal(monto),
                forma);
        long pago = jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, comprobante);
        return new VentaSuelta(comprobante, pago, n);
    }

    protected BigDecimal saldo(long clienteId) {
        return jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
    }

    protected long movimientos(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class,
                clienteId);
    }

    protected void assertInvariante(long clienteId) {
        InvarianteCuenta.assertInvariante(jdbc, clienteId);
    }
}
