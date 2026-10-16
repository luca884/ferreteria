package com.ferreteria.cotizacion.repository;

import com.ferreteria.cotizacion.model.Cotizacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de la cotizacion del mercado. NO extiende TenantScopedRepository, a proposito: la cotizacion es global
 * (la misma para todas las ferreterias) y la tabla no tiene ferreteria_id. Los datos propios de cada ferreteria
 * (su configuracion y su dolar vigente) viven en configuracion_cotizacion, que si es de tenant.
 */
public interface CotizacionRepository extends JpaRepository<Cotizacion, Long> {

    /** El ultimo valor guardado de un tipo. */
    Optional<Cotizacion> findByMonedaAndCasa(String moneda, String casa);

    List<Cotizacion> findByMoneda(String moneda);

    /** Momento de la ultima respuesta exitosa de cualquier fuente; vacio si todavia no hay ninguna cotizacion. */
    @Query("select max(c.obtenidaEn) from Cotizacion c")
    Optional<Instant> ultimaObtenidaEn();
}
