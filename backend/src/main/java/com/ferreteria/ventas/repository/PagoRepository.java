package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Pago;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public interface PagoRepository extends TenantScopedRepository<Pago, Long> {

    @Query("select p from Pago p where p.ferreteriaId = :fid and p.comprobanteId = :comprobanteId order by p.id")
    List<Pago> deComprobante(@Param("fid") Long ferreteriaId, @Param("comprobanteId") Long comprobanteId);

    default List<Pago> deComprobanteScoped(Long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }

    @Query("select p.medio, sum(p.monto) from Pago p, Comprobante c where c.id = p.comprobanteId "
            + "and c.ferreteriaId = :fid and p.ferreteriaId = :fid and c.cajaId = :cajaId group by p.medio")
    List<Object[]> sumasDeCaja(@Param("fid") Long ferreteriaId, @Param("cajaId") Long cajaId);

    /** CU-10: lo cobrado por cada medio en los comprobantes de la caja; los medios sin cobros no vienen. */
    default Map<MedioPago, BigDecimal> totalesDeCajaScoped(Long cajaId) {
        Map<MedioPago, BigDecimal> totales = new EnumMap<>(MedioPago.class);
        sumasDeCaja(TenantContext.requireFerreteriaId(), cajaId)
                .forEach(fila -> totales.put((MedioPago) fila[0], (BigDecimal) fila[1]));
        return totales;
    }
}
