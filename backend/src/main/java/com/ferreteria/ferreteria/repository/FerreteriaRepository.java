package com.ferreteria.ferreteria.repository;

import com.ferreteria.ferreteria.model.Ferreteria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** La ferreteria es la raiz del tenant, no un dato "de" un tenant: no extiende TenantScopedRepository. */
public interface FerreteriaRepository extends JpaRepository<Ferreteria, Long> {

    /**
     * Ids de las ferreterias en estado ACTIVA: las que recorren los procesos programados (recalculo diario de precios
     * en dolares). Las suspendidas no se tocan. Cada ferreteria se procesa despues con su propio TenantContext.
     */
    @Query("select f.id from Ferreteria f where f.estado = com.ferreteria.ferreteria.model.EstadoFerreteria.ACTIVA")
    List<Long> idsActivas();

    boolean existsByCuit(String cuit);

    /** Todas las ferreterias (activas y suspendidas) por razon social, para el estado general de la plataforma. */
    @Query("select f from Ferreteria f order by f.razonSocial, f.id")
    List<Ferreteria> todasOrdenadas();
}
