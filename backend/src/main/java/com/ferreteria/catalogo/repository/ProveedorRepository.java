package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface ProveedorRepository extends TenantScopedRepository<Proveedor, Long> {

    List<Proveedor> findAllByFerreteriaIdAndActivoTrueOrderByNombreAsc(Long ferreteriaId);

    List<Proveedor> findAllByFerreteriaIdOrderByNombreAsc(Long ferreteriaId);

    Optional<Proveedor> findFirstByFerreteriaIdAndNombreIgnoreCase(Long ferreteriaId, String nombre);

    boolean existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(Long ferreteriaId, String nombre, Long id);

    /** Ordenados por nombre: solo los activos o, con incluirInactivos, tambien los dados de baja. */
    default List<Proveedor> listarScoped(boolean incluirInactivos) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        return incluirInactivos
                ? findAllByFerreteriaIdOrderByNombreAsc(ferreteriaId)
                : findAllByFerreteriaIdAndActivoTrueOrderByNombreAsc(ferreteriaId);
    }

    /** El proveedor con ese nombre (sin distinguir mayusculas ni tildes), activo o dado de baja. */
    default Optional<Proveedor> buscarPorNombreScoped(String nombre) {
        return findFirstByFerreteriaIdAndNombreIgnoreCase(TenantContext.requireFerreteriaId(), nombre);
    }

    /** Para editar: busca otro proveedor con ese nombre, excluyendo el propio. */
    default boolean existeOtroConNombreScoped(String nombre, Long idPropio) {
        return existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(TenantContext.requireFerreteriaId(), nombre, idPropio);
    }
}
