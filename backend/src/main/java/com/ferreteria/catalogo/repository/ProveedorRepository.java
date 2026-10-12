package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;

import java.util.List;

public interface ProveedorRepository extends TenantScopedRepository<Proveedor, Long> {

    List<Proveedor> findAllByFerreteriaIdAndActivoTrueOrderByNombreAsc(Long ferreteriaId);

    boolean existsByFerreteriaIdAndNombreIgnoreCase(Long ferreteriaId, String nombre);

    boolean existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(Long ferreteriaId, String nombre, Long id);

    /** Solo los proveedores activos, ordenados por nombre. */
    default List<Proveedor> listarScoped() {
        return findAllByFerreteriaIdAndActivoTrueOrderByNombreAsc(TenantContext.requireFerreteriaId());
    }

    default boolean existeNombreScoped(String nombre) {
        return existsByFerreteriaIdAndNombreIgnoreCase(TenantContext.requireFerreteriaId(), nombre);
    }

    /** Para editar: busca otro proveedor con ese nombre, excluyendo el propio. */
    default boolean existeOtroConNombreScoped(String nombre, Long idPropio) {
        return existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(TenantContext.requireFerreteriaId(), nombre, idPropio);
    }
}
