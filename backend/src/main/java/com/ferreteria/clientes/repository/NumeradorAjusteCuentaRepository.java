package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.NumeradorAjusteCuenta;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * La numeración de los ajustes de cuenta corriente (GDS-20). Copia del patrón de {@code NumeradorReciboRepository}: el UPSERT
 * toma el lock de la fila hasta el fin de la transacción, así que serializa los ajustes de una misma ferretería (y solo ellos).
 * Se llama DESPUÉS del lock del cliente (S6): cliente → numerador → partidas.
 */
public interface NumeradorAjusteCuentaRepository extends TenantScopedRepository<NumeradorAjusteCuenta, Long> {

    /** Crea la fila de la ferretería en 1 o le suma 1 (UPSERT). Nativo por el ON DUPLICATE KEY. */
    @Modifying
    @Query(value = "insert into numerador_ajuste_cuenta (ferreteria_id, ultimo_numero) values (:fid, 1) "
            + "on duplicate key update ultimo_numero = ultimo_numero + 1", nativeQuery = true)
    int incrementar(@Param("fid") Long ferreteriaId);

    /** Lee el valor que dejó el incremento de esta misma transacción (InnoDB muestra los cambios propios). */
    @Query(value = "select ultimo_numero from numerador_ajuste_cuenta where ferreteria_id = :fid", nativeQuery = true)
    long ultimo(@Param("fid") Long ferreteriaId);

    /**
     * El próximo número de ajuste de la ferretería del request: 1, 2, 3... Correlativo y sin repetidos. Exige una transacción
     * abierta; si se revierte, el número no se consume.
     */
    default long siguienteScoped() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        incrementar(ferreteriaId);
        return ultimo(ferreteriaId);
    }
}
