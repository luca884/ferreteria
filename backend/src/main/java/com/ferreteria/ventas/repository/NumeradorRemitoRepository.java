package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.NumeradorRemito;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NumeradorRemitoRepository extends TenantScopedRepository<NumeradorRemito, Long> {

    /**
     * Crea la fila de la ferreteria en 1 o le suma 1 (UPSERT). Toma el lock de la fila hasta el fin de la
     * transaccion: eso serializa los remitos de una misma ferreteria. Nativo por el ON DUPLICATE KEY.
     */
    @Modifying
    @Query(value = "insert into numerador_remito (ferreteria_id, ultimo_numero) values (:fid, 1) "
            + "on duplicate key update ultimo_numero = ultimo_numero + 1", nativeQuery = true)
    int incrementar(@Param("fid") Long ferreteriaId);

    /** Lee el valor que dejo el incremento de esta misma transaccion (InnoDB muestra los cambios propios). */
    @Query(value = "select ultimo_numero from numerador_remito where ferreteria_id = :fid", nativeQuery = true)
    long ultimo(@Param("fid") Long ferreteriaId);

    /**
     * El proximo numero de remito de la ferreteria del request: 1, 2, 3... Correlativo y sin repetidos. Exige una
     * transaccion abierta; si se revierte, el numero no se consume.
     */
    default long siguienteScoped() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        incrementar(ferreteriaId);
        return ultimo(ferreteriaId);
    }
}
