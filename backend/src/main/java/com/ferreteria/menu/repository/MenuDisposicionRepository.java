package com.ferreteria.menu.repository;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.menu.model.MenuDisposicion;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;

import java.util.Optional;

public interface MenuDisposicionRepository extends TenantScopedRepository<MenuDisposicion, Long> {

    Optional<MenuDisposicion> findByFerreteriaIdAndRol(Long ferreteriaId, Rol rol);

    Optional<MenuDisposicion> findByFerreteriaIdAndUsuarioId(Long ferreteriaId, Long usuarioId);

    void deleteByFerreteriaIdAndRol(Long ferreteriaId, Rol rol);

    void deleteByFerreteriaIdAndUsuarioId(Long ferreteriaId, Long usuarioId);

    default Optional<MenuDisposicion> deRolScoped(Rol rol) {
        return findByFerreteriaIdAndRol(TenantContext.requireFerreteriaId(), rol);
    }

    default Optional<MenuDisposicion> deUsuarioScoped(Long usuarioId) {
        return findByFerreteriaIdAndUsuarioId(TenantContext.requireFerreteriaId(), usuarioId);
    }

    default void borrarRolScoped(Rol rol) {
        deleteByFerreteriaIdAndRol(TenantContext.requireFerreteriaId(), rol);
    }

    default void borrarUsuarioScoped(Long usuarioId) {
        deleteByFerreteriaIdAndUsuarioId(TenantContext.requireFerreteriaId(), usuarioId);
    }
}
