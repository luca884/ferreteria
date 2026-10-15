package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.Pago;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PagoRepository extends TenantScopedRepository<Pago, Long> {

    @Query("select p from Pago p where p.ferreteriaId = :fid and p.comprobanteId = :comprobanteId order by p.id")
    List<Pago> deComprobante(@Param("fid") Long ferreteriaId, @Param("comprobanteId") Long comprobanteId);

    default List<Pago> deComprobanteScoped(Long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }
}
