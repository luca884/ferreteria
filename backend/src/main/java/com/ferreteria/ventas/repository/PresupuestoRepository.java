package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.Presupuesto;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PresupuestoRepository extends TenantScopedRepository<Presupuesto, Long> {

    @Query("select p from Presupuesto p where p.ferreteriaId = :fid and p.claveIdempotencia = :clave")
    Optional<Presupuesto> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** El presupuesto de la ferreteria del request con esa clave de idempotencia; el de otra ferreteria no cuenta. */
    default Optional<Presupuesto> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }
}
