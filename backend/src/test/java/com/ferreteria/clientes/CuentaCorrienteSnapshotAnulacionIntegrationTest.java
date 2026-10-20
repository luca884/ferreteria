package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoAnulacion;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AN-8 (CCT3B1-208: CC-091, CC-063, CC-085): regresión de snapshot a nivel PUERTO, con REPEATABLE READ por defecto. La tx B
 * hace un SELECT plano (= la FOTO queda fijada); otro hilo anula el recibo R y confirma; B toma al cliente y llama a
 * {@code asentarAnulacion(R)}: DEBE rechazarse. Con imputaciones, porque {@code bloquearDeRecibo} es una lectura ACTUAL y ve
 * {@code vigente = 0} (con una lectura plana B vería la foto vieja); sin imputaciones, porque el INSERT de la segunda
 * ANULACION choca con {@code uk_mcc_recibo_tipo}. En ambos queda UNA sola ANULACION y el saldo sube T una vez. Si alguien
 * moviera una lectura plana antes del lock del cliente, la anulación concurrente no duplicaría el contra-asiento aunque la
 * respuesta cambie. Hermano de {@code CuentaCorrienteSnapshotCobranzaIntegrationTest}. CUIT 30-8502NNNN.
 */
class CuentaCorrienteSnapshotAnulacionIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    @Override
    protected String prefijoDeCuit() {
        return "30-8502";
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

    /** La foto de B: un SELECT plano sobre imputacion fija el snapshot de REPEATABLE READ. */
    private long fijarLaFoto(long cliente) {
        return jdbc.queryForObject("select count(*) from imputacion where cliente_id = ?", Long.class, cliente);
    }

    private ReciboEmitido cobrarConRecibo(Ferreteria f, long cliente, long caja, String total, com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar... imputaciones) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            ReciboEmitido recibo = emitirRecibo(f, cliente, caja, total);
            cobranza.asentarCobro(new AsientoCobro(cliente, recibo.id(), recibo.numero(), new BigDecimal(total),
                    List.of(imputaciones), f.usuarioId()));
            return recibo;
        });
    }

    /** Lo que hace RegistroAnulacion en la tx: toma al cliente, marca el recibo ANULADO (SQL) y asienta la anulación. */
    private void anularEnSuTx(Ferreteria f, long cliente, ReciboEmitido recibo, String total, long cajaAnulacion) {
        new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            em.createNativeQuery("update recibo set estado = 'ANULADO', fecha_anulacion = now(6), usuario_anulacion_id = ?1, "
                    + "caja_anulacion_id = ?2, motivo_anulacion = ?3 where id = ?4")
                    .setParameter(1, f.usuarioId()).setParameter(2, cajaAnulacion).setParameter(3, MOTIVO)
                    .setParameter(4, recibo.id()).executeUpdate();
            return cobranza.asentarAnulacion(
                    new AsientoAnulacion(cliente, recibo.id(), recibo.numero(), new BigDecimal(total), MOTIVO, f.usuarioId()));
        });
    }

    private long anulaciones(long cliente) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, cliente);
    }

    @Test
    @DisplayName("AN-8: con la foto fijada ANTES de que otro hilo anule R, asentarAnulacion(R) se rechaza porque lee la vigencia con FOR UPDATE (una sola ANULACION, saldo +T)")
    void laFotoViejaNoDejaAnularDosVeces() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00"));

        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(fijarLaFoto(cliente)).isEqualTo(1L); // foto con la imputación vigente
            try {
                enOtroHilo(f, () -> {
                    anularEnSuTx(f, cliente, recibo, "100.00", caja);
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(jdbc.queryForObject("select vigente from imputacion where recibo_id = ?", Boolean.class, recibo.id()))
                    .as("la foto de B sigue vieja: ve la imputación vigente (el test es válido)").isTrue();

            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            assertThatThrownBy(() -> cobranza.asentarAnulacion(
                    new AsientoAnulacion(cliente, recibo.id(), recibo.numero(), new BigDecimal("100.00"), MOTIVO, f.usuarioId())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ya no son vigentes"); // lo detectó la lectura ACTUAL de la vigencia (FOR UPDATE)
            status.setRollbackOnly();
            return null;
        });

        assertThat(anulaciones(cliente)).as("una sola ANULACION").isEqualTo(1);
        assertThat(saldo(cliente)).as("+T una sola vez").isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select vigente from imputacion where recibo_id = ?", Boolean.class, recibo.id())).isFalse();
        assertThat(jdbc.queryForObject("select estado from recibo where id = ?", String.class, recibo.id())).isEqualTo("ANULADO");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("AN-8 variante sin imputaciones (adelanto): B pasa las lecturas planas con la foto vieja pero el INSERT choca con uk_mcc_recibo_tipo → DataIntegrityViolationException; una sola ANULACION")
    void sinImputacionesLoRespaldaLaUnicidad() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "500.00");

        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(fijarLaFoto(cliente)).isZero();
            try {
                enOtroHilo(f, () -> {
                    anularEnSuTx(f, cliente, recibo, "500.00", caja);
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            assertThatThrownBy(() -> cobranza.asentarAnulacion(
                    new AsientoAnulacion(cliente, recibo.id(), recibo.numero(), new BigDecimal("500.00"), MOTIVO, f.usuarioId())))
                    .isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
            return null;
        });

        assertThat(anulaciones(cliente)).as("una sola ANULACION").isEqualTo(1);
        assertThat(saldo(cliente)).as("−500 + 500: el adelanto se anuló una sola vez").isEqualByComparingTo("0");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }
}
