package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.Comprobante;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    String VENTAS_DEL_RANGO = " c.ferreteriaId = :fid and c.tipo = com.ferreteria.ventas.model.TipoComprobante.VENTA"
            + " and c.fecha >= :desde and c.fecha < :hasta";

    /**
     * Una fila por venta: id, fecha, usuario, total, neto, IVA, la suma del costo de sus renglones y cuantos renglones no
     * tienen costo.
     */
    @Query("select c.id, c.fecha, c.usuarioId, c.total, c.neto, c.iva, sum(i.costo), "
            + "sum(case when i.costo is null then 1 else 0 end) "
            + "from Comprobante c, ItemComprobante i where i.comprobanteId = c.id and" + VENTAS_DEL_RANGO
            + " group by c.id, c.fecha, c.usuarioId, c.total, c.neto, c.iva order by c.fecha, c.id")
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
     * renglones con costo (neto - costo), renglones sin costo y renglones.
     */
    @Query("select i.articuloId, max(i.descripcion), sum(i.cantidad), sum(i.total), "
            + "sum(case when i.costo is not null then i.neto - i.costo else 0 end), "
            + "sum(case when i.costo is null then 1 else 0 end), count(i) "
            + "from ItemComprobante i, Comprobante c where i.comprobanteId = c.id and" + VENTAS_DEL_RANGO
            + " group by i.articuloId order by sum(i.total) desc, i.articuloId")
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
}
