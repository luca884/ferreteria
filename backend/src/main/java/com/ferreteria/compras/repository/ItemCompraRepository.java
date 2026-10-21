package com.ferreteria.compras.repository;

import com.ferreteria.compras.model.ItemCompra;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ItemCompraRepository extends TenantScopedRepository<ItemCompra, Long> {

    @Query("select i from ItemCompra i where i.ferreteriaId = :fid and i.compraId = :compraId order by i.renglon")
    List<ItemCompra> deCompra(@Param("fid") Long ferreteriaId, @Param("compraId") Long compraId);

    /** Los renglones de la compra, en el orden en que se cargaron. */
    default List<ItemCompra> deCompraScoped(Long compraId) {
        return deCompra(TenantContext.requireFerreteriaId(), compraId);
    }

    @Query("select i.compraId, count(i) from ItemCompra i where i.ferreteriaId = :fid and i.compraId in :ids "
            + "group by i.compraId")
    List<Object[]> contar(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    /** Cuantos renglones tiene cada compra (para el listado). */
    default Map<Long, Long> renglonesPorCompraScoped(Collection<Long> compraIds) {
        Map<Long, Long> cantidades = new HashMap<>();
        if (compraIds.isEmpty()) {
            return cantidades;
        }
        for (Object[] fila : contar(TenantContext.requireFerreteriaId(), compraIds)) {
            cantidades.put((Long) fila[0], (Long) fila[1]);
        }
        return cantidades;
    }
}
