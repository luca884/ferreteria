package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.Comprobante;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ComprobanteRepository extends TenantScopedRepository<Comprobante, Long> {

    @Query("select c from Comprobante c where c.ferreteriaId = :fid and c.claveIdempotencia = :clave")
    Optional<Comprobante> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** La venta de la ferreteria del request con esa clave de idempotencia; la de otra ferreteria no cuenta. */
    default Optional<Comprobante> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }
}
