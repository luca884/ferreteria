package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.AjusteCuenta;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los ajustes manuales de la cuenta corriente (GDS-20): append-only, se agregan con {@code save} desde
 * {@code RegistroAjuste} y NADIE llama a {@code delete*} (lo vigila ModulosArchTest). Se leen por id con {@code findScoped}.
 */
public interface AjusteCuentaRepository extends TenantScopedRepository<AjusteCuenta, Long> {

    /** El ajuste de esa clave de idempotencia en la ferretería (UNIQUE (ferreteria, clave)), si ya se cargó. */
    @Query("select a from AjusteCuenta a where a.ferreteriaId = :fid and a.clave = :clave")
    Optional<AjusteCuenta> porClave(@Param("fid") Long ferreteriaId, @Param("clave") String clave);

    default Optional<AjusteCuenta> porClaveScoped(String clave) {
        return porClave(TenantContext.requireFerreteriaId(), clave);
    }
}
