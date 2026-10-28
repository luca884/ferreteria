package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.tenant.TenantScopedRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * La configuración de ARCA de cada ferretería (una fila por ferretería, si el super admin ya la tocó). Repositorio de
 * tenant. Hoy solo la usa el panel del super admin, que no tiene ferretería en el token: sus consultas reciben la
 * ferretería explícita y solo se llaman desde métodos o clases con {@code @UnscopedTenantAccess} (regla de
 * {@code TenantScopingArchTest}). La venta (rama 3) va a leer la suya con un método {@code *Scoped}.
 *
 * <p>El lock: los cambios del panel toman la fila con {@link #crearSiFalta} y {@link #bloquearDeFerreteria} (lock exclusivo)
 * antes de decidir. Para coordinarse con el panel, el alta de un registro fiscal (rama 3) tiene que leer esta misma fila
 * con {@code FOR SHARE} y sostenerla hasta el commit: mientras tanto el ambiente no cambia, y un cambio del panel que espera
 * ese lock ve el registro al tomarlo.
 */
public interface ConfigArcaRepository extends TenantScopedRepository<ConfigArca, Long> {

    @Query("select c from ConfigArca c where c.ferreteriaId = :fid")
    Optional<ConfigArca> deFerreteria(@Param("fid") Long ferreteriaId);

    /**
     * Crea la fila con los valores por defecto (sin marca, sin ambiente, deshabilitada) si la ferretería existe y todavía no
     * la tiene, y en cualquier caso deja tomado el lock exclusivo de la fila hasta el fin de la transacción. Un INSERT con
     * ON DUPLICATE KEY UPDATE y no un SELECT ... FOR UPDATE: sobre una fila que no existe, dos SELECT ... FOR UPDATE toman
     * locks de hueco compatibles y los dos INSERT siguientes chocan en un deadlock. Si la ferretería no existe no inserta
     * nada (y {@link #bloquearDeFerreteria} vuelve vacío).
     */
    @Modifying
    @Query(value = """
            insert into config_arca (ferreteria_id)
            select f.id from ferreteria f where f.id = :fid
            on duplicate key update ferreteria_id = ferreteria_id
            """, nativeQuery = true)
    int crearSiFalta(@Param("fid") Long ferreteriaId);

    /** La fila de la ferretería con lock exclusivo ({@code FOR UPDATE}): lee lo último confirmado. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConfigArca c where c.ferreteriaId = :fid")
    Optional<ConfigArca> bloquearDeFerreteria(@Param("fid") Long ferreteriaId);

    /** Las de todas las ferreterías que tienen fila, para la lista del super admin (sin N+1). */
    @Query("select c from ConfigArca c")
    List<ConfigArca> deTodasLasFerreterias();

    /**
     * Registros fiscales abiertos de la ferretería, de cualquier ambiente: los que no terminaron (todo estado salvo
     * AUTORIZADO, RECHAZADO y FUERA_DE_FECHA, incluidas las alertas). Mientras haya uno, el ambiente no cambia.
     */
    @Query(value = """
            select count(*) from comprobante_fiscal
            where ferreteria_id = :fid and estado not in ('AUTORIZADO', 'RECHAZADO', 'FUERA_DE_FECHA')
            """, nativeQuery = true)
    long registrosAbiertos(@Param("fid") Long ferreteriaId);

    /** Registros fiscales de homologación o producción de la ferretería, en cualquier estado. */
    @Query(value = """
            select count(*) from comprobante_fiscal
            where ferreteria_id = :fid and ambiente in ('HOMOLOGACION', 'PRODUCCION')
            """, nativeQuery = true)
    long registrosDeHomologacionOProduccion(@Param("fid") Long ferreteriaId);
}
