package com.ferreteria.ferreteria.repository;

import com.ferreteria.ferreteria.model.Ferreteria;
import org.springframework.data.jpa.repository.JpaRepository;

/** La ferreteria es la raiz del tenant, no un dato "de" un tenant: no extiende TenantScopedRepository. */
public interface FerreteriaRepository extends JpaRepository<Ferreteria, Long> {
}
