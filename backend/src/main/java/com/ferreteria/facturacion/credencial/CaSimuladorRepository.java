package com.ferreteria.facturacion.credencial;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** La CA del simulador de la ferretería del request (una sola). Repositorio de tenant, por {@code *Scoped}. */
public interface CaSimuladorRepository extends TenantScopedRepository<CaSimulador, Long> {

    @Query("select c from CaSimulador c where c.ferreteriaId = :fid")
    Optional<CaSimulador> deFerreteria(@Param("fid") Long ferreteriaId);

    default Optional<CaSimulador> deLaFerreteriaScoped() {
        return deFerreteria(TenantContext.requireFerreteriaId());
    }
}
