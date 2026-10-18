package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.model.Remito;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RemitoRepository extends TenantScopedRepository<Remito, Long> {

    @Query("select r from Remito r where r.ferreteriaId = :fid and r.claveIdempotencia = :clave")
    Optional<Remito> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** El remito de la ferreteria del request con esa clave de idempotencia; el de otra ferreteria no cuenta. */
    default Optional<Remito> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }

    /** Con lock exclusivo: lo toma quien anula o factura, para ordenarse entre si. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Remito r where r.id = :id and r.ferreteriaId = :fid")
    Optional<Remito> paraCambiar(@Param("id") Long id, @Param("fid") Long ferreteriaId);

    /** El remito de la ferreteria del request con lock exclusivo. El de otra ferreteria no aparece. */
    default Optional<Remito> paraCambiarScoped(Long id) {
        return paraCambiar(id, TenantContext.requireFerreteriaId());
    }

    /**
     * Los de la ferreteria, mas nuevos primero. Cada filtro nulo no filtra. {@code desde} incluye ese instante y
     * {@code hasta} no (es el comienzo del dia siguiente al ultimo que se quiere ver).
     */
    @Query("select r from Remito r where r.ferreteriaId = :fid and (:estado is null or r.estado = :estado) "
            + "and (:numero is null or r.numero = :numero) and (:desde is null or r.fecha >= :desde) "
            + "and (:hasta is null or r.fecha < :hasta) order by r.numero desc")
    Page<Remito> buscar(
            @Param("fid") Long ferreteriaId, @Param("estado") EstadoRemito estado, @Param("numero") Long numero,
            @Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta, Pageable pageable);

    default Page<Remito> buscarScoped(
            EstadoRemito estado, Long numero, LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), estado, numero, desde, hasta, pageable);
    }
}
