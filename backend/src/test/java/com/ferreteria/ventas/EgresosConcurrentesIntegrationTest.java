package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.TipoMovimientoCaja;
import com.ferreteria.ventas.repository.MovimientoCajaRepository;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.EgresoSinEfectivoException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * GDS-36: dos egresos a la vez no pueden pasar los dos el control del efectivo. Determinista contra MySQL real: se PAUSA el
 * primer gasto dentro de su transacción, justo al leer los movimientos para el esperado ({@code @SpyBean} sobre
 * {@link MovimientoCajaRepository#totalesScoped}), se lanza el segundo y se espera a que quede en LOCK WAIT sobre la caja;
 * recién ahí se suelta el primero. Con la caja tomada en modo compartido el segundo no espera, lee el mismo esperado y los
 * dos pasan (la caja queda en negativo): el test falla siempre. CUIT 30-8701NNNN-1.
 */
class EgresosConcurrentesIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CajaService cajaService;

    /** Pausa al primer egreso dentro de su transacción, ya con la caja tomada. */
    @SpyBean
    MovimientoCajaRepository movimientosEspiados;

    private final AtomicReference<Thread> hiloDelPrimero = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8701";
    }

    @AfterEach
    void soltarElEspia() {
        Mockito.reset(movimientosEspiados);
        hiloDelPrimero.set(null);
    }

    private record Salida(Object valor, Throwable error) {}

    private Future<Salida> lanzar(ExecutorService pool, Ferreteria f, Callable<?> trabajo) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(f.id());
            try {
                return new Salida(trabajo.call(), null);
            } catch (Throwable t) {
                return new Salida(null, t);
            } finally {
                TenantContext.clear();
            }
        });
    }

    @Test
    @DisplayName("dos gastos de $ 80 con $ 100 en la caja: el segundo espera al primero, ve $ 20 y recibe el aviso; lock_deadlocks 0")
    void dosGastosALaVez() throws Exception {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        AuthUser dueno = new AuthUser(f.usuarioId(), "dueno@test.local", Rol.DUENO, f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        try {
            cajaService.registrarMovimiento(caja, TipoMovimientoCaja.INGRESO, new BigDecimal("100"), "Cambio", false, dueno);
        } finally {
            TenantContext.clear();
        }

        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot(), Duration.ofSeconds(10));
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        doAnswer(invocacion -> {
            if (Thread.currentThread() == hiloDelPrimero.get()) {
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        }).when(movimientosEspiados).totalesScoped(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Salida primero;
        Salida segundo;
        try {
            Future<Salida> futuroPrimero = lanzar(pool, f, () -> {
                hiloDelPrimero.set(Thread.currentThread());
                return cajaService.registrarMovimiento(caja, TipoMovimientoCaja.GASTO, new BigDecimal("80"), "Proveedor",
                        false, dueno);
            });
            llego.esperar(); // el primero tiene la caja tomada y no confirmó
            Future<Salida> futuroSegundo = lanzar(pool, f, () -> cajaService.registrarMovimiento(caja,
                    TipoMovimientoCaja.GASTO, new BigDecimal("80"), "Cadete", false, dueno));
            barrera.esperarBloqueados(1);
            assertThat(barrera.objetosEnEspera()).as("el segundo espera la CAJA").containsExactly("caja");
            suelta.avisar();
            primero = futuroPrimero.get(60, TimeUnit.SECONDS);
            segundo = futuroSegundo.get(60, TimeUnit.SECONDS);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        barrera.assertSinDeadlocks(deadlocksAntes);
        assertThat(primero.error()).as("el primero tiene que entrar y fue %s", primero.error()).isNull();
        assertThat(segundo.error()).isInstanceOf(EgresoSinEfectivoException.class);
        EgresoSinEfectivoException rechazo = (EgresoSinEfectivoException) segundo.error();
        assertThat(rechazo.disponible()).isEqualByComparingTo("20");
        assertThat(rechazo.faltante()).isEqualByComparingTo("60");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_caja where caja_id = ? and tipo = 'GASTO'",
                Long.class, caja)).isEqualTo(1);
    }
}
