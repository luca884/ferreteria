package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.ItemPresupuesto;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemPresupuestoRepository extends TenantScopedRepository<ItemPresupuesto, Long> {

    @Query("select i from ItemPresupuesto i where i.ferreteriaId = :fid and i.presupuestoId = :presupuestoId "
            + "order by i.renglon")
    List<ItemPresupuesto> dePresupuesto(@Param("fid") Long ferreteriaId, @Param("presupuestoId") Long presupuestoId);

    /** Los renglones del presupuesto, en el orden del ticket. */
    default List<ItemPresupuesto> dePresupuestoScoped(Long presupuestoId) {
        return dePresupuesto(TenantContext.requireFerreteriaId(), presupuestoId);
    }
}
