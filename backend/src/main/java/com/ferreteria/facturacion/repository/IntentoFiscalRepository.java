package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.EstadoIntento;
import com.ferreteria.facturacion.model.IntentoFiscal;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los intentos ({@code FECAESolicitar}), siempre de la ferretería del request. Los escribe solo el motor: el alta guarda la
 * entidad ABIERTA antes de llamar, y el resultado y la verificación son UPDATE condicionales sobre el estado de antes.
 * Nada se borra.
 */
public interface IntentoFiscalRepository extends TenantScopedRepository<IntentoFiscal, Long> {

    /** El intento abierto de la serie (ABIERTO, EN_VERIFICACION o ALERTA): hay a lo sumo uno, por el UNIQUE funcional. */
    @Query(value = """
            select * from intento_fiscal
            where ferreteria_id = :fid and serie_fiscal_id = :serie and estado in ('ABIERTO', 'EN_VERIFICACION', 'ALERTA')
            """, nativeQuery = true)
    Optional<IntentoFiscal> abiertoDeSerie(@Param("fid") Long ferreteriaId, @Param("serie") long serieId);

    default Optional<IntentoFiscal> abiertoDeSerieScoped(long serieId) {
        return abiertoDeSerie(TenantContext.requireFerreteriaId(), serieId);
    }

    /**
     * ABIERTO → {@code hacia}, con la respuesta tal cual y la hora en que terminó la llamada; {@code cerradoEn} va solo si
     * {@code hacia} es un estado cerrado (AUTORIZADO, RECHAZADO o NO_LLEGO).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update intento_fiscal set estado = :hacia, resultado = :resultado, respuesta = :respuesta,
              terminado_en = :terminadoEn, cerrado_en = :cerradoEn
            where ferreteria_id = :fid and id = :id and estado = 'ABIERTO'
            """, nativeQuery = true)
    int terminar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("hacia") String hacia,
            @Param("resultado") String resultado, @Param("respuesta") String respuesta,
            @Param("terminadoEn") Instant terminadoEn, @Param("cerradoEn") Instant cerradoEn);

    default int terminarScoped(long id, EstadoIntento hacia, String resultado, String respuesta, Instant terminadoEn,
            Instant cerradoEn) {
        return terminar(TenantContext.requireFerreteriaId(), id, hacia.name(), resultado, respuesta, terminadoEn,
                cerradoEn);
    }

    /**
     * ABIERTO → RECHAZADO (R), con la respuesta tal cual y lo que se consultó después para entenderla (el 10016, C6:
     * FECompUltimoAutorizado), cerrado en la misma hora.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update intento_fiscal set estado = 'RECHAZADO', resultado = 'R', respuesta = :respuesta,
              respuesta_verificacion = :verificacion, terminado_en = :ahora, cerrado_en = :ahora
            where ferreteria_id = :fid and id = :id and estado = 'ABIERTO'
            """, nativeQuery = true)
    int rechazarConVerificacion(@Param("fid") Long ferreteriaId, @Param("id") long id,
            @Param("respuesta") String respuesta, @Param("verificacion") String verificacion,
            @Param("ahora") Instant ahora);

    default int rechazarConVerificacionScoped(long id, String respuesta, String verificacion, Instant ahora) {
        return rechazarConVerificacion(TenantContext.requireFerreteriaId(), id, respuesta, verificacion, ahora);
    }

    /**
     * ABIERTO → ALERTA con la respuesta tal cual y lo que se consultó después para entenderla: un 10016 con ARCA atrás de
     * números que la serie ya tiene (C6). Sin resultado y sin cerrar, como toda alerta: traba la serie.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update intento_fiscal set estado = 'ALERTA', respuesta = :respuesta, respuesta_verificacion = :verificacion,
              terminado_en = :ahora
            where ferreteria_id = :fid and id = :id and estado = 'ABIERTO'
            """, nativeQuery = true)
    int alertarConVerificacion(@Param("fid") Long ferreteriaId, @Param("id") long id,
            @Param("respuesta") String respuesta, @Param("verificacion") String verificacion,
            @Param("ahora") Instant ahora);

    default int alertarConVerificacionScoped(long id, String respuesta, String verificacion, Instant ahora) {
        return alertarConVerificacion(TenantContext.requireFerreteriaId(), id, respuesta, verificacion, ahora);
    }

    /**
     * EN_VERIFICACION → {@code hacia} (AUTORIZADO, NO_LLEGO o ALERTA), con lo que respondió la verificación;
     * {@code cerradoEn} va solo si {@code hacia} es un estado cerrado.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update intento_fiscal set estado = :hacia, resultado = :resultado, respuesta_verificacion = :verificacion,
              cerrado_en = :cerradoEn
            where ferreteria_id = :fid and id = :id and estado = 'EN_VERIFICACION'
            """, nativeQuery = true)
    int resolver(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("hacia") String hacia,
            @Param("resultado") String resultado, @Param("verificacion") String verificacion,
            @Param("cerradoEn") Instant cerradoEn);

    default int resolverScoped(long id, EstadoIntento hacia, String resultado, String verificacion, Instant cerradoEn) {
        return resolver(TenantContext.requireFerreteriaId(), id, hacia.name(), resultado, verificacion, cerradoEn);
    }
}
