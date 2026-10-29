package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.credencial.CredencialArca.Estado;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Las credenciales de ARCA de la ferretería del request (a lo sumo una PENDIENTE y una ACTIVA). Repositorio de tenant: todo
 * se usa por los métodos {@code *Scoped}, con la ferretería de {@code TenantContext}.
 *
 * <p>Quien cambia algo (un pedido nuevo, un certificado activado) tiene tomado antes el lock exclusivo de la fila de
 * {@code config_arca} de la ferretería: dos cambios a la vez se serializan. La venta lee la activa con
 * {@link #activaCompartidaScoped()} después de su {@code FOR SHARE} de {@code config_arca}, en el mismo orden.
 */
public interface CredencialArcaRepository extends TenantScopedRepository<CredencialArca, Long> {

    @Query("select c from CredencialArca c where c.ferreteriaId = :fid and c.estado = :estado")
    Optional<CredencialArca> deFerreteriaEnEstado(@Param("fid") Long ferreteriaId, @Param("estado") Estado estado);

    /** La activa con lock compartido ({@code FOR SHARE}): lee lo último confirmado. */
    @Query(value = "select * from credencial_arca where ferreteria_id = :fid and estado = 'ACTIVA' for share",
            nativeQuery = true)
    Optional<CredencialArca> activaCompartida(@Param("fid") Long ferreteriaId);

    /**
     * Borra la de ese estado, con su clave. Primero baja a la base lo pendiente de la transacción (el ambiente de
     * {@code config_arca}, por ejemplo); no limpia el contexto de persistencia, así quien llama sigue con sus entidades.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from CredencialArca c where c.ferreteriaId = :fid and c.estado = :estado")
    int borrarEnEstado(@Param("fid") Long ferreteriaId, @Param("estado") Estado estado);

    default Optional<CredencialArca> activaScoped() {
        return deFerreteriaEnEstado(TenantContext.requireFerreteriaId(), Estado.ACTIVA);
    }

    default Optional<CredencialArca> pendienteScoped() {
        return deFerreteriaEnEstado(TenantContext.requireFerreteriaId(), Estado.PENDIENTE);
    }

    /** La activa con lock compartido hasta el fin de la transacción; exige una abierta. */
    default Optional<CredencialArca> activaCompartidaScoped() {
        return activaCompartida(TenantContext.requireFerreteriaId());
    }

    /** Borra el pedido pendiente, con su clave (un pedido nuevo lo reemplaza). */
    default void borrarPendienteScoped() {
        borrarEnEstado(TenantContext.requireFerreteriaId(), Estado.PENDIENTE);
    }

    /** Borra la credencial activa, con su clave (un certificado nuevo la reemplaza, S15). */
    default void borrarActivaScoped() {
        borrarEnEstado(TenantContext.requireFerreteriaId(), Estado.ACTIVA);
    }
}
