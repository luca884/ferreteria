package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.NumeradorAjusteCuenta;
import com.ferreteria.clientes.model.TipoAjuste;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * La numeración de los ajustes de cuenta corriente (GDS-20): una por ferretería y por tipo, así los Recargos y los Descuentos
 * se numeran cada uno por su lado, como GDS (G95, V40). Copia del patrón de {@code NumeradorReciboRepository}: el UPSERT toma
 * el lock de la fila hasta el fin de la transacción, así que serializa los ajustes de un mismo tipo en una misma ferretería (y
 * solo ellos). Se llama DESPUÉS del lock del cliente (S6): cliente → numerador → partidas.
 */
public interface NumeradorAjusteCuentaRepository extends TenantScopedRepository<NumeradorAjusteCuenta, Long> {

    /** Crea la fila de la ferretería y el tipo en 1 o le suma 1 (UPSERT). Nativo por el ON DUPLICATE KEY. */
    @Modifying
    @Query(value = "insert into numerador_ajuste_cuenta (ferreteria_id, tipo, ultimo_numero) values (:fid, :tipo, 1) "
            + "on duplicate key update ultimo_numero = ultimo_numero + 1", nativeQuery = true)
    int incrementar(@Param("fid") Long ferreteriaId, @Param("tipo") String tipo);

    /** Lee el valor que dejó el incremento de esta misma transacción (InnoDB muestra los cambios propios). */
    @Query(value = "select ultimo_numero from numerador_ajuste_cuenta where ferreteria_id = :fid and tipo = :tipo",
            nativeQuery = true)
    long ultimo(@Param("fid") Long ferreteriaId, @Param("tipo") String tipo);

    /**
     * El próximo número del tipo de ajuste en la ferretería del request: 1, 2, 3... Correlativo y sin repetidos dentro del
     * tipo. Exige una transacción abierta; si se revierte, el número no se consume.
     */
    default long siguienteScoped(TipoAjuste tipo) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        incrementar(ferreteriaId, tipo.name());
        return ultimo(ferreteriaId, tipo.name());
    }
}
