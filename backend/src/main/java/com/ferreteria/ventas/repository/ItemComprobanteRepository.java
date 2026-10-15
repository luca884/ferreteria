package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.ItemComprobante;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemComprobanteRepository extends TenantScopedRepository<ItemComprobante, Long> {

    @Query("select i from ItemComprobante i where i.ferreteriaId = :fid and i.comprobanteId = :comprobanteId "
            + "order by i.renglon")
    List<ItemComprobante> deComprobante(@Param("fid") Long ferreteriaId, @Param("comprobanteId") Long comprobanteId);

    /** Los renglones de la venta, en el orden del ticket. */
    default List<ItemComprobante> deComprobanteScoped(Long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }
}
