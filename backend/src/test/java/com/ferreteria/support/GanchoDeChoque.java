package com.ferreteria.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/**
 * El gancho de choque del bloqueo optimista del articulo (lote 5b, design: estrategia de tests). Sobre un
 * {@code @SpyBean} de {@link HistorialPrecioRepository}, en el {@code save} o el {@code saveAll} (despues de mutar los
 * articulos y antes del flush) ejecuta algo concurrente en OTRO hilo (un {@code update} por JDBC o un pedido HTTP real)
 * y espera a que termine; despues sigue con el guardado real. No sirve con el PUT de ajustes, cuyo lock lo trabaria.
 *
 * <p>El espia de un repositorio de Spring Data no puede llamar al metodo real (es abstracto en la interfaz): se llama a
 * una instancia real armada con el EntityManager compartido, que se une a la transaccion del pedido que se prueba.
 */
public final class GanchoDeChoque {

    /**
     * Lo que se ejecuta en el otro hilo. Tiene que ser JDBC (o un pedido que no necesite una fila que la transaccion
     * probada ya tenga bloqueada): si pide un lock que el pedido en curso retiene, el gancho espera y falla a los 10 s.
     */
    @FunctionalInterface
    public interface Accion {
        void run() throws Exception;
    }

    private final HistorialPrecioRepository espia;
    private final EntityManager entityManager;
    private HistorialPrecioRepository real;
    private final AtomicBoolean ejecutando = new AtomicBoolean();

    public GanchoDeChoque(HistorialPrecioRepository espia, EntityManager entityManager) {
        this.espia = espia;
        this.entityManager = entityManager;
    }

    /** En el primer {@code saveAll} de historial. */
    public void enSaveAll(Accion concurrente) {
        AtomicBoolean yaCorrio = new AtomicBoolean();
        doAnswer(invocacion -> {
            if (yaCorrio.compareAndSet(false, true)) {
                disparar(concurrente);
            }
            return llamarAlReal(invocacion);
        }).when(espia).saveAll(any());
    }

    /** En el primer {@code save} de historial (el PUT, el alta). */
    public void enSave(Accion concurrente) {
        AtomicBoolean yaCorrio = new AtomicBoolean();
        doAnswer(invocacion -> {
            if (yaCorrio.compareAndSet(false, true)) {
                disparar(concurrente);
            }
            return llamarAlReal(invocacion);
        }).when(espia).save(any());
    }

    /** En CADA {@code saveAll}: choca siempre (cada intento del recalculo). */
    public void siempreEnSaveAll(Accion concurrente) {
        doAnswer(invocacion -> {
            disparar(concurrente);
            return llamarAlReal(invocacion);
        }).when(espia).saveAll(any());
    }

    /** Guarda contra la reentrada: si la propia accion guarda historial por el espia, no se dispara otra vez. */
    private void disparar(Accion accion) {
        if (!ejecutando.compareAndSet(false, true)) {
            return;
        }
        try {
            correrAparte(accion);
        } finally {
            ejecutando.set(false);
        }
    }

    /** Saca el gancho: el espia vuelve a pasar todo al repositorio real. */
    public void soltar() {
        reset(espia);
    }

    private Object llamarAlReal(InvocationOnMock invocacion) throws Throwable {
        if (real == null) {
            real = new JpaRepositoryFactory(entityManager).getRepository(HistorialPrecioRepository.class);
        }
        try {
            return invocacion.getMethod().invoke(real, invocacion.getArguments());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void correrAparte(Accion accion) {
        CompletableFuture<Void> otroHilo = CompletableFuture.runAsync(() -> {
            try {
                accion.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            otroHilo.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("el gancho quedó bloqueado por un lock", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
