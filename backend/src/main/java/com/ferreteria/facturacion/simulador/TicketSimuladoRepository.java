package com.ferreteria.facturacion.simulador;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * El TA que emitió el WSAA del simulador en el borde, uno por servicio de la ferretería del request (rama 6, S6). Repositorio
 * de tenant: se usa por los métodos {@code *Scoped}, con la ferretería de {@code TenantContext}.
 */
public interface TicketSimuladoRepository extends TenantScopedRepository<TicketSimulado, Long> {

    @Query("select t from TicketSimulado t where t.ferreteriaId = :fid and t.servicio = :servicio")
    Optional<TicketSimulado> deClave(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio);

    /** La fila con lock exclusivo ({@code FOR UPDATE}): dos {@code loginCms} de la misma ferretería se ordenan. */
    @Query(value = "select * from ticket_simulado where ferreteria_id = :fid and servicio = :servicio for update",
            nativeQuery = true)
    Optional<TicketSimulado> bloquearDeClave(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio);

    /** Guarda el TA nuevo, que reemplaza al anterior (INSERT con ON DUPLICATE KEY, como la serie del simulador). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into ticket_simulado (ferreteria_id, servicio, huella, generado, vence)
            values (:fid, :servicio, :huella, :generado, :vence)
            on duplicate key update huella = :huella, generado = :generado, vence = :vence
            """, nativeQuery = true)
    int emitir(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio, @Param("huella") String huella,
            @Param("generado") Instant generado, @Param("vence") Instant vence);

    /** El último TA emitido para ese servicio de la ferretería del request, si hay. */
    default Optional<TicketSimulado> deClaveScoped(String servicio) {
        return deClave(TenantContext.requireFerreteriaId(), servicio);
    }

    /** Lo mismo, con la fila tomada hasta el fin de la transacción. Exige una transacción abierta. */
    default Optional<TicketSimulado> bloquearScoped(String servicio) {
        return bloquearDeClave(TenantContext.requireFerreteriaId(), servicio);
    }

    /** Guarda el TA nuevo de ese servicio para la ferretería del request. Exige una transacción abierta. */
    default void emitirScoped(String servicio, String huella, Instant generado, Instant vence) {
        emitir(TenantContext.requireFerreteriaId(), servicio, huella, generado, vence);
    }
}
