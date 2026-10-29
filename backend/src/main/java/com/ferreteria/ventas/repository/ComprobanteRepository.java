package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.Comprobante;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ComprobanteRepository extends TenantScopedRepository<Comprobante, Long> {

    @Query("select c from Comprobante c where c.ferreteriaId = :fid and c.claveIdempotencia = :clave")
    Optional<Comprobante> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** La venta de la ferreteria del request con esa clave de idempotencia; la de otra ferreteria no cuenta. */
    default Optional<Comprobante> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }

    // ---- Reportes de ventas: [desde, hasta) en hora de Buenos Aires, solo ventas (no presupuestos ni remitos) ----

    /** Solo las ventas: «Por medio de pago» y «Con descuento» no cuentan las notas de credito (CU-08, C4). */
    String VENTAS_DEL_RANGO = " c.ferreteriaId = :fid and c.tipo = com.ferreteria.ventas.model.TipoComprobante.VENTA"
            + " and c.fecha >= :desde and c.fecha < :hasta";

    /**
     * Las ventas y las notas de credito (CU-08, C4: la NC y la anulacion interna restan en SU fecha, no en la de la venta).
     * Sus importes se guardan en positivo: quien lee resta los de la NOTA_CREDITO.
     */
    String COMPROBANTES_DEL_RANGO = " c.ferreteriaId = :fid and c.tipo in ("
            + "com.ferreteria.ventas.model.TipoComprobante.VENTA, com.ferreteria.ventas.model.TipoComprobante.NOTA_CREDITO)"
            + " and c.fecha >= :desde and c.fecha < :hasta";

    /** El renglon de una nota de credito resta (CU-08, C4): el signo de cada importe por renglon. */
    String ES_NOTA = "c.tipo = com.ferreteria.ventas.model.TipoComprobante.NOTA_CREDITO";

    /**
     * Una fila por venta o nota de credito: id, fecha, usuario (el que la hizo: la NC es de quien anulo), total, neto, IVA,
     * la suma del costo de sus renglones, cuantos renglones no tienen costo y el tipo. Los importes van en positivo.
     */
    @Query("select c.id, c.fecha, c.usuarioId, c.total, c.neto, c.iva, sum(i.costo), "
            + "sum(case when i.costo is null then 1 else 0 end), c.tipo "
            + "from Comprobante c, ItemComprobante i where i.comprobanteId = c.id and" + COMPROBANTES_DEL_RANGO
            + " group by c.id, c.fecha, c.usuarioId, c.total, c.neto, c.iva, c.tipo order by c.fecha, c.id")
    List<Object[]> ventasDelRango(@Param("fid") Long ferreteriaId, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    default List<Object[]> ventasDelRangoScoped(LocalDateTime desde, LocalDateTime hasta) {
        return ventasDelRango(TenantContext.requireFerreteriaId(), desde, hasta);
    }

    /** Por forma de pago (nombre guardado en el pago) y medio: cantidad de pagos y monto. */
    @Query("select p.formaPagoNombre, p.medio, count(p), sum(p.monto) from Pago p, Comprobante c "
            + "where p.comprobanteId = c.id and" + VENTAS_DEL_RANGO
            + " group by p.formaPagoNombre, p.medio order by sum(p.monto) desc, p.formaPagoNombre")
    List<Object[]> pagosDelRango(@Param("fid") Long ferreteriaId, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    default List<Object[]> pagosDelRangoScoped(LocalDateTime desde, LocalDateTime hasta) {
        return pagosDelRango(TenantContext.requireFerreteriaId(), desde, hasta);
    }

    /**
     * Por articulo: id, descripcion (la mayor de las copiadas), cantidad, total de los renglones, ganancia de los
     * renglones con costo (neto - costo), renglones vendidos sin costo y renglones vendidos. Los renglones de una nota de
     * credito restan la cantidad, el total y la ganancia (CU-08, C4), y no se cuentan como renglones.
     */
    @Query("select i.articuloId, max(i.descripcion), "
            + "sum(case when " + ES_NOTA + " then -i.cantidad else i.cantidad end), "
            + "sum(case when " + ES_NOTA + " then -i.total else i.total end), "
            + "sum(case when i.costo is null then 0 when " + ES_NOTA + " then i.costo - i.neto else i.neto - i.costo end), "
            + "sum(case when i.costo is null and not " + ES_NOTA + " then 1 else 0 end), "
            + "sum(case when " + ES_NOTA + " then 0 else 1 end) "
            + "from ItemComprobante i, Comprobante c where i.comprobanteId = c.id and" + COMPROBANTES_DEL_RANGO
            + " group by i.articuloId order by sum(case when " + ES_NOTA + " then -i.total else i.total end) desc, "
            + "i.articuloId")
    List<Object[]> articulosDelRango(@Param("fid") Long ferreteriaId, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    default List<Object[]> articulosDelRangoScoped(LocalDateTime desde, LocalDateTime hasta) {
        return articulosDelRango(TenantContext.requireFerreteriaId(), desde, hasta);
    }

    /** Las ventas con descuento global o con descuento en algun renglon, por fecha. */
    @Query("select c from Comprobante c where" + VENTAS_DEL_RANGO + " and (c.descuentoPorcentaje > 0 or exists ("
            + "select 1 from ItemComprobante i where i.comprobanteId = c.id and i.descuentoPorcentaje > 0)) "
            + "order by c.fecha, c.id")
    List<Comprobante> conDescuentoDelRango(@Param("fid") Long ferreteriaId, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    default List<Comprobante> conDescuentoDelRangoScoped(LocalDateTime desde, LocalDateTime hasta) {
        return conDescuentoDelRango(TenantContext.requireFerreteriaId(), desde, hasta);
    }

    // ---- Consulta de ventas (G60) ----

    /**
     * Las ventas de la ferreteria y sus notas de credito (CU-08, S14: renglones propios), mas nuevas primero. Cada filtro
     * nulo no filtra. {@code cliente} ya viene con los
     * comodines y escapado (barra invertida); la collation (_ai_ci) no distingue mayusculas ni tildes. {@code desde}
     * incluye ese instante y {@code hasta} no (es el comienzo del dia siguiente al ultimo que se quiere ver).
     */
    String FILTROS_DE_LA_CONSULTA = " c.ferreteriaId = :fid "
            + "and c.tipo in (com.ferreteria.ventas.model.TipoComprobante.VENTA, "
            + "com.ferreteria.ventas.model.TipoComprobante.NOTA_CREDITO) "
            + "and (:numero is null or c.numero = :numero) "
            + "and (:cliente is null or c.clienteNombre like :cliente escape '\\') "
            + "and (:desde is null or c.fecha >= :desde) and (:hasta is null or c.fecha < :hasta) ";

    @Query("select c from Comprobante c where" + FILTROS_DE_LA_CONSULTA + "order by c.numero desc")
    Page<Comprobante> buscarVentas(@Param("fid") Long ferreteriaId, @Param("numero") Long numero,
            @Param("cliente") String cliente, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta, Pageable pageable);

    default Page<Comprobante> buscarVentasScoped(
            Long numero, String cliente, LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        return buscarVentas(TenantContext.requireFerreteriaId(), numero, cliente, desde, hasta, pageable);
    }

    /**
     * Lo mismo, solo entre esas ventas: las que tienen el numero fiscal buscado (cu-07-factura-mostrador S15). Los ids los
     * da {@code facturacion}, sin unir tablas entre modulos; nunca vacios (sin ids no hay nada que buscar).
     */
    @Query("select c from Comprobante c where" + FILTROS_DE_LA_CONSULTA + "and c.id in :ids order by c.numero desc")
    Page<Comprobante> buscarVentasEntre(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids,
            @Param("numero") Long numero, @Param("cliente") String cliente, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta, Pageable pageable);

    default Page<Comprobante> buscarVentasEntreScoped(Collection<Long> ids, Long numero, String cliente,
            LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        return buscarVentasEntre(TenantContext.requireFerreteriaId(), ids, numero, cliente, desde, hasta, pageable);
    }

    /**
     * El id y el numero de las ventas que ese usuario cobro en esa caja, por numero (cu-07-contingencias C7: «llego el
     * CAE»). Solo ventas, no presupuestos ni remitos.
     */
    @Query("select c.id, c.numero from Comprobante c where c.ferreteriaId = :fid and c.cajaId = :caja "
            + "and c.usuarioId = :usuario and c.tipo = com.ferreteria.ventas.model.TipoComprobante.VENTA "
            + "order by c.numero")
    List<Object[]> ventasDelUsuarioEnLaCaja(@Param("fid") Long ferreteriaId, @Param("caja") Long cajaId,
            @Param("usuario") Long usuarioId);

    default List<Object[]> ventasDelUsuarioEnLaCajaScoped(Long cajaId, Long usuarioId) {
        return ventasDelUsuarioEnLaCaja(TenantContext.requireFerreteriaId(), cajaId, usuarioId);
    }

    // ---- La anulacion de las ventas (CU-08, C4) ----

    /** Las notas de credito (y anulaciones internas) de esas ventas: a lo sumo una por venta (uk_comprobante_asociado). */
    @Query("select n from Comprobante n where n.ferreteriaId = :fid and n.comprobanteAsociadoId in :ventas")
    List<Comprobante> notasDe(@Param("fid") Long ferreteriaId, @Param("ventas") Collection<Long> ventaIds);

    default List<Comprobante> notasDeScoped(Collection<Long> ventaIds) {
        return ventaIds.isEmpty() ? List.of() : notasDe(TenantContext.requireFerreteriaId(), ventaIds);
    }

    /** El id y el numero de esos comprobantes de la ferreteria (las ventas que anulan las NC de una pagina). */
    @Query("select c.id, c.numero from Comprobante c where c.ferreteriaId = :fid and c.id in :ids")
    List<Object[]> numerosDe(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    default List<Object[]> numerosDeScoped(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : numerosDe(TenantContext.requireFerreteriaId(), ids);
    }

    // ---- Clientes que compraron un articulo (G29, Ctrl+A del listado de GDS) ----

    /** Un cliente que compro el articulo: cuantas ventas, cuanto en total y la fecha y hora de la ultima. */
    interface ClienteDelArticulo {
        Long getClienteId();
        String getRazonSocial();
        Boolean getActivo();
        Long getCompras();
        BigDecimal getCantidad();
        Object getUltimaCompra();
    }

    String RENGLONES_CON_VENTA = " from item_comprobante i"
            + " join comprobante v on v.id = i.comprobante_id and v.ferreteria_id = i.ferreteria_id";

    String CON_CLIENTE = " join cliente c on c.id = v.cliente_id and c.ferreteria_id = v.ferreteria_id";

    /** Solo las ventas que no estan anuladas (CU-08, C4): una venta con su nota de credito no es una compra. */
    String DEL_ARTICULO = " where i.ferreteria_id = :fid and i.articulo_id = :articulo and v.tipo = 'VENTA'"
            + " and not exists (select 1 from comprobante n where n.ferreteria_id = v.ferreteria_id"
            + " and n.comprobante_asociado_id = v.id)";

    /**
     * Una fila por cliente que compro el articulo, la compra mas nueva primero. La razon social es la actual del cliente
     * (no la copiada en la venta).
     */
    @Query(nativeQuery = true, value = "select c.id as clienteId, c.razon_social as razonSocial, c.activo as activo,"
            + " count(distinct v.id) as compras, sum(i.cantidad) as cantidad,"
            + " max(v.fecha) as ultimaCompra" + RENGLONES_CON_VENTA + CON_CLIENTE + DEL_ARTICULO
            + " group by c.id, c.razon_social, c.activo order by max(v.fecha) desc, c.id desc",
            countQuery = "select count(distinct c.id)" + RENGLONES_CON_VENTA + CON_CLIENTE + DEL_ARTICULO)
    Page<ClienteDelArticulo> clientesDelArticulo(@Param("fid") Long ferreteriaId, @Param("articulo") Long articuloId,
            Pageable pageable);

    default Page<ClienteDelArticulo> clientesDelArticuloScoped(Long articuloId, Pageable pageable) {
        return clientesDelArticulo(TenantContext.requireFerreteriaId(), articuloId, pageable);
    }

    /** Cuantas ventas sin cliente (consumidor final) tienen el articulo. */
    @Query(nativeQuery = true, value = "select count(distinct v.id)" + RENGLONES_CON_VENTA + DEL_ARTICULO
            + " and v.cliente_id is null")
    long ventasSinClienteDelArticulo(@Param("fid") Long ferreteriaId, @Param("articulo") Long articuloId);

    default long ventasSinClienteDelArticuloScoped(Long articuloId) {
        return ventasSinClienteDelArticulo(TenantContext.requireFerreteriaId(), articuloId);
    }
}
