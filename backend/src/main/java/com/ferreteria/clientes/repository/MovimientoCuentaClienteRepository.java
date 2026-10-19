package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El libro de cuenta corriente (CC-001). Append-only: se agrega con {@code save}, y NADIE llama a {@code delete*} sobre este
 * repositorio (lo vigila ModulosArchTest).
 */
public interface MovimientoCuentaClienteRepository extends TenantScopedRepository<MovimientoCuentaCliente, Long> {

    @Query(value = "select m from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "order by m.id", countQuery = "select count(m) from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId")
    Page<MovimientoCuentaCliente> deCliente(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            Pageable pageable);

    /** Σ(debe − haber) del cliente; 0 si el libro está vacío. Lo usan los tests de la invariante y la cobranza futura. */
    @Query("select coalesce(sum(m.debe - m.haber), 0) from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId")
    BigDecimal saldoDelLibro(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    @Query("select m from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.comprobanteId = :comprobanteId "
            + "order by m.id")
    List<MovimientoCuentaCliente> deComprobante(@Param("fid") Long ferreteriaId,
            @Param("comprobanteId") Long comprobanteId);

    /**
     * El débito de un comprobante en la cuenta (CC-031): Σ debe de sus filas {@code VENTA} (una venta mixta puede traer más
     * de un pago a cuenta corriente), el id de la primera (orden FIFO), y la referencia y fecha copiadas.
     */
    interface DebitoDeComprobante {
        Long getComprobanteId();

        Long getPrimerMovimientoId();

        String getReferencia();

        LocalDateTime getFecha();

        BigDecimal getDebe();
    }

    /**
     * Los débitos {@code VENTA} del cliente agrupados por comprobante, del más viejo al más nuevo (por el id de la primera
     * fila). Lectura plana: es monótona (el libro solo agrega), una foto vieja solo SUBestima la deuda, nunca sobre-imputa.
     */
    @Query("select m.comprobanteId as comprobanteId, min(m.id) as primerMovimientoId, min(m.referencia) as referencia, "
            + "min(m.fecha) as fecha, sum(m.debe) as debe from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.VENTA "
            + "group by m.comprobanteId order by min(m.id)")
    List<DebitoDeComprobante> debitosDeCliente(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /** Lo mismo, solo de esos comprobantes: uno que no tenga débito en la cuenta de ESE cliente simplemente no aparece. */
    @Query("select m.comprobanteId as comprobanteId, min(m.id) as primerMovimientoId, min(m.referencia) as referencia, "
            + "min(m.fecha) as fecha, sum(m.debe) as debe from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId and m.comprobanteId in :comprobanteIds "
            + "and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.VENTA "
            + "group by m.comprobanteId order by min(m.id)")
    List<DebitoDeComprobante> debitosDeComprobantes(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("comprobanteIds") List<Long> comprobanteIds);

    /** Los ids de las filas {@code VENTA} de esos comprobantes en la cuenta del cliente, ascendentes (las partidas a bloquear). */
    @Query("select m.id from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "and m.comprobanteId in :comprobanteIds "
            + "and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.VENTA order by m.id")
    List<Long> idsDePartidas(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("comprobanteIds") List<Long> comprobanteIds);

    /**
     * Bloquea las partidas con {@code FOR UPDATE} por CLAVE PRIMARIA exacta y en orden de id ascendente. Es nativa a propósito:
     * una búsqueda por rango sobre un índice secundario no único ({@code ix_mcc_comprobante}, {@code ix_mcc_cliente}) toma
     * next-key y gap locks en REPEATABLE READ y frenaría las altas de ventas a cuenta de OTROS clientes. El mutex real de la
     * cuenta es la fila del cliente; esto fija además el orden de los locks (id ascendente: sin deadlock entre cobranzas).
     * Requiere transacción. {@code ids} no puede ser vacío.
     */
    @Query(value = "select id from movimiento_cuenta_cliente where id in (:ids) and ferreteria_id = :fid "
            + "and cliente_id = :clienteId order by id for update", nativeQuery = true)
    List<Long> bloquearPartidas(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("ids") List<Long> ids);

    /** Σ haber de los {@code COBRO} del cliente: con las imputaciones, el «a cuenta» (crédito sin imputar). 0 si no hay. */
    @Query("select coalesce(sum(m.haber), 0) from MovimientoCuentaCliente m where m.ferreteriaId = :fid "
            + "and m.clienteId = :clienteId and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.COBRO")
    BigDecimal creditosDeCobro(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    default List<DebitoDeComprobante> debitosDeClienteScoped(Long clienteId) {
        return debitosDeCliente(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<DebitoDeComprobante> debitosDeComprobantesScoped(Long clienteId, List<Long> comprobanteIds) {
        return debitosDeComprobantes(TenantContext.requireFerreteriaId(), clienteId, comprobanteIds);
    }

    default List<Long> idsDePartidasScoped(Long clienteId, List<Long> comprobanteIds) {
        return idsDePartidas(TenantContext.requireFerreteriaId(), clienteId, comprobanteIds);
    }

    default List<Long> bloquearPartidasScoped(Long clienteId, List<Long> ids) {
        return bloquearPartidas(TenantContext.requireFerreteriaId(), clienteId, ids);
    }

    default BigDecimal creditosDeCobroScoped(Long clienteId) {
        return creditosDeCobro(TenantContext.requireFerreteriaId(), clienteId);
    }

    default Page<MovimientoCuentaCliente> deClienteScoped(Long clienteId, Pageable pageable) {
        return deCliente(TenantContext.requireFerreteriaId(), clienteId, pageable);
    }

    default BigDecimal saldoDelLibroScoped(Long clienteId) {
        return saldoDelLibro(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<MovimientoCuentaCliente> deComprobanteScoped(Long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }
}
