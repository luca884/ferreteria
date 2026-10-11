package com.ferreteria.tenant;

import java.util.Optional;

/**
 * Guarda el id de la ferreteria (tenant) del request actual en un ThreadLocal.
 * Lo llena {@link TenantFilter} a partir del JWT y lo limpia siempre al terminar el request.
 * Mismo patron que Sendo (TenantContext con studioId).
 */
public final class TenantContext {

    private static final ThreadLocal<Long> currentFerreteriaId = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentFerreteriaId(Long ferreteriaId) {
        currentFerreteriaId.set(ferreteriaId);
    }

    /**
     * Fail-closed: devuelve el id de la ferreteria actual o lanza {@link MissingTenantException}.
     * Es el accessor por defecto para cualquier codigo que trabaje con datos de una ferreteria.
     */
    public static Long requireFerreteriaId() {
        Long id = currentFerreteriaId.get();
        if (id == null) {
            throw new MissingTenantException();
        }
        return id;
    }

    /** Opt-out explicito para los pocos casos donde "sin tenant" es normal (login, super admin). */
    public static Optional<Long> currentFerreteriaId() {
        return Optional.ofNullable(currentFerreteriaId.get());
    }

    public static void clear() {
        currentFerreteriaId.remove();
    }
}
