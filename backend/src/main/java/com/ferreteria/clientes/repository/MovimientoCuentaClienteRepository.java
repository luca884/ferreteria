package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * El libro de cuenta corriente (CC-001). Append-only: se agrega con {@code save}, y NADIE llama a {@code delete*} sobre este
 * repositorio (lo vigila ModulosArchTest).
 */
public interface MovimientoCuentaClienteRepository extends TenantScopedRepository<MovimientoCuentaCliente, Long> {

    @Query(value = "select m from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.clienteId = :clienteId "
            + "order by m.id", countQuery = "select count(m) from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId")
    Page<MovimientoCuentaCliente> deCliente(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            Pageable pageable);

    /** Σ(debe − haber) del cliente; 0 si el libro está vacío. Lo usan los tests de la invariante y la cobranza futura. */
    @Query("select coalesce(sum(m.debe - m.haber), 0) from MovimientoCuentaCliente m "
            + "where m.ferreteriaId = :fid and m.clienteId = :clienteId")
    BigDecimal saldoDelLibro(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    @Query("select m from MovimientoCuentaCliente m where m.ferreteriaId = :fid and m.comprobanteId = :comprobanteId "
            + "order by m.id")
    List<MovimientoCuentaCliente> deComprobante(@Param("fid") Long ferreteriaId,
            @Param("comprobanteId") Long comprobanteId);

    default Page<MovimientoCuentaCliente> deClienteScoped(Long clienteId, Pageable pageable) {
        return deCliente(TenantContext.requireFerreteriaId(), clienteId, pageable);
    }

    default BigDecimal saldoDelLibroScoped(Long clienteId) {
        return saldoDelLibro(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<MovimientoCuentaCliente> deComprobanteScoped(Long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }
}
