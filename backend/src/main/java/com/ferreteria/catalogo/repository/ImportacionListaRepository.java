package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Las importaciones se registran al aplicar (save) y se leen con los metodos Scoped: nunca las de otra ferreteria. */
public interface ImportacionListaRepository extends TenantScopedRepository<ImportacionLista, Long> {

    @Query("""
            select i from ImportacionLista i
            where i.ferreteriaId = :fid and (:proveedorId is null or i.proveedorId = :proveedorId)
            order by i.fecha desc, i.id desc
            """)
    List<ImportacionLista> listar(@Param("fid") Long ferreteriaId, @Param("proveedorId") Long proveedorId);

    /**
     * Las importaciones de la ferreteria, de la mas reciente a la mas vieja (fecha y, con la misma fecha, id), sin
     * paginar. Con {@code proveedorIdONull} solo las de ese proveedor; null no filtra.
     */
    default List<ImportacionLista> listarScoped(Long proveedorIdONull) {
        return listar(TenantContext.requireFerreteriaId(), proveedorIdONull);
    }
}
