package com.ferreteria.stock.repository;

import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface MovimientoStockRepository extends TenantScopedRepository<MovimientoStock, Long> {

    /**
     * Un movimiento tal como lo lista la consulta del articulo, con el numero del comprobante que lo origino y el nombre
     * del usuario. Es una lectura: une las tablas de ventas, remitos y compras por SQL porque el modulo stock no puede
     * depender de esas clases (ModulosArchTest).
     */
    interface MovimientoListado {
        Long getId();
        LocalDateTime getFecha();
        String getTipo();
        BigDecimal getCantidad();
        String getMotivo();
        String getUsuario();
        Long getVentaNumero();
        /** El tipo del comprobante de ventas (VENTA o NOTA_CREDITO, CU-08); null si el movimiento no tiene uno. */
        String getVentaTipo();
        /** El motivo de la anulacion interna (una NOTA_CREDITO con motivo, CU-08 C4); null en la nota con factura y en el resto. */
        String getMotivoAnulacion();
        Long getRemitoNumero();
        String getCompraTipo();
        Integer getCompraPuntoVenta();
        Long getCompraNumero();
        String getProveedor();
    }

    String DE_ARTICULO = """
            from movimiento_stock m
            where m.ferreteria_id = :fid and m.articulo_id = :articuloId
              and (:desde is null or m.fecha >= :desde) and (:hasta is null or m.fecha < :hasta)
            """;

    @Query(nativeQuery = true, value = """
            select m.id as id, m.fecha as fecha, m.tipo as tipo, m.cantidad as cantidad, m.motivo as motivo,
                   (select u.nombre from usuario u where u.id = m.usuario_id and u.ferreteria_id = m.ferreteria_id)
                       as usuario,
                   vc.numero as ventaNumero, vc.tipo as ventaTipo,
                   vc.motivo_anulacion as motivoAnulacion,
                   (select r.numero from remito r where r.id = m.remito_id and r.ferreteria_id = m.ferreteria_id)
                       as remitoNumero,
                   co.tipo as compraTipo, co.punto_venta as compraPuntoVenta, co.numero as compraNumero,
                   p.nombre as proveedor
            from movimiento_stock m
            left join comprobante vc on vc.id = m.comprobante_id and vc.ferreteria_id = m.ferreteria_id
            left join compra co on co.id = m.compra_id and co.ferreteria_id = m.ferreteria_id
            left join proveedor p on p.id = co.proveedor_id and p.ferreteria_id = m.ferreteria_id
            where m.ferreteria_id = :fid and m.articulo_id = :articuloId
              and (:desde is null or m.fecha >= :desde) and (:hasta is null or m.fecha < :hasta)
            order by m.fecha desc, m.id desc
            """,
            countQuery = "select count(*) " + DE_ARTICULO)
    Page<MovimientoListado> deArticulo(@Param("fid") Long ferreteriaId, @Param("articuloId") Long articuloId,
                                       @Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta,
                                       Pageable pageable);

    /** Lo que sumaron los movimientos del articulo posteriores a uno dado (por fecha y, a igual fecha, por id). */
    @Query(nativeQuery = true, value = """
            select coalesce(sum(m.cantidad), 0) from movimiento_stock m
            where m.ferreteria_id = :fid and m.articulo_id = :articuloId
              and (m.fecha > :fecha or (m.fecha = :fecha and m.id > :id))
            """)
    BigDecimal sumaPosterior(@Param("fid") Long ferreteriaId, @Param("articuloId") Long articuloId,
                             @Param("fecha") LocalDateTime fecha, @Param("id") Long id);

    /** Movimientos del articulo de la ferreteria del request, el mas nuevo primero; desde inclusive, hasta exclusivo. */
    default Page<MovimientoListado> deArticuloScoped(
            Long articuloId, LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        return deArticulo(TenantContext.requireFerreteriaId(), articuloId, desde, hasta, pageable);
    }

    default BigDecimal sumaPosteriorScoped(Long articuloId, LocalDateTime fecha, Long id) {
        return sumaPosterior(TenantContext.requireFerreteriaId(), articuloId, fecha, id);
    }
}
