package com.ferreteria.cotizacion.repository;

import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;

import java.util.Optional;

/** Configuracion de cotizacion de cada ferreteria: una fila por ferreteria, siempre leida con su scope. */
public interface ConfiguracionCotizacionRepository extends TenantScopedRepository<ConfiguracionCotizacion, Long> {

    Optional<ConfiguracionCotizacion> findByFerreteriaId(Long ferreteriaId);

    /** La configuracion de la ferreteria del request; vacio si todavia no se creo (se crea al pedirla). */
    default Optional<ConfiguracionCotizacion> findPropiaScoped() {
        return findByFerreteriaId(TenantContext.requireFerreteriaId());
    }
}
