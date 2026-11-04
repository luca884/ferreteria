package com.ferreteria.compras.repository;

import com.ferreteria.compras.model.Compra;
import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompraRepository extends TenantScopedRepository<Compra, Long> {

    @Query("select c from Compra c where c.ferreteriaId = :fid and c.claveIdempotencia = :clave")
    Optional<Compra> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** La compra de la ferreteria del request con esa clave de idempotencia; la de otra ferreteria no cuenta. */
    default Optional<Compra> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }

    @Query("select c from Compra c where c.ferreteriaId = :fid and c.proveedorId = :proveedorId and c.tipo = :tipo "
            + "and c.puntoVenta = :puntoVenta and c.numero = :numero")
    Optional<Compra> porComprobante(@Param("fid") Long ferreteriaId, @Param("proveedorId") Long proveedorId,
            @Param("tipo") TipoCompra tipo, @Param("puntoVenta") Integer puntoVenta, @Param("numero") Long numero);

    /** La compra ya cargada con ese comprobante del proveedor en la ferreteria del request (uk_compra_comprobante). */
    default Optional<Compra> porComprobanteScoped(Long proveedorId, TipoCompra tipo, Integer puntoVenta, Long numero) {
        return porComprobante(TenantContext.requireFerreteriaId(), proveedorId, tipo, puntoVenta, numero);
    }

    /** Las de la ferreteria, mas nuevas primero (por fecha y por orden de carga). Cada filtro nulo no filtra. */
    @Query("select c from Compra c where c.ferreteriaId = :fid and (:proveedorId is null or c.proveedorId = :proveedorId) "
            + "and (:desde is null or c.fecha >= :desde) and (:hasta is null or c.fecha <= :hasta) "
            + "order by c.fecha desc, c.id desc")
    Page<Compra> buscar(@Param("fid") Long ferreteriaId, @Param("proveedorId") Long proveedorId,
            @Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta, Pageable pageable);

    default Page<Compra> buscarScoped(Long proveedorId, LocalDate desde, LocalDate hasta, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), proveedorId, desde, hasta, pageable);
    }

    /**
     * Cada compra de esos articulos en la ferreteria, la mas nueva primero por articulo (fecha del comprobante y, a
     * igual fecha, orden de carga). Filas (articulo_id, fecha, proveedor_nombre, id, tipo, punto_venta, numero).
     */
    @Query(nativeQuery = true, value = """
            select ic.articulo_id, c.fecha, c.proveedor_nombre, c.id, c.tipo, c.punto_venta, c.numero
            from item_compra ic
            join compra c on c.id = ic.compra_id and c.ferreteria_id = ic.ferreteria_id
            where ic.ferreteria_id = :fid and ic.articulo_id in (:articulos)
            order by ic.articulo_id, c.fecha desc, c.registrada desc, c.id desc
            """)
    List<Object[]> comprasDe(@Param("fid") Long ferreteriaId, @Param("articulos") Collection<Long> articuloIds);

    /** Las compras de esos articulos en la ferreteria del request; ver {@link #comprasDe}. */
    default List<Object[]> comprasDeScoped(Collection<Long> articuloIds) {
        return comprasDe(TenantContext.requireFerreteriaId(), articuloIds);
    }
}
