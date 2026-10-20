package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.NumeradorRecibo;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * La numeración de los recibos de cobranza (CC-036). Copia exacta del patrón de {@link NumeradorComprobanteRepository}: no
 * se unifica con los otros numeradores (YAGNI; deuda anotada). El UPSERT toma el lock de la fila hasta el fin de la
 * transacción, así que serializa las cobranzas de una misma ferretería (y solo ellas: la numeración de ventas es otra tabla).
 */
public interface NumeradorReciboRepository extends TenantScopedRepository<NumeradorRecibo, Long> {

    /** Crea la fila de la ferretería en 1 o le suma 1 (UPSERT). Nativo por el ON DUPLICATE KEY. */
    @Modifying
    @Query(value = "insert into numerador_recibo (ferreteria_id, ultimo_numero) values (:fid, 1) "
            + "on duplicate key update ultimo_numero = ultimo_numero + 1", nativeQuery = true)
    int incrementar(@Param("fid") Long ferreteriaId);

    /** Lee el valor que dejó el incremento de esta misma transacción (InnoDB muestra los cambios propios). */
    @Query(value = "select ultimo_numero from numerador_recibo where ferreteria_id = :fid", nativeQuery = true)
    long ultimo(@Param("fid") Long ferreteriaId);

    /**
     * El próximo número de recibo de la ferretería del request: 1, 2, 3... Correlativo y sin repetidos. Exige una
     * transacción abierta; si se revierte, el número no se consume.
     */
    default long siguienteScoped() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        incrementar(ferreteriaId);
        return ultimo(ferreteriaId);
    }
}
