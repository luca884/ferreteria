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

    /** El id del cliente del recibo (CC-057, CC-085): una proyección de una columna, sin cargar la entidad ni tomar locks. */
    @Query("select r.clienteId from Recibo r where r.ferreteriaId = :fid and r.id = :id")
    Optional<Long> clienteDe(@Param("fid") Long ferreteriaId, @Param("id") Long id);

    /**
     * CC-085: el cliente del recibo, para resolver reciboId → clienteId ANTES de tomar los locks (el cliente de un recibo es
     * inmutable). Vacío si el recibo no existe o es de otra ferretería (404). Lectura plana: la fachada la corre en una
     * transacción propia de solo lectura.
     */
    default Optional<Long> clienteDeScoped(Long reciboId) {
        return clienteDe(TenantContext.requireFerreteriaId(), reciboId);
    }

    @Query("select r.medio, sum(r.total) from Recibo r where r.ferreteriaId = :fid and r.cajaAnulacionId = :cajaId group by r.medio")
    List<Object[]> sumasAnuladasDeCaja(@Param("fid") Long ferreteriaId, @Param("cajaId") Long cajaId);

    /**
     * CC-081: lo anulado por cada medio en los recibos cuya CAJA DE ANULACIÓN es la pedida (sean de esa u otra caja); los medios
     * sin anulaciones no vienen. Usa {@code ix_recibo_caja_anulacion}. Lectura plana: en el cierre corre DESPUÉS de tomar la caja
     * con lock exclusivo, así que ve toda anulación comprometida (las que llegan después reciben CAJA_CERRADA).
     */
    default Map<MedioPago, BigDecimal> totalesAnuladosDeCajaScoped(Long cajaId) {
        Map<MedioPago, BigDecimal> totales = new EnumMap<>(MedioPago.class);
        sumasAnuladasDeCaja(TenantContext.requireFerreteriaId(), cajaId)
                .forEach(fila -> totales.put((MedioPago) fila[0], (BigDecimal) fila[1]));
        return totales;
    }
}
