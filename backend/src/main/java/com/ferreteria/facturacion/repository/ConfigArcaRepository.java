package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * La configuración de ARCA de cada ferretería (una fila por ferretería, si el super admin ya la tocó). Repositorio de
 * tenant. Hoy solo la usa el panel del super admin, que no tiene ferretería en el token: sus consultas reciben la
 * ferretería explícita y se llaman únicamente desde métodos con {@code @UnscopedTenantAccess}
 * ({@code ConfigArcaParaPlataformaImpl}). La venta (rama 3) va a leer la suya con un método {@code *Scoped}.
 */
public interface ConfigArcaRepository extends TenantScopedRepository<ConfigArca, Long> {

    @Query("select c from ConfigArca c where c.ferreteriaId = :fid")
    Optional<ConfigArca> deFerreteria(@Param("fid") Long ferreteriaId);

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
