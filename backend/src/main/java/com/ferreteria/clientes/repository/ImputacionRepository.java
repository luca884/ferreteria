package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las imputaciones (CC-026): append-only, se agregan con {@code save} desde {@code CuentaCorrienteService} y NADIE llama a
 * {@code delete*} sobre este repositorio (lo vigila ModulosArchTest). La única mutación posterior es la vigencia
 * ({@link #desactivarDeRecibo}, CC-063/CC-102). TODA consulta que reste del pendiente filtra {@code vigente = true}: la
 * lista de las que no lo hacen es explícita ({@code ConsultasDeImputacionFiltranVigenciaTest}).
 */
public interface ImputacionRepository extends TenantScopedRepository<Imputacion, Long> {

    /** Lo imputado a un comprobante (Σ de sus imputaciones). */
    interface ImputadoDeComprobante {
        Long getComprobanteId();

        BigDecimal getImputado();
    }

    /** Una fila cruda de imputación: el comprobante y el importe (sin agregar, ver {@link #imputadoBloqueado}). */
    interface FilaImputada {
        Long getComprobanteId();

        BigDecimal getImporte();
    }

    /**
     * Lo imputado por comprobante a todo el cliente. Lectura PLANA (con la foto de la transacción): sirve para el GET de
     * pendientes, que es solo un consejo. La cobranza NO decide con esto: lee con {@link #imputadoBloqueado}.
     */
    @Query("select i.comprobanteId as comprobanteId, sum(i.importe) as imputado from Imputacion i "
            + "where i.ferreteriaId = :fid and i.clienteId = :clienteId and i.vigente = true group by i.comprobanteId")
    List<ImputadoDeComprobante> imputadoPorComprobante(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /**
     * Las imputaciones de esos comprobantes, con {@code FOR UPDATE}: una lectura ACTUAL (lo último confirmado), inmune a la
     * foto de REPEATABLE READ. Un imputado viejo SOBRE-estima el pendiente y dejaría sobre-imputar, así que es la única
     * lectura crítica de la cobranza. Filas crudas, sin agregado (MySQL no admite agregados bajo FOR UPDATE de forma
     * confiable): se suman en el dominio. Se limita a los comprobantes imputados para no tomar locks de más. Requiere
     * transacción; {@code comprobanteIds} no puede ser vacío.
     */
    @Query(value = "select comprobante_id as comprobanteId, importe as importe from imputacion "
            + "where ferreteria_id = :fid and cliente_id = :clienteId and comprobante_id in (:comprobanteIds) and vigente = true for update",
            nativeQuery = true)
    List<FilaImputada> imputadoBloqueado(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("comprobanteIds") List<Long> comprobanteIds);

    /**
     * Las imputaciones de un recibo, en el orden en que se asentaron. SIN filtro de vigencia a propósito (CC-063): la
     * reimpresión de un recibo anulado y la cobranza repetida necesitan las imputaciones ORIGINALES, también las no vigentes.
     * Es de las tres consultas de este repositorio que no filtran ({@code ConsultasDeImputacionFiltranVigenciaTest}).
     */
    @Query("select i from Imputacion i where i.ferreteriaId = :fid and i.reciboId = :reciboId order by i.id")
    List<Imputacion> deRecibo(@Param("fid") Long ferreteriaId, @Param("reciboId") Long reciboId);

    /**
     * Una fila de imputación tomada por la anulación: su id, su comprobante y si sigue vigente (lectura actual, bajo
     * {@code FOR UPDATE}).
     */
    interface FilaDeImputacion {
        Long getId();

        Long getComprobanteId();

        boolean getVigente();
    }

    /**
     * Los ids de todas las imputaciones del recibo, ascendentes, también las no vigentes (sin filtro, como
     * {@link #deRecibo}): es lo que la anulación bloquea y da de baja. Lectura plana, tomada DESPUÉS del lock del cliente.
     */
    @Query("select i.id from Imputacion i where i.ferreteriaId = :fid and i.reciboId = :reciboId order by i.id")
    List<Long> idsDeRecibo(@Param("fid") Long ferreteriaId, @Param("reciboId") Long reciboId);

    /**
     * Bloquea esas imputaciones con {@code FOR UPDATE} por CLAVE PRIMARIA exacta y en orden de id ascendente, y devuelve su
     * vigencia ACTUAL (lectura actual, inmune a la foto de REPEATABLE READ). Es nativa y por id (no por
     * {@code uk_imputacion_recibo_comprobante}) a propósito: un rango sobre un índice secundario tomaría next-key y gap locks y
     * frenaría las imputaciones de recibos nuevos de OTROS clientes; el mutex real es la fila del cliente. {@code force index
     * (primary)} es indispensable: con el índice por defecto el optimizador, apenas la tabla crece, elige
     * {@code ix_imputacion_cliente} (ref sobre ferretería y cliente, con filesort) y toma next-key locks sobre ese índice
     * secundario (se vio con EXPLAIN en la suite completa y rompía {@code sinGapLocks}). No filtra vigencia: justamente
     * informa cuáles ya no lo son. Requiere transacción; {@code ids} no puede ser vacío.
     */
    @Query(value = "select id as id, comprobante_id as comprobanteId, vigente as vigente from imputacion force index (primary) "
            + "where id in (:ids) and ferreteria_id = :fid "
            + "and cliente_id = :clienteId order by id for update", nativeQuery = true)
    List<FilaDeImputacion> bloquearDeRecibo(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("ids") List<Long> ids);

    /**
     * La ÚNICA mutación permitida de una imputación (CC-102): la baja por anulación del recibo. Devuelve las filas que
     * pasaron de vigente a no vigente (0 si ya no estaban vigentes: idempotente). Solo la llama {@code CuentaCorrienteService}
     * (lo vigila ModulosArchTest) y exige que los ids ya estén bloqueados con {@link #bloquearDeRecibo}. {@code flushAutomatically}
     * vuelca antes lo pendiente de la transacción; {@code clearAutomatically} queda apagado a propósito: limpiaría el contexto y
     * perdería cambios sin volcar.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update imputacion force index (primary) set vigente = false where ferreteria_id = :fid and cliente_id = :clienteId "
            + "and recibo_id = :reciboId and id in (:ids) and vigente = true", nativeQuery = true)
    int desactivarDeRecibo(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("reciboId") Long reciboId, @Param("ids") List<Long> ids);

    /** Σ de todas las imputaciones del cliente (con los COBRO, el «a cuenta»); 0 si no hay. */
    @Query("select coalesce(sum(i.importe), 0) from Imputacion i where i.ferreteriaId = :fid and i.clienteId = :clienteId and i.vigente = true")
    BigDecimal totalImputado(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    default List<ImputadoDeComprobante> imputadoPorComprobanteScoped(Long clienteId) {
        return imputadoPorComprobante(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<FilaImputada> imputadoBloqueadoScoped(Long clienteId, List<Long> comprobanteIds) {
        return imputadoBloqueado(TenantContext.requireFerreteriaId(), clienteId, comprobanteIds);
    }

    default List<Imputacion> deReciboScoped(Long reciboId) {
        return deRecibo(TenantContext.requireFerreteriaId(), reciboId);
    }

    default BigDecimal totalImputadoScoped(Long clienteId) {
        return totalImputado(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<Long> idsDeReciboScoped(Long reciboId) {
        return idsDeRecibo(TenantContext.requireFerreteriaId(), reciboId);
    }

    default List<FilaDeImputacion> bloquearDeReciboScoped(Long clienteId, List<Long> ids) {
        return bloquearDeRecibo(TenantContext.requireFerreteriaId(), clienteId, ids);
    }

    default int desactivarDeReciboScoped(Long clienteId, Long reciboId, List<Long> ids) {
        return desactivarDeRecibo(TenantContext.requireFerreteriaId(), clienteId, reciboId, ids);
    }
}
