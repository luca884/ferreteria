package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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
     * El débito de una deuda en la cuenta (CC-031, GDS-20): de un comprobante, Σ debe de sus filas {@code VENTA} (una venta
     * mixta puede traer más de un pago a cuenta corriente); de un Recargo, el debe de su fila {@code AJUSTE}. Más el id de la
     * primera fila (orden FIFO) y la referencia y fecha copiadas. Exactamente uno de {@code comprobanteId} y {@code ajusteId}.
     */
    interface DebitoDeComprobante {
        Long getComprobanteId();

        Long getAjusteId();

        Long getPrimerMovimientoId();

        String getReferencia();

        LocalDateTime getFecha();

        BigDecimal getDebe();
    }

    /**
     * Los débitos del cliente, del más viejo al más nuevo (por el id de la primera fila): las {@code VENTA} agrupadas por
     * comprobante y los {@code AJUSTE} con debe (los Recargos) uno por ajuste. Lectura plana: es monótona (el libro solo
     * agrega), una foto vieja solo SUBestima la deuda, nunca sobre-imputa.
     */
    @Query("select m.comprobanteId as comprobanteId, m.ajusteId as ajusteId, min(m.id) as primerMovimientoId, "
            + "min(m.referencia) as referencia, min(m.fecha) as fecha, sum(m.debe) as debe from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "and (m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.VENTA "
            + "or (m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.AJUSTE and m.debe > 0)) "
            + "group by m.comprobanteId, m.ajusteId order by min(m.id)")
    List<DebitoDeComprobante> debitosDeCliente(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /** Lo mismo, solo de esos comprobantes: uno que no tenga débito en la cuenta de ESE cliente simplemente no aparece. */
    @Query("select m.comprobanteId as comprobanteId, m.ajusteId as ajusteId, min(m.id) as primerMovimientoId, "
            + "min(m.referencia) as referencia, min(m.fecha) as fecha, sum(m.debe) as debe from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId and m.comprobanteId in :comprobanteIds "
            + "and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.VENTA "
            + "group by m.comprobanteId, m.ajusteId order by min(m.id)")
    List<DebitoDeComprobante> debitosDeComprobantes(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("comprobanteIds") List<Long> comprobanteIds);

    /** Los ids de las filas {@code VENTA} de esos comprobantes en la cuenta del cliente, ascendentes (las partidas a bloquear). */
    @Query("select m.id from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "and m.comprobanteId in :comprobanteIds "
            + "and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.VENTA order by m.id")
    List<Long> idsDePartidas(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("comprobanteIds") List<Long> comprobanteIds);

    /** El débito de esos Recargos en la cuenta de ESE cliente: uno que no sea suyo (o no exista) simplemente no aparece. */
    @Query("select m.comprobanteId as comprobanteId, m.ajusteId as ajusteId, min(m.id) as primerMovimientoId, "
            + "min(m.referencia) as referencia, min(m.fecha) as fecha, sum(m.debe) as debe from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId and m.ajusteId in :ajusteIds "
            + "and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.AJUSTE and m.debe > 0 "
            + "group by m.comprobanteId, m.ajusteId order by min(m.id)")
    List<DebitoDeComprobante> debitosDeRecargos(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("ajusteIds") List<Long> ajusteIds);

    /** Los ids de las filas {@code AJUSTE} de esos Recargos en la cuenta del cliente, ascendentes (las partidas a bloquear). */
    @Query("select m.id from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "and m.ajusteId in :ajusteIds and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.AJUSTE "
            + "and m.debe > 0 order by m.id")
    List<Long> idsDePartidasDeRecargos(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("ajusteIds") List<Long> ajusteIds);

    /**
     * Bloquea las partidas con {@code FOR UPDATE} por CLAVE PRIMARIA exacta y en orden de id ascendente. Es nativa a propósito:
     * una búsqueda por rango sobre un índice secundario no único ({@code ix_mcc_comprobante}, {@code ix_mcc_cliente}) toma
     * next-key y gap locks en REPEATABLE READ y frenaría las altas de ventas a cuenta de OTROS clientes. El mutex real de la
     * cuenta es la fila del cliente; esto fija además el orden de los locks (id ascendente: sin deadlock entre cobranzas).
     * {@code force index (primary)} es indispensable: sin él el optimizador puede elegir {@code ix_mcc_cliente} (un rango
     * sobre ferretería y cliente, que además cubre la consulta) y tomar next-key locks sobre ese índice secundario, lo que
     * reintroduce el gap lock que esta consulta evita (se vio con EXPLAIN: {@code PlanDeLocksDeCobranzaTest}, igual que
     * {@code ImputacionRepository.bloquearDeRecibo}). Requiere transacción. {@code ids} no puede ser vacío.
     */
    @Query(value = "select id from movimiento_cuenta_cliente force index (primary) where id in (:ids) and ferreteria_id = :fid "
            + "and cliente_id = :clienteId order by id for update", nativeQuery = true)
    List<Long> bloquearPartidas(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("ids") List<Long> ids);

    /**
     * El crédito NETO de los recibos del cliente: Σ haber de los {@code COBRO} menos Σ debe de las {@code ANULACION} (un cobro
     * anulado ya no es crédito, CC-064). Con las imputaciones vigentes, es el «a cuenta» (crédito sin imputar): aCuenta =
     * creditoNeto − totalImputado. 0 si no hay.
     */
    @Query("select coalesce(sum(case when m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.COBRO then m.haber "
            + "when m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.ANULACION then -m.debe else 0 end), 0) "
            + "from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.clienteId = :clienteId")
    BigDecimal creditoNetoDeRecibos(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /** El crédito de los Descuentos del cliente (GDS-20): Σ haber de los {@code AJUSTE}. Con el de los recibos y las imputaciones, el «a cuenta». */
    @Query("select coalesce(sum(m.haber), 0) from MovimientoCuentaCliente m where m.ferreteriaId = :fid "
            + "and m.clienteId = :clienteId and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.AJUSTE")
    BigDecimal creditoDeDescuentos(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /**
     * El crédito de las notas de crédito del cliente (CU-08): Σ haber de los {@code NC}. Con el de los recibos, el de los
     * Descuentos y las imputaciones vigentes, el «a cuenta». 0 si no hay.
     */
    @Query("select coalesce(sum(m.haber), 0) from MovimientoCuentaCliente m where m.ferreteriaId = :fid "
            + "and m.clienteId = :clienteId and m.tipo = com.ferreteria.clientes.model.TipoMovimientoCuenta.NC")
    BigDecimal creditoDeNotasDeCredito(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /**
     * Una fila del libro que mueve crédito: un COBRO, su ANULACION, un Descuento (AJUSTE con haber) o una NC (CU-08, con el
     * comprobante NOTA_CREDITO en {@code comprobanteId}).
     */
    interface FilaDeCredito {
        Long getId();

        String getTipo();

        Long getReciboId();

        Long getAjusteId();

        Long getComprobanteId();

        BigDecimal getDebe();

        BigDecimal getHaber();
    }

    /**
     * Las filas del libro que mueven crédito (COBRO, ANULACION, AJUSTE con haber y NC), con {@code FOR UPDATE}: lectura ACTUAL,
     * inmune a la foto de REPEATABLE READ (una venta o un Recargo nuevos consumen crédito y no pueden ver de menos ni de más).
     * Filas crudas. {@code force index (ix_mcc_cliente)} las recorre por (ferretería, cliente), con el mismo gap residual
     * acotado al cliente que {@code ImputacionRepository.imputadoBloqueado}; el mutex real es la fila del cliente. Requiere
     * transacción.
     */
    @Query(value = "select id as id, tipo as tipo, recibo_id as reciboId, ajuste_id as ajusteId, comprobante_id as comprobanteId, "
            + "debe as debe, haber as haber "
            + "from movimiento_cuenta_cliente force index (ix_mcc_cliente) where ferreteria_id = :fid and cliente_id = :clienteId "
            + "and (tipo in ('COBRO', 'ANULACION', 'NC') or (tipo = 'AJUSTE' and haber > 0)) order by id for update",
            nativeQuery = true)
    List<FilaDeCredito> creditosBloqueados(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /** El renglón de ese ajuste en el libro del cliente (a lo sumo uno: uk_mcc_ajuste). */
    @Query("select m from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.ajusteId = :ajusteId")
    List<MovimientoCuentaCliente> deAjuste(@Param("fid") Long ferreteriaId, @Param("ajusteId") Long ajusteId);

    /** El saldo del cliente justo después de ese renglón: Σ(debe − haber) del libro hasta su id. */
    @Query("select coalesce(sum(m.debe - m.haber), 0) from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId and m.id <= :movimientoId")
    BigDecimal saldoHastaMovimiento(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("movimientoId") Long movimientoId);

    /**
     * Los movimientos de UN recibo ({@code COBRO} y {@code ANULACION}), en orden: lo que {@code asentarAnulacion} mira, bajo
     * el lock del cliente, para defenderse (un solo COBRO con haber = total y ninguna ANULACION previa). Lectura plana.
     */
    @Query("select m from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.reciboId = :reciboId "
            + "and m.tipo in (com.ferreteria.clientes.model.TipoMovimientoCuenta.COBRO, "
            + "com.ferreteria.clientes.model.TipoMovimientoCuenta.ANULACION) order by m.id")
    List<MovimientoCuentaCliente> deRecibo(@Param("fid") Long ferreteriaId, @Param("reciboId") Long reciboId);

    // ---------------------------------------------------------------------------------------------------------------
    // Extracto (CC-072..CC-080): consultas nativas, con :fid explícito, SOLO por los wrappers *Scoped.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Una línea del extracto. Proyección de una consulta NATIVA: los booleanos ({@code excedioLimite}, {@code anulado}) son 0/1 en
     * MySQL y el driver los entrega como {@link Boolean} ({@code TINYINT(1)}) o como {@link Number} (una expresión): se leen como
     * {@link Object} y el servicio los convierte (proyectar a {@code Number} falla con un {@code Boolean}). {@code saldo} es el saldo del cliente DESPUÉS de
     * la línea (Σ debe − haber de todo el libro hasta ella, por id).
     */
    interface LineaDeExtracto {
        Long getId();

        LocalDateTime getFecha();

        String getTipo();

        String getReferencia();

        BigDecimal getDebe();

        BigDecimal getHaber();

        BigDecimal getSaldo();

        Long getReciboId();

        Long getComprobanteId();

        String getMotivo();

        Object getExcedioLimite();

        String getUsuario();

        String getAutorizadoPor();

        Object getAnulado();
    }

    /** Σ debe y Σ haber de las líneas que pasan el filtro (0 si ninguna). */
    interface TotalesDelPeriodo {
        BigDecimal getDebe();

        BigDecimal getHaber();
    }

    /**
     * Las líneas del extracto, la más nueva primero (id descendente). La ventana {@code SUM(debe − haber) OVER (ORDER BY id)}
     * corre sobre TODO el libro del cliente (un rango de {@code ix_mcc_cliente (ferreteria_id, cliente_id, id)}, que ya viene en
     * orden de id) y los filtros de fecha y tipo y el LIMIT van AFUERA, sobre la derivada: el saldo de cada línea es el real
     * aunque se filtre o se pagine. Sin filtros se bindean centinelas (D11: {@code 1000-01-01}, {@code 9999-12-31}, todos los
     * tipos), así no hay {@code :p is null or …} (el tipado de null en nativas de Hibernate 6). {@code anulado} sale de un
     * EXISTS sobre {@code uk_mcc_recibo_tipo}: clientes no lee {@code recibo} (ventas). {@code force index (ix_mcc_cliente)} fija
     * el recorrido del libro del cliente por su índice (el optimizador elegía {@code fk_mcc_cliente}, solo por cliente).
     */
    @Query(value = "select l.id as id, l.fecha as fecha, l.tipo as tipo, l.referencia as referencia, l.debe as debe, "
            + "l.haber as haber, l.saldo as saldo, l.recibo_id as reciboId, l.comprobante_id as comprobanteId, "
            + "l.motivo as motivo, l.excedio_limite as excedioLimite, u.nombre as usuario, a.nombre as autorizadoPor, "
            + "(l.tipo = 'COBRO' and exists (select 1 from movimiento_cuenta_cliente x where x.recibo_id = l.recibo_id "
            + "and x.tipo = 'ANULACION' and x.ferreteria_id = :fid)) as anulado "
            + "from (select m.*, sum(m.debe - m.haber) over (order by m.id rows between unbounded preceding and current row) "
            + "as saldo from movimiento_cuenta_cliente m force index (ix_mcc_cliente) where m.ferreteria_id = :fid and m.cliente_id = :clienteId) l "
            + "left join usuario u on u.id = l.usuario_id and u.ferreteria_id = :fid "
            + "left join usuario a on a.id = l.autorizado_por and a.ferreteria_id = :fid "
            + "where l.fecha >= :desde and l.fecha < :hastaExclusivo and l.tipo in (:tipos) "
            + "order by l.id desc limit :limite offset :offset", nativeQuery = true)
    List<LineaDeExtracto> lineasDelExtracto(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("desde") LocalDateTime desde, @Param("hastaExclusivo") LocalDateTime hastaExclusivo,
            @Param("tipos") Collection<String> tipos, @Param("limite") int limite, @Param("offset") int offset);

    /** Cuántas líneas pasan el filtro (sin ventana): para el total de líneas y de páginas. */
    @Query(value = "select count(*) from movimiento_cuenta_cliente m where m.ferreteria_id = :fid and m.cliente_id = :clienteId "
            + "and m.fecha >= :desde and m.fecha < :hastaExclusivo and m.tipo in (:tipos)", nativeQuery = true)
    long contarLineas(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("desde") LocalDateTime desde, @Param("hastaExclusivo") LocalDateTime hastaExclusivo,
            @Param("tipos") Collection<String> tipos);

    /** El saldo inicial del período: Σ(debe − haber) de lo anterior a {@code desde} (0 con el centinela de «sin desde»). */
    @Query(value = "select coalesce(sum(m.debe - m.haber), 0) from movimiento_cuenta_cliente m "
            + "where m.ferreteria_id = :fid and m.cliente_id = :clienteId and m.fecha < :desde", nativeQuery = true)
    BigDecimal saldoAntesDe(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("desde") LocalDateTime desde);

    /** Σ debe y Σ haber de lo que pasa el filtro de fecha y tipo (el encabezado: totalDebe y totalHaber). */
    @Query(value = "select coalesce(sum(m.debe), 0) as debe, coalesce(sum(m.haber), 0) as haber "
            + "from movimiento_cuenta_cliente m where m.ferreteria_id = :fid and m.cliente_id = :clienteId "
            + "and m.fecha >= :desde and m.fecha < :hastaExclusivo and m.tipo in (:tipos)", nativeQuery = true)
    TotalesDelPeriodo totalesDelPeriodo(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("desde") LocalDateTime desde, @Param("hastaExclusivo") LocalDateTime hastaExclusivo,
            @Param("tipos") Collection<String> tipos);

    /** El saldo final del período: Σ(debe − haber) de todo el libro anterior a {@code hastaExclusivo}, SIN filtro de tipo. */
    @Query(value = "select coalesce(sum(m.debe - m.haber), 0) from movimiento_cuenta_cliente m "
            + "where m.ferreteria_id = :fid and m.cliente_id = :clienteId and m.fecha < :hastaExclusivo", nativeQuery = true)
    BigDecimal saldoHasta(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("hastaExclusivo") LocalDateTime hastaExclusivo);

    default List<DebitoDeComprobante> debitosDeClienteScoped(Long clienteId) {
        return debitosDeCliente(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<DebitoDeComprobante> debitosDeComprobantesScoped(Long clienteId, List<Long> comprobanteIds) {
        return debitosDeComprobantes(TenantContext.requireFerreteriaId(), clienteId, comprobanteIds);
    }

    default List<Long> idsDePartidasScoped(Long clienteId, List<Long> comprobanteIds) {
        return idsDePartidas(TenantContext.requireFerreteriaId(), clienteId, comprobanteIds);
    }

    default List<DebitoDeComprobante> debitosDeRecargosScoped(Long clienteId, List<Long> ajusteIds) {
        return debitosDeRecargos(TenantContext.requireFerreteriaId(), clienteId, ajusteIds);
    }

    default List<Long> idsDePartidasDeRecargosScoped(Long clienteId, List<Long> ajusteIds) {
        return idsDePartidasDeRecargos(TenantContext.requireFerreteriaId(), clienteId, ajusteIds);
    }

    default BigDecimal creditoDeDescuentosScoped(Long clienteId) {
        return creditoDeDescuentos(TenantContext.requireFerreteriaId(), clienteId);
    }

    default BigDecimal creditoDeNotasDeCreditoScoped(Long clienteId) {
        return creditoDeNotasDeCredito(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<FilaDeCredito> creditosBloqueadosScoped(Long clienteId) {
        return creditosBloqueados(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<MovimientoCuentaCliente> deAjusteScoped(Long ajusteId) {
        return deAjuste(TenantContext.requireFerreteriaId(), ajusteId);
    }

    default BigDecimal saldoHastaMovimientoScoped(Long clienteId, Long movimientoId) {
        return saldoHastaMovimiento(TenantContext.requireFerreteriaId(), clienteId, movimientoId);
    }

    default List<Long> bloquearPartidasScoped(Long clienteId, List<Long> ids) {
        return bloquearPartidas(TenantContext.requireFerreteriaId(), clienteId, ids);
    }

    default BigDecimal creditoNetoDeRecibosScoped(Long clienteId) {
        return creditoNetoDeRecibos(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<MovimientoCuentaCliente> deReciboScoped(Long reciboId) {
        return deRecibo(TenantContext.requireFerreteriaId(), reciboId);
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

    default List<LineaDeExtracto> lineasDelExtractoScoped(Long clienteId, LocalDateTime desde, LocalDateTime hastaExclusivo,
            Collection<TipoMovimientoCuenta> tipos, int limite, int offset) {
        return lineasDelExtracto(TenantContext.requireFerreteriaId(), clienteId, desde, hastaExclusivo, TipoMovimientoCuenta.nombres(tipos), limite,
                offset);
    }

    default long contarLineasScoped(Long clienteId, LocalDateTime desde, LocalDateTime hastaExclusivo,
            Collection<TipoMovimientoCuenta> tipos) {
        return contarLineas(TenantContext.requireFerreteriaId(), clienteId, desde, hastaExclusivo, TipoMovimientoCuenta.nombres(tipos));
    }

    default BigDecimal saldoAntesDeScoped(Long clienteId, LocalDateTime desde) {
        return saldoAntesDe(TenantContext.requireFerreteriaId(), clienteId, desde);
    }

    default TotalesDelPeriodo totalesDelPeriodoScoped(Long clienteId, LocalDateTime desde, LocalDateTime hastaExclusivo,
            Collection<TipoMovimientoCuenta> tipos) {
        return totalesDelPeriodo(TenantContext.requireFerreteriaId(), clienteId, desde, hastaExclusivo, TipoMovimientoCuenta.nombres(tipos));
    }

    default BigDecimal saldoHastaScoped(Long clienteId, LocalDateTime hastaExclusivo) {
        return saldoHasta(TenantContext.requireFerreteriaId(), clienteId, hastaExclusivo);
    }
}
