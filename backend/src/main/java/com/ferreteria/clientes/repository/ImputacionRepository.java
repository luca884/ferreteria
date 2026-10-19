package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las imputaciones (CC-026): append-only, se agregan con {@code save} desde {@code CuentaCorrienteService} y NADIE llama a
 * {@code delete*} sobre este repositorio (lo vigila ModulosArchTest).
 */
public interface ImputacionRepository extends TenantScopedRepository<Imputacion, Long> {

    /** Lo imputado a un comprobante (Σ de sus imputaciones). */
    interface ImputadoDeComprobante {
        Long getComprobanteId();

        BigDecimal getImputado();
    }

    /** Una fila cruda de imputación: el comprobante y el importe (sin agregar, ver {@link #imputadoBloqueado}). */
    interface FilaImputada {
        Long getComprobanteId();

        BigDecimal getImporte();
    }

    /**
     * Lo imputado por comprobante a todo el cliente. Lectura PLANA (con la foto de la transacción): sirve para el GET de
     * pendientes, que es solo un consejo. La cobranza NO decide con esto: lee con {@link #imputadoBloqueado}.
     */
    @Query("select i.comprobanteId as comprobanteId, sum(i.importe) as imputado from Imputacion i "
            + "where i.ferreteriaId = :fid and i.clienteId = :clienteId group by i.comprobanteId")
    List<ImputadoDeComprobante> imputadoPorComprobante(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    /**
     * Las imputaciones de esos comprobantes, con {@code FOR UPDATE}: una lectura ACTUAL (lo último confirmado), inmune a la
     * foto de REPEATABLE READ. Un imputado viejo SOBRE-estima el pendiente y dejaría sobre-imputar, así que es la única
     * lectura crítica de la cobranza. Filas crudas, sin agregado (MySQL no admite agregados bajo FOR UPDATE de forma
     * confiable): se suman en el dominio. Se limita a los comprobantes imputados para no tomar locks de más. Requiere
     * transacción; {@code comprobanteIds} no puede ser vacío.
     */
    @Query(value = "select comprobante_id as comprobanteId, importe as importe from imputacion "
            + "where ferreteria_id = :fid and cliente_id = :clienteId and comprobante_id in (:comprobanteIds) for update",
            nativeQuery = true)
    List<FilaImputada> imputadoBloqueado(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId,
            @Param("comprobanteIds") List<Long> comprobanteIds);

    /** Las imputaciones de un recibo, en el orden en que se asentaron. */
    @Query("select i from Imputacion i where i.ferreteriaId = :fid and i.reciboId = :reciboId order by i.id")
    List<Imputacion> deRecibo(@Param("fid") Long ferreteriaId, @Param("reciboId") Long reciboId);

    /** Σ de todas las imputaciones del cliente (con los COBRO, el «a cuenta»); 0 si no hay. */
    @Query("select coalesce(sum(i.importe), 0) from Imputacion i where i.ferreteriaId = :fid and i.clienteId = :clienteId")
    BigDecimal totalImputado(@Param("fid") Long ferreteriaId, @Param("clienteId") Long clienteId);

    default List<ImputadoDeComprobante> imputadoPorComprobanteScoped(Long clienteId) {
        return imputadoPorComprobante(TenantContext.requireFerreteriaId(), clienteId);
    }

    default List<FilaImputada> imputadoBloqueadoScoped(Long clienteId, List<Long> comprobanteIds) {
        return imputadoBloqueado(TenantContext.requireFerreteriaId(), clienteId, comprobanteIds);
    }

    default List<Imputacion> deReciboScoped(Long reciboId) {
        return deRecibo(TenantContext.requireFerreteriaId(), reciboId);
    }

    default BigDecimal totalImputadoScoped(Long clienteId) {
        return totalImputado(TenantContext.requireFerreteriaId(), clienteId);
    }
}
