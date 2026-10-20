package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CC-001, CC-002, CC-008, CC-010: los repositorios de la cuenta corriente contra MySQL real. El bloqueo lee escalares con
 * una consulta nativa (nunca la entidad) y el saldo se escribe relativo; el libro se suma por cliente.
 */
class ClienteRepositoryCuentaTest extends CuentaCorrienteTestBase {

    @Autowired
    ClienteRepository clientes;

    @Autowired
    MovimientoCuentaClienteRepository libro;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T enTx(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(transactionManager).execute(status -> trabajo.get());
    }

    @Test
    @DisplayName("bloquearCuentaScoped devuelve el saldo, el límite y si está activo del cliente propio")
    void bloquearCuentaDelPropio() {
        Ferreteria f = ferreteria();
        long id = cliente(f, "1000.00");
        jdbc.update("update cliente set saldo = 250.50 where id = ?", id);
        TenantContext.setCurrentFerreteriaId(f.id());

        var cuenta = enTx(() -> clientes.bloquearCuentaScoped(id)).orElseThrow();

        assertThat(cuenta.getRazonSocial())
                .isEqualTo(jdbc.queryForObject("select razon_social from cliente where id = ?", String.class, id));
        assertThat(cuenta.getSaldo()).isEqualByComparingTo("250.50");
        assertThat(cuenta.getLimiteCuentaCorriente()).isEqualByComparingTo("1000.00");
        assertThat(cuenta.getActivo()).isTrue();
    }

    @Test
    @DisplayName("bloquearCuentaScoped con un cliente sin cuenta corriente habilitada: límite null; y desactivado: activo false")
    void bloquearCuentaSinLimiteYDesactivado() {
        Ferreteria f = ferreteria();
        long id = cliente(f, null);
        jdbc.update("update cliente set activo = false where id = ?", id);
        TenantContext.setCurrentFerreteriaId(f.id());

        var cuenta = enTx(() -> clientes.bloquearCuentaScoped(id)).orElseThrow();

        assertThat(cuenta.getLimiteCuentaCorriente()).isNull();
        assertThat(cuenta.getActivo()).isFalse();
        assertThat(cuenta.getSaldo()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("bloquearCuentaScoped con un cliente de otra ferretería no devuelve nada (aislamiento)")
    void bloquearCuentaDelAjeno() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long deB = cliente(b, "500.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThat(enTx(() -> clientes.bloquearCuentaScoped(deB))).isEmpty();
        assertThat(enTx(() -> clientes.bloquearCuentaScoped(-1L))).isEmpty();
    }

    @Test
    @DisplayName("sumarSaldoScoped suma relativo al saldo vigente (no lo reemplaza) y acepta deltas negativos")
    void sumarSaldoEsRelativo() {
        Ferreteria f = ferreteria();
        long id = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        int primera = enTx(() -> clientes.sumarSaldoScoped(id, new BigDecimal("100.00")));
        jdbc.update("update cliente set saldo = saldo + 40 where id = ?", id); // alguien más lo cambió entre medio
        int segunda = enTx(() -> clientes.sumarSaldoScoped(id, new BigDecimal("50.25")));
        enTx(() -> clientes.sumarSaldoScoped(id, new BigDecimal("-10.00")));

        assertThat(primera).isEqualTo(1);
        assertThat(segunda).isEqualTo(1);
        assertThat(saldo(id)).isEqualByComparingTo("180.25");
    }

    @Test
    @DisplayName("sumarSaldoScoped no toca a otro cliente ni a un cliente de otra ferretería")
    void sumarSaldoNoTocaAOtros() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long propio = cliente(a, "1000.00");
        long vecino = cliente(a, "1000.00");
        long deB = cliente(b, "1000.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        int sobreElPropio = enTx(() -> clientes.sumarSaldoScoped(propio, new BigDecimal("70.00")));
        int sobreElAjeno = enTx(() -> clientes.sumarSaldoScoped(deB, new BigDecimal("70.00")));

        assertThat(sobreElPropio).isEqualTo(1);
        assertThat(sobreElAjeno).isZero();
        assertThat(saldo(propio)).isEqualByComparingTo("70.00");
        assertThat(saldo(vecino)).isEqualByComparingTo("0");
        assertThat(saldo(deB)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("saldoDelLibroScoped: libro vacío = 0; con renglones es Σ(debe − haber) del cliente y de su ferretería")
    void saldoDelLibro() {
        Ferreteria f = ferreteria();
        long id = cliente(f, "1000.00");
        long otro = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());
        assertThat(enTx(() -> libro.saldoDelLibroScoped(id))).isEqualByComparingTo("0");

        asiento(f, id, "VENTA", "300.00", "0");
        asiento(f, id, "VENTA", "200.00", "0");
        asiento(f, id, "COBRO", "0", "120.00");
        asiento(f, otro, "VENTA", "999.00", "0");

        assertThat(enTx(() -> libro.saldoDelLibroScoped(id))).isEqualByComparingTo("380.00");
        Ferreteria ajena = ferreteria();
        TenantContext.setCurrentFerreteriaId(ajena.id());
        assertThat(enTx(() -> libro.saldoDelLibroScoped(id))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("deClienteScoped y deComprobanteScoped devuelven solo los renglones propios, en orden de id")
    void consultasDelLibro() {
        Ferreteria f = ferreteria();
        long id = cliente(f, "1000.00");
        VentaSuelta v = asiento(f, id, "VENTA", "300.00", "0");
        asiento(f, id, "VENTA", "100.00", "0");
        TenantContext.setCurrentFerreteriaId(f.id());

        var delCliente = enTx(() -> libro.deClienteScoped(id, org.springframework.data.domain.PageRequest.of(0, 10)));
        var delComprobante = enTx(() -> libro.deComprobanteScoped(v.comprobanteId()));

        assertThat(delCliente.getContent()).extracting(m -> m.getDebe().toPlainString()).containsExactly("300.00", "100.00");
        assertThat(delComprobante).hasSize(1);
        assertThat(delComprobante.get(0).getPagoId()).isEqualTo(v.pagoId());
        TenantContext.setCurrentFerreteriaId(ferreteria().id());
        assertThat(enTx(() -> libro.deClienteScoped(id, org.springframework.data.domain.PageRequest.of(0, 10))).getContent())
                .isEmpty();
        assertThat(enTx(() -> libro.deComprobanteScoped(v.comprobanteId()))).isEmpty();
    }

    @Test
    @DisplayName("guardar un Cliente modificado NO pisa un saldo cambiado por otra transacción (@DynamicUpdate + saldo de solo lectura)")
    void actualizarNoPisaElSaldo() {
        Ferreteria f = ferreteria();
        long id = cliente(f, "1000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        new TransactionTemplate(transactionManager).execute(status -> {
            Cliente cargado = clientes.findScoped(id).orElseThrow(); // foto con saldo 0
            jdbc.update("update cliente set saldo = 500.00 where id = ?", id); // otra transacción lo cambia y confirma
            cargado.actualizar("Nuevo nombre", null, null, cargado.getCondicionIva(), null, null, null, null);
            cargado.cambiarActivo(false);
            clientes.saveAndFlush(cargado);
            return null;
        });

        assertThat(saldo(id)).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("select razon_social from cliente where id = ?", String.class, id))
                .isEqualTo("Nuevo nombre");
    }

    @Test
    @DisplayName("cambiarLimite persiste el límite en la columna, null lo deshabilita, y el alta nace con saldo 0")
    void limiteSePersiste() {
        Ferreteria f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f.id());
        Cliente nuevo = Cliente.de(f.id(), "Alta " + System.nanoTime(), null, null,
                com.ferreteria.clientes.model.CondicionIvaCliente.CONSUMIDOR_FINAL, null, null, null, null);
        nuevo.cambiarLimite(new BigDecimal("750.00"));
        Cliente guardado = new TransactionTemplate(transactionManager).execute(s -> clientes.saveAndFlush(nuevo));
        long id = guardado.getId();

        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("750.00");
        assertThat(saldo(id)).isEqualByComparingTo("0");

        new TransactionTemplate(transactionManager).execute(s -> {
            clientes.findScoped(id).orElseThrow().cambiarLimite(null);
            return null;
        });
        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", BigDecimal.class, id))
                .isNull();
    }

    private VentaSuelta asiento(Ferreteria f, long clienteId, String tipo, String debe, String haber) {
        VentaSuelta v = ventaSuelta(f, clienteId, "1.00");
        if (tipo.equals("COBRO")) {
            // un COBRO es de un recibo y no lleva comprobante ni pago (ck_mcc_recibo_ref): se siembra el recibo real
            jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, "
                    + "usuario_id, recibo_id, referencia) values (?, ?, 'COBRO', ?, ?, ?, ?, ?, 'Recibo N.º test')", f.id(),
                    clienteId, new BigDecimal(debe), new BigDecimal(haber), LocalDateTime.now(), f.usuarioId(),
                    reciboSuelto(f, clienteId, haber));
            return v;
        }
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                + "pago_id, fecha, usuario_id, referencia, motivo) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", f.id(),
                clienteId, tipo, new BigDecimal(debe), new BigDecimal(haber), v.comprobanteId(), v.pagoId(),
                LocalDateTime.now(), f.usuarioId(), "Venta N.º " + v.numero(),
                // ck_mcc_motivo: solo AJUSTE y ANULACION llevan motivo (obligatorio)
                tipo.equals("AJUSTE") || tipo.equals("ANULACION") ? "Saldo inicial de test" : null);
        return v;
    }
}
