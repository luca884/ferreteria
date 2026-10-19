package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CB-8 (CCT3-210, CC-048, CC-040): regresión de snapshot a nivel PUERTO, con REPEATABLE READ por defecto. La tx B hace un
 * SELECT plano (= la FOTO queda fijada); otro hilo confirma una imputación que agota el pendiente de A; B toma al cliente y
 * llama a {@code asentarCobro} por A: DEBE rechazarse porque el imputado se lee con {@code FOR UPDATE} (lectura actual). Con
 * una lectura plana del imputado, B vería la foto vieja (nada imputado) y sobre-imputaría. Hermano del test (e) de tramo 2,
 * que se mantiene. CUIT 30-8401NNNN.
 */
class CuentaCorrienteSnapshotCobranzaIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    ClienteRepository clientes;

    @Override
    protected String prefijoDeCuit() {
        return "30-8401";
    }

    private <T> T enOtroHilo(Ferreteria f, Callable<T> trabajo) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> {
                TenantContext.setCurrentFerreteriaId(f.id());
                try {
                    return trabajo.call();
                } finally {
                    TenantContext.clear();
                }
            }).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** La foto de B: un SELECT plano sobre imputacion fija el snapshot de REPEATABLE READ con «nada imputado». */
    private long fijarLaFoto(long cliente) {
        return jdbc.queryForObject("select count(*) from imputacion where cliente_id = ?", Long.class, cliente);
    }

    @Test
    @DisplayName("CB-8: con la foto fijada ANTES de que otro hilo agote A, el cobro por A se rechaza (imputado leído con FOR UPDATE)")
    void laFotoViejaNoDejaSobreImputar() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(fijarLaFoto(cliente)).isZero(); // foto con 0 imputaciones
            try {
                enOtroHilo(f, () -> cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(fijarLaFoto(cliente)).as("la foto de B sigue vieja: el test es válido").isZero();

            assertThatThrownBy(() -> asentarEnLaTx(f, cliente, caja, "50.00", imputa(a.comprobanteId(), "50.00")))
                    .isInstanceOfSatisfying(ImputacionInvalidaException.class, e -> {
                        assertThat(e.deudas()).singleElement().satisfies(deuda -> {
                            assertThat(deuda.comprobanteId()).isEqualTo(a.comprobanteId());
                            assertThat(deuda.pendiente()).as("ve lo confirmado por el otro hilo").isEqualByComparingTo("0");
                        });
                    });
            status.setRollbackOnly(); // el rechazo deja la tx sin uso: lo que escribió (el recibo) se revierte
            return null;
        });

        assertThat(imputadoA(a.comprobanteId())).as("A quedó imputado una sola vez").isEqualByComparingTo("100.00");
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CB-8 variante: con otro comprobante B distinto, la misma foto vieja SÍ deja imputar (el rechazo es por A, no por la foto)")
    void otroComprobanteSiEntra() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(fijarLaFoto(cliente)).isZero();
            try {
                enOtroHilo(f, () -> cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            asentarEnLaTx(f, cliente, caja, "100.00", imputa(b.comprobanteId(), "100.00"));
            return null;
        });

        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("100.00");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("100.00");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CB-8 variante con la entidad Cliente ya cargada en el EntityManager: el rechazo no depende de la instancia gestionada")
    void conLaEntidadYaCargada() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        new TransactionTemplate(transactionManager).execute(status -> {
            Cliente cargado = clientes.findScoped(cliente).orElseThrow(); // instancia gestionada con saldo 100 (y foto fijada)
            try {
                enOtroHilo(f, () -> cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            assertThatThrownBy(() -> asentarEnLaTx(f, cliente, caja, "50.00", imputa(a.comprobanteId(), "50.00")))
                    .isInstanceOf(ImputacionInvalidaException.class);
            assertThat(cargado.getSaldo()).as("la instancia gestionada está desactualizada").isEqualByComparingTo("100.00");
            status.setRollbackOnly();
            return null;
        });

        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("100.00");
        assertThat(saldo(cliente)).isEqualByComparingTo(BigDecimal.ZERO);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }
}
