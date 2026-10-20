package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Los recibos de cobranza (CC-036). Los recibos son inmutables y nadie los borra (regla ArchUnit (k)). El recibo por id se
 * busca con {@code findScoped} de la base (otra ferretería = vacío = 404).
 */
public interface ReciboRepository extends TenantScopedRepository<Recibo, Long> {

    @Query("select r from Recibo r where r.ferreteriaId = :fid and r.claveIdempotencia = :clave")
    Optional<Recibo> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    /** El recibo de la ferretería del request con esa clave de idempotencia; el de otra ferretería no cuenta. */
    default Optional<Recibo> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }

    @Query("select r.medio, sum(r.total) from Recibo r where r.ferreteriaId = :fid and r.cajaId = :cajaId group by r.medio")
    List<Object[]> sumasDeCaja(@Param("fid") Long ferreteriaId, @Param("cajaId") Long cajaId);

    /**
     * CC-053: lo cobrado por cada medio en los recibos de la caja; los medios sin cobranzas no vienen. Lectura plana: en el
     * cierre corre DESPUÉS de tomar la caja con lock exclusivo, así que ve todo recibo comprometido.
     */
    default Map<MedioPago, BigDecimal> totalesDeCajaScoped(Long cajaId) {
        Map<MedioPago, BigDecimal> totales = new EnumMap<>(MedioPago.class);
        sumasDeCaja(TenantContext.requireFerreteriaId(), cajaId)
                .forEach(fila -> totales.put((MedioPago) fila[0], (BigDecimal) fila[1]));
        return totales;
    }
}
