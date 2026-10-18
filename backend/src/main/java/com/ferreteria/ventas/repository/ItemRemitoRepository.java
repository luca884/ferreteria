package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.ItemRemito;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemRemitoRepository extends TenantScopedRepository<ItemRemito, Long> {

    @Query("select i from ItemRemito i where i.ferreteriaId = :fid and i.remitoId = :remitoId order by i.renglon")
    List<ItemRemito> deRemito(@Param("fid") Long ferreteriaId, @Param("remitoId") Long remitoId);

    /** Los renglones del remito, en el orden del ticket. */
    default List<ItemRemito> deRemitoScoped(Long remitoId) {
        return deRemito(TenantContext.requireFerreteriaId(), remitoId);
    }
}
