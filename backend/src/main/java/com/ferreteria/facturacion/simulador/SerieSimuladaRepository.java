package com.ferreteria.facturacion.simulador;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Las series del simulador de WSFEv1, siempre de la ferretería del request. */
public interface SerieSimuladaRepository extends TenantScopedRepository<SerieSimulada, Long> {

    @Query("select s from SerieSimulada s where s.ferreteriaId = :fid and s.cuit = :cuit and s.puntoVenta = :ptoVta "
            + "and s.cbteTipo = :cbteTipo")
    Optional<SerieSimulada> buscar(@Param("fid") Long ferreteriaId, @Param("cuit") long cuit, @Param("ptoVta") int ptoVta,
            @Param("cbteTipo") int cbteTipo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SerieSimulada s where s.ferreteriaId = :fid and s.cuit = :cuit and s.puntoVenta = :ptoVta "
            + "and s.cbteTipo = :cbteTipo")
    Optional<SerieSimulada> bloquear(@Param("fid") Long ferreteriaId, @Param("cuit") long cuit, @Param("ptoVta") int ptoVta,
            @Param("cbteTipo") int cbteTipo);

    /** Crea la serie en 0 si no existe (UPSERT, sin cambiar una que ya está). Nativo por el ON DUPLICATE KEY. */
    @Modifying
    @Query(value = "insert into simulador_wsfe_serie (ferreteria_id, cuit, punto_venta, cbte_tipo) "
            + "values (:fid, :cuit, :ptoVta, :cbteTipo) on duplicate key update ultimo_nro = ultimo_nro", nativeQuery = true)
    int asegurar(@Param("fid") Long ferreteriaId, @Param("cuit") long cuit, @Param("ptoVta") int ptoVta,
            @Param("cbteTipo") int cbteTipo);

    /** La serie de la ferretería del request, si ya se usó. */
    default Optional<SerieSimulada> buscarScoped(long cuit, int ptoVta, int cbteTipo) {
        return buscar(TenantContext.requireFerreteriaId(), cuit, ptoVta, cbteTipo);
    }

    /**
     * La serie de la ferretería del request, tomada con lock hasta el fin de la transacción (la crea en 0 si no existe):
     * serializa las autorizaciones de una misma serie. Exige una transacción abierta.
     */
    default SerieSimulada bloquearScoped(long cuit, int ptoVta, int cbteTipo) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        asegurar(ferreteriaId, cuit, ptoVta, cbteTipo);
        return bloquear(ferreteriaId, cuit, ptoVta, cbteTipo).orElseThrow();
    }
}
