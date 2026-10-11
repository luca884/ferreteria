package com.ferreteria.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Base para repositorios de entidades que pertenecen a una ferreteria (columna ferreteria_id).
 * Los metodos *Scoped agregan SIEMPRE el filtro por la ferreteria del request, resuelta con
 * {@link TenantContext#requireFerreteriaId()}. Los finders "pelados" de JpaRepository
 * (findById, findAll, etc.) estan prohibidos por TenantScopingArchTest.
 */
@NoRepositoryBean
public interface TenantScopedRepository<T, ID> extends JpaRepository<T, ID> {

    @Query("select e from #{#entityName} e where e.id = :id and e.ferreteriaId = :fid")
    Optional<T> findByIdAndFerreteriaId(@Param("id") ID id, @Param("fid") Long ferreteriaId);

    @Query("select e from #{#entityName} e where e.ferreteriaId = :fid")
    List<T> findAllByFerreteriaId(@Param("fid") Long ferreteriaId);

    default Optional<T> findScoped(ID id) {
        return findByIdAndFerreteriaId(id, TenantContext.requireFerreteriaId());
    }

    default List<T> findAllScoped() {
        return findAllByFerreteriaId(TenantContext.requireFerreteriaId());
    }
}
