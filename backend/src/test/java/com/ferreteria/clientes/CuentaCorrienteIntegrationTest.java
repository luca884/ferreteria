package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El puerto de la cuenta corriente contra MySQL real, con el puerto invocado como lo hará RegistroVenta: dentro de una
 * transacción que primero tomó al cliente. Cubre el asiento, el rollback, MANDATORY (g), la regresión de snapshot (e), el
 * aislamiento por ferretería y la invariante saldo == Σ libro, incluido un test de propiedad con semilla fija.
 */
class CuentaCorrienteIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CuentaCorrienteParaVenta cuentas;

    @Autowired
    ClientesParaVenta clientesParaVenta;

    @Autowired
    ClienteRepository clientes;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T enTx(Supplier<T> trabajo) {
        return new TransactionTemplate(transactionManager).execute(status -> trabajo.get());
    }

    private void enTx(Runnable trabajo) {
        enTx(() -> {
            trabajo.run();
            return null;
        });
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    // ---- asiento y rollback ----

    @Test
    @DisplayName("asentarVenta en una transacción real deja libro y saldo coherentes (un movimiento por pago)")
    void asentarVentaDejaLibroYSaldoCoherentes() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta pagoA = ventaSuelta(f, cliente, "300.00");
        VentaSuelta pagoB = ventaSuelta(f, cliente, "120.50");
        TenantContext.setCurrentFerreteriaId(f.id());

        enTx(() -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            cuentas.asentarVenta(new AsientoVenta(cliente, pagoA.comprobanteId(), pagoA.numero(),
                    List.of(new CargoPago(pagoA.pagoId(), d("300.00")), new CargoPago(pagoB.pagoId(), d("120.50"))),
                    f.usuarioId(), null));
        });

        assertThat(saldo(cliente)).isEqualByComparingTo("420.50");
        assertThat(movimientos(cliente)).isEqualTo(2);
        assertThat(jdbc.queryForList("select debe from movimiento_cuenta_cliente where cliente_id = ? order by id",
                BigDecimal.class, cliente)).extracting(BigDecimal::toPlainString).containsExactly("300.00", "120.50");
        assertThat(jdbc.queryForObject("select excedio_limite from movimiento_cuenta_cliente where pago_id = ?",
                Boolean.class, pagoA.pagoId())).isFalse();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("asentar con exceso autorizado firma el movimiento: excedio_limite y autorizado_por")
    void asentarConExcesoAutorizado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "100.00");
        VentaSuelta v = ventaSuelta(f, cliente, "500.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        enTx(() -> {
            clientesParaVenta.tomarParaVenta(cliente);
            assertThat(cuentas.verificarCupo(cliente, d("500.00")).excede()).isTrue();
            cuentas.asentarVenta(new AsientoVenta(cliente, v.comprobanteId(), v.numero(),
                    List.of(new CargoPago(v.pagoId(), d("500.00"))), f.usuarioId(), f.usuarioId()));
        });

        assertThat(jdbc.queryForObject("select autorizado_por from movimiento_cuenta_cliente where pago_id = ?", Long.class,
                v.pagoId())).isEqualTo(f.usuarioId());
        assertThat(jdbc.queryForObject("select excedio_limite from movimiento_cuenta_cliente where pago_id = ?",
                Boolean.class, v.pagoId())).isTrue();
        assertThat(saldo(cliente)).isEqualByComparingTo("500.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("si la transacción externa se revierte no queda ni movimiento ni saldo")
    void rollbackRevierteTodo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta v = ventaSuelta(f, cliente, "300.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente);
            cuentas.asentarVenta(new AsientoVenta(cliente, v.comprobanteId(), v.numero(), List.of(new CargoPago(v.pagoId(), d("300.00"))),
                    f.usuarioId(), null));
            status.setRollbackOnly();
            return null;
        });

        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(movimientos(cliente)).isZero();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("el mismo pago asentado dos veces choca con la clave única y revierte, sin duplicar movimiento ni saldo")
    void elMismoPagoNoSeAsientaDosVeces() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta v = ventaSuelta(f, cliente, "300.00");
        TenantContext.setCurrentFerreteriaId(f.id());
        AsientoVenta asiento = new AsientoVenta(cliente, v.comprobanteId(), v.numero(), List.of(new CargoPago(v.pagoId(), d("300.00"))),
                f.usuarioId(), null);
        enTx(() -> {
            clientesParaVenta.tomarParaVenta(cliente);
            cuentas.asentarVenta(asiento);
        });

        assertThatThrownBy(() -> enTx(() -> {
            clientesParaVenta.tomarParaVenta(cliente);
            cuentas.asentarVenta(asiento);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(saldo(cliente)).isEqualByComparingTo("300.00");
        assertThat(movimientos(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    // ---- tomarParaVenta: el lock ----

    @Test
    @DisplayName("tomarParaVenta toma la fila del cliente: otra conexión no puede bloquearla hasta que termine la transacción")
    void tomarParaVentaBloquea() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        enTx(() -> {
            assertThat(clientesParaVenta.tomarParaVenta(cliente)).isPresent();
            assertThatThrownBy(() -> otraConexionIntentaBloquear(cliente))
                    .isInstanceOf(SQLException.class).hasMessageContaining("NOWAIT");
        });
        otraConexionIntentaBloquear(cliente); // ya terminó la transacción: la fila está libre
    }

    private void otraConexionIntentaBloquear(long clienteId) throws SQLException {
        try (Connection c = jdbc.getDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("select id from cliente where id = " + clienteId + " for update nowait");
        }
    }

    // ---- (g) MANDATORY ----

    @Test
    @DisplayName("(g) tomarParaVenta, verificarCupo y asentarVenta sin transacción lanzan IllegalTransactionStateException")
    void mandatorySinTransaccion() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        VentaSuelta v = ventaSuelta(f, cliente, "300.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThatThrownBy(() -> clientesParaVenta.tomarParaVenta(cliente)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> cuentas.verificarCupo(cliente, d("1.00"))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> cuentas.asentarVenta(new AsientoVenta(cliente, v.comprobanteId(), v.numero(),
                List.of(new CargoPago(v.pagoId(), d("300.00"))), f.usuarioId(), null)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(movimientos(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
    }

    // ---- (e) snapshot REPEATABLE READ ----

    @Test
    @DisplayName("(e) con la foto de la transacción ya fijada, el cupo ve el saldo NUEVO confirmado por otra transacción")
    void snapshotElCupoVeElSaldoNuevo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        enTx(() -> {
            // A hace una lectura plana: en REPEATABLE READ fija la foto con saldo 0.
            assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, cliente))
                    .isEqualByComparingTo("0");
            // B suma saldo y confirma, en otra transacción (otra conexión).
            enOtraTx(() -> clientes.sumarSaldoScoped(cliente, d("900.00")));
            // La foto de A sigue vieja: esto es lo que vería un SUM del libro o un findScoped/SELECT plano.
            assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, cliente))
                    .as("la foto de A sigue siendo la vieja (el test es válido)").isEqualByComparingTo("0");

            CupoCuenta cupo = cuentas.verificarCupo(cliente, d("200.00"));

            assertThat(cupo.saldo()).isEqualByComparingTo("900.00");
            assertThat(cupo.disponible()).isEqualByComparingTo("100.00");
            assertThat(cupo.excede()).isTrue();
            assertThat(cupo.exceso()).isEqualByComparingTo("100.00");
        });
    }

    @Test
    @DisplayName("(e) variante con la entidad Cliente ya cargada en el EntityManager: el bloqueo trae escalares frescos, no la instancia gestionada")
    void snapshotConLaEntidadYaCargada() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        enTx(() -> {
            Cliente cargado = clientes.findScoped(cliente).orElseThrow(); // entidad gestionada con saldo 0 (y foto fijada)
            enOtraTx(() -> clientes.sumarSaldoScoped(cliente, d("700.00")));

            var bloqueada = clientes.bloquearCuentaScoped(cliente).orElseThrow();
            CupoCuenta cupo = cuentas.verificarCupo(cliente, d("100.00"));

            assertThat(cargado.getSaldo()).as("la instancia gestionada está desactualizada").isEqualByComparingTo("0");
            assertThat(bloqueada.getSaldo()).isEqualByComparingTo("700.00");
            assertThat(cupo.saldo()).isEqualByComparingTo("700.00");
        });
    }

    private void enOtraTx(Runnable trabajo) {
        TransactionTemplate otra = new TransactionTemplate(transactionManager);
        otra.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        otra.execute(status -> {
            trabajo.run();
            return null;
        });
    }

    // ---- aislamiento por ferretería ----

    @Test
    @DisplayName("un cliente de otra ferretería: no se toma, no se verifica, no se asienta y no se cruzan saldos")
    void aislamientoPorFerreteria() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long deB = cliente(b, "1000.00");
        VentaSuelta ventaDeB = ventaSuelta(b, deB, "100.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        enTx(() -> assertThat(clientesParaVenta.tomarParaVenta(deB)).isEmpty());
        assertThatThrownBy(() -> enTx(() -> cuentas.verificarCupo(deB, d("1.00"))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enTx(() -> cuentas.asentarVenta(new AsientoVenta(deB, ventaDeB.comprobanteId(), ventaDeB.numero(),
                List.of(new CargoPago(ventaDeB.pagoId(), d("100.00"))), a.usuarioId(), null))))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(saldo(deB)).isEqualByComparingTo("0");
        assertThat(movimientos(deB)).isZero();
        assertInvariante(deB);
    }

    // ---- invariante: test de propiedad ----

    @Test
    @DisplayName("propiedad: tras cada paso de una secuencia aleatoria (semilla fija) de ventas aceptadas, rechazadas, repetidas, revertidas y con exceso, saldo == Σ libro")
    void propiedadDeLaInvariante() {
        long semilla = Long.getLong("cc.semilla", 20261018L);
        System.out.println("propiedadDeLaInvariante: semilla=" + semilla + " (para repetirla: -Dcc.semilla=" + semilla + ")");
        Random azar = new Random(semilla);
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());
        BigDecimal esperado = BigDecimal.ZERO;
        VentaSuelta ultimaAsentada = null;
        int excedidas = 0;

        for (int paso = 0; paso < 80; paso++) {
            int accion = azar.nextInt(10);
            BigDecimal importe = BigDecimal.valueOf(1 + azar.nextInt(60000), 2); // 0,01 a 600,00
            if (accion == 7 && ultimaAsentada != null) { // repetida: el mismo pago otra vez → choca y revierte
                VentaSuelta repetida = ultimaAsentada;
                assertThatThrownBy(() -> enTx(() -> {
                    clientesParaVenta.tomarParaVenta(cliente);
                    cuentas.asentarVenta(new AsientoVenta(cliente, repetida.comprobanteId(), repetida.numero(),
                            List.of(new CargoPago(repetida.pagoId(), d("1.00"))), f.usuarioId(), null));
                })).isInstanceOf(DataIntegrityViolationException.class);
            } else if (accion == 8) { // revertida: se asienta y la transacción se cae
                VentaSuelta v = ventaSuelta(f, cliente, importe.toPlainString());
                new TransactionTemplate(transactionManager).execute(status -> {
                    clientesParaVenta.tomarParaVenta(cliente);
                    cuentas.asentarVenta(new AsientoVenta(cliente, v.comprobanteId(), v.numero(),
                            List.of(new CargoPago(v.pagoId(), importe)), f.usuarioId(), null));
                    status.setRollbackOnly();
                    return null;
                });
            } else if (accion == 9) { // se cae con una excepción después de asentar
                VentaSuelta v = ventaSuelta(f, cliente, importe.toPlainString());
                assertThatThrownBy(() -> enTx(() -> {
                    clientesParaVenta.tomarParaVenta(cliente);
                    cuentas.asentarVenta(new AsientoVenta(cliente, v.comprobanteId(), v.numero(),
                            List.of(new CargoPago(v.pagoId(), importe)), f.usuarioId(), null));
                    throw new IllegalStateException("falla posterior");
                })).isInstanceOf(IllegalStateException.class);
            } else { // venta: se rechaza si excede y no la autoriza el Dueño (accion par), o se asienta con exceso autorizado
                boolean autoriza = accion % 2 == 0;
                VentaSuelta v = ventaSuelta(f, cliente, importe.toPlainString());
                boolean seAsento = enTx(() -> {
                    clientesParaVenta.tomarParaVenta(cliente);
                    CupoCuenta cupo = cuentas.verificarCupo(cliente, importe);
                    if (cupo.excede() && !autoriza) {
                        return false;
                    }
                    cuentas.asentarVenta(new AsientoVenta(cliente, v.comprobanteId(), v.numero(),
                            List.of(new CargoPago(v.pagoId(), importe)), f.usuarioId(), cupo.excede() ? f.usuarioId() : null));
                    return true;
                });
                if (seAsento) {
                    esperado = esperado.add(importe);
                    ultimaAsentada = v;
                }
            }
            assertInvariante(cliente);
            assertThat(saldo(cliente)).as("saldo esperado tras el paso %d (semilla %d)", paso, semilla)
                    .isEqualByComparingTo(esperado);
        }
        excedidas = jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? "
                + "and excedio_limite = true", Integer.class, cliente);
        assertThat(movimientos(cliente)).as("hubo ventas aceptadas").isGreaterThan(5);
        assertThat(excedidas).as("hubo ventas con exceso autorizado (semilla %d)", semilla).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? "
                + "and excedio_limite = false and debe > 0", Integer.class, cliente)).isGreaterThan(0);
    }
}
