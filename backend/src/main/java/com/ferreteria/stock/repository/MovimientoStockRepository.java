package com.ferreteria.stock.repository;

import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.tenant.TenantScopedRepository;

public interface MovimientoStockRepository extends TenantScopedRepository<MovimientoStock, Long> {
}
