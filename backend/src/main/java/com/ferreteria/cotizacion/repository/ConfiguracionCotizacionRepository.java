package com.ferreteria.cotizacion.repository;

import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/** Configuracion de cotizacion de cada ferreteria: una fila por ferreteria, siempre leida con su scope. */
public interface ConfiguracionCotizacionRepository extends TenantScopedRepository<ConfiguracionCotizacion, Long> {

    Optional<ConfiguracionCotizacion> findByFerreteriaId(Long ferreteriaId);

    /** La configuracion de la ferreteria del request; vacio si todavia no se creo (se crea al pedirla). */
    default Optional<ConfiguracionCotizacion> findPropiaScoped() {
        return findByFerreteriaId(TenantContext.requireFerreteriaId());
    }

    /**
     * Reclama el recalculo del dia para la ferreteria del request: marca {@code hoy} como ultimo dia recalculado SOLO si
     * la cotizacion esta activada, ya paso la hora configurada y hoy todavia no se recalculo. Devuelve 1 si lo reclamo
     * y 0 si no le tocaba. Al ser un UPDATE condicional es idempotente aunque corran varias instancias, y se revierte
     * con la transaccion del recalculo. Limpia el contexto de persistencia: las entidades cargadas antes quedan viejas.
     */
    default int reclamarRecalculoDelDiaScoped(LocalDateTime ahoraBuenosAires) {
        return reclamarRecalculoDelDia(TenantContext.requireFerreteriaId(), ahoraBuenosAires.toLocalDate(),
                ahoraBuenosAires.toLocalTime());
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConfiguracionCotizacion c set c.ultimoRecalculoDia = :hoy "
            + "where c.ferreteriaId = :fid and c.activa = true and c.horaRecalculo <= :hora "
            + "and (c.ultimoRecalculoDia is null or c.ultimoRecalculoDia < :hoy)")
    int reclamarRecalculoDelDia(@Param("fid") Long ferreteriaId, @Param("hoy") LocalDate hoy,
                                @Param("hora") LocalTime hora);
}
