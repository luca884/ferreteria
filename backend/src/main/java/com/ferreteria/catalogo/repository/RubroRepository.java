package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;

import java.util.List;

public interface RubroRepository extends TenantScopedRepository<Rubro, Long> {

    List<Rubro> findAllByFerreteriaIdOrderByNombreAsc(Long ferreteriaId);

    boolean existsByFerreteriaIdAndNombreIgnoreCase(Long ferreteriaId, String nombre);

    boolean existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(Long ferreteriaId, String nombre, Long id);

    default List<Rubro> listarScoped() {
        return findAllByFerreteriaIdOrderByNombreAsc(TenantContext.requireFerreteriaId());
    }

    default boolean existeNombreScoped(String nombre) {
        return existsByFerreteriaIdAndNombreIgnoreCase(TenantContext.requireFerreteriaId(), nombre);
    }

    /** Para renombrar: busca otro rubro con ese nombre, excluyendo el propio. */
    default boolean existeOtroConNombreScoped(String nombre, Long idPropio) {
        return existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(TenantContext.requireFerreteriaId(), nombre, idPropio);
    }
}
