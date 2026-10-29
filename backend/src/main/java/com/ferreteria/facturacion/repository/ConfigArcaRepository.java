package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.model.RegistrosFiscales;
import com.ferreteria.tenant.TenantContext;
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
 * tenant. El panel del super admin no tiene ferretería en el token: sus consultas reciben la ferretería explícita y solo se
 * llaman desde métodos o clases con {@code @UnscopedTenantAccess} (regla de {@code TenantScopingArchTest}). El alta de un
 * registro fiscal lee la suya con {@link #bloquearCompartidaScoped}.
 *
 * <p>El lock: los cambios del panel toman la fila con {@link #crearSiFalta} y {@link #bloquearDeFerreteria} (lock exclusivo)
 * antes de decidir. Para coordinarse con el panel, el alta de un registro fiscal lee esta misma fila con {@code FOR SHARE}
 * ({@link #bloquearCompartidaScoped}) y la sostiene hasta el commit: mientras tanto el ambiente no cambia, y un cambio del
 * panel que espera ese lock ve el registro al tomarlo.
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

    /** La fila de la ferretería con lock compartido ({@code FOR SHARE}): lee lo último confirmado. */
    @Query(value = "select * from config_arca where ferreteria_id = :fid for share", nativeQuery = true)
    Optional<ConfigArca> bloquearCompartida(@Param("fid") Long ferreteriaId);

    /**
     * La configuración de la ferretería del request, con lock compartido hasta el fin de la transacción: lo que lee el alta
     * de un registro fiscal. Mientras tanto el panel no cambia el ambiente (espera el lock), y cuando lo toma ve el registro.
     * Exige una transacción abierta.
     */
    default Optional<ConfigArca> bloquearCompartidaScoped() {
        return bloquearCompartida(TenantContext.requireFerreteriaId());
    }

    /**
     * La configuración de la ferretería del request, sin lock: lo que anticipa el cobro (cu-07-factura-mostrador C3), que
     * no decide nada. Lo que decide la venta la lee con {@link #bloquearCompartidaScoped}.
     */
    default Optional<ConfigArca> deFerreteriaScoped() {
        return deFerreteria(TenantContext.requireFerreteriaId());
    }

    /**
     * La configuración de la ferretería del request con lock exclusivo hasta el fin de la transacción, creándola si falta:
     * el mismo protocolo que el panel ({@link #crearSiFalta} y {@link #bloquearDeFerreteria}), para lo que cambia el Dueño
     * (el punto de venta, el pedido de certificado, el certificado que fija el ambiente; rama 5). Tiene que ser lo primero
     * de la transacción, antes de cualquier lectura sin lock (ver {@code ConfigArcaParaPlataformaImpl.bloquear}).
     */
    default Optional<ConfigArca> bloquearScoped() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        crearSiFalta(ferreteriaId);
        return bloquearDeFerreteria(ferreteriaId);
    }

    /** Los registros fiscales de la ferretería del request que traban el ambiente: abiertos, y de homologación o producción. */
    default RegistrosFiscales registrosScoped() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        return new RegistrosFiscales(registrosAbiertos(ferreteriaId) > 0,
                registrosDeHomologacionOProduccion(ferreteriaId) > 0);
    }

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
