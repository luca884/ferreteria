package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PresupuestoRepository extends TenantScopedRepository<Presupuesto, Long> {

    @Query("select p from Presupuesto p where p.ferreteriaId = :fid and p.claveIdempotencia = :clave")
    Optional<Presupuesto> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** El presupuesto de la ferreteria del request con esa clave de idempotencia; el de otra ferreteria no cuenta. */
    default Optional<Presupuesto> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }

    /** Con lock exclusivo: lo toma quien cambia el estado, para ordenarse con la venta que lo factura. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Presupuesto p where p.id = :id and p.ferreteriaId = :fid")
    Optional<Presupuesto> paraCambiar(@Param("id") Long id, @Param("fid") Long ferreteriaId);

    /** El presupuesto de la ferreteria del request con lock exclusivo. El de otra ferreteria no aparece. */
    default Optional<Presupuesto> paraCambiarScoped(Long id) {
        return paraCambiar(id, TenantContext.requireFerreteriaId());
    }

    /**
     * Los de la ferreteria, mas nuevos primero. Cada filtro nulo no filtra. {@code desde} incluye ese instante y
     * {@code hasta} no (es el comienzo del dia siguiente al ultimo que se quiere ver).
     */
    @Query("select p from Presupuesto p where p.ferreteriaId = :fid and (:estado is null or p.estado = :estado) "
            + "and (:numero is null or p.numero = :numero) and (:desde is null or p.fecha >= :desde) "
            + "and (:hasta is null or p.fecha < :hasta) order by p.numero desc")
    Page<Presupuesto> buscar(
            @Param("fid") Long ferreteriaId, @Param("estado") EstadoPresupuesto estado, @Param("numero") Long numero,
            @Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta, Pageable pageable);

    default Page<Presupuesto> buscarScoped(
            EstadoPresupuesto estado, Long numero, LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), estado, numero, desde, hasta, pageable);
    }
}
