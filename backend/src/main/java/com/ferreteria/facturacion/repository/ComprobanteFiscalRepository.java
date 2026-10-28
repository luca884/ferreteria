package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los registros fiscales, siempre de la ferretería del request. Lo escribe solo el motor ({@code facturacion.motor}): el
 * alta guarda la entidad y cada transición es un UPDATE condicional que mira el estado (y el número) de antes, así un
 * resultado viejo o de otra instancia no pisa nada (devuelve 0). Nada se borra.
 *
 * <p>Las consultas reciben la ferretería explícita; se llaman por su {@code *Scoped}, que la toma de {@code TenantContext}.
 */
public interface ComprobanteFiscalRepository extends TenantScopedRepository<ComprobanteFiscal, Long> {

    /** El PENDIENTE más viejo de la serie (FIFO por id), por el índice {@code ix_comprobante_fiscal_cola}. */
    @Query(value = """
            select * from comprobante_fiscal
            where ferreteria_id = :fid and ambiente = :ambiente and punto_venta = :pv and cbte_tipo = :tipo
              and estado = 'PENDIENTE'
            order by id limit 1
            """, nativeQuery = true)
    Optional<ComprobanteFiscal> siguientePendiente(@Param("fid") Long ferreteriaId, @Param("ambiente") String ambiente,
            @Param("pv") int puntoVenta, @Param("tipo") int cbteTipo);

    default Optional<ComprobanteFiscal> siguientePendienteScoped(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        return siguientePendiente(TenantContext.requireFerreteriaId(), ambiente.name(), puntoVenta, cbteTipo);
    }

    /** El registro de una venta (1-1: {@code uk_comprobante_fiscal_comprobante}). Solo lectura: el estado que ve la venta. */
    @Query("select c from ComprobanteFiscal c where c.ferreteriaId = :fid and c.comprobanteId = :comprobanteId")
    Optional<ComprobanteFiscal> deComprobante(@Param("fid") Long ferreteriaId, @Param("comprobanteId") long comprobanteId);

    default Optional<ComprobanteFiscal> deComprobanteScoped(long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }

    /** Los registros de esas ventas (el listado, CU-16). Solo lectura. */
    @Query("select c from ComprobanteFiscal c where c.ferreteriaId = :fid and c.comprobanteId in :comprobantes")
    List<ComprobanteFiscal> deComprobantes(@Param("fid") Long ferreteriaId,
            @Param("comprobantes") Collection<Long> comprobanteIds);

    /** Vacío sin ventas: no llega a la base. */
    default List<ComprobanteFiscal> deComprobantesScoped(Collection<Long> comprobanteIds) {
        if (comprobanteIds.isEmpty()) {
            return List.of();
        }
        return deComprobantes(TenantContext.requireFerreteriaId(), comprobanteIds);
    }

    /**
     * Las ventas cuyo registro tiene ese número, en cualquier ambiente, clase y letra; con {@code puntoVenta} null, en
     * cualquier punto de venta (la búsqueda por número fiscal, cu-07-factura-mostrador S15). Solo lectura.
     */
    @Query("select c.comprobanteId from ComprobanteFiscal c where c.ferreteriaId = :fid and c.numero = :numero "
            + "and (:pv is null or c.puntoVenta = :pv) order by c.comprobanteId")
    List<Long> comprobantesConNumero(@Param("fid") Long ferreteriaId, @Param("pv") Integer puntoVenta,
            @Param("numero") long numero);

    default List<Long> comprobantesConNumeroScoped(Integer puntoVenta, long numero) {
        return comprobantesConNumero(TenantContext.requireFerreteriaId(), puntoVenta, numero);
    }

    /** PENDIENTE → EN_CURSO con el número y la CbteFch del intento que se guarda en la misma transacción. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'EN_CURSO', numero = :numero, cbte_fch = :cbteFch
            where ferreteria_id = :fid and id = :id and estado = 'PENDIENTE'
            """, nativeQuery = true)
    int numerar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("cbteFch") LocalDate cbteFch);

    default int numerarScoped(long id, long numero, LocalDate cbteFch) {
        return numerar(TenantContext.requireFerreteriaId(), id, numero, cbteFch);
    }

    /** EN_CURSO (con ese número) → PENDIENTE sin número: el pedido no llegó a ARCA. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'PENDIENTE', numero = null, cbte_fch = null
            where ferreteria_id = :fid and id = :id and estado = 'EN_CURSO' and numero = :numero
            """, nativeQuery = true)
    int liberar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero);

    default int liberarScoped(long id, long numero) {
        return liberar(TenantContext.requireFerreteriaId(), id, numero);
    }

    /** {@code desde} (con ese número) → {@code hacia}, conservando el número: EN_CURSO ⇄ EN_VERIFICACION y las alertas. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = :hacia
            where ferreteria_id = :fid and id = :id and estado = :desde and numero = :numero
            """, nativeQuery = true)
    int pasar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("desde") String desde, @Param("hacia") String hacia);

    default int pasarScoped(long id, long numero, EstadoFiscal desde, EstadoFiscal hacia) {
        return pasar(TenantContext.requireFerreteriaId(), id, numero, desde.name(), hacia.name());
    }

    /** {@code desde} (EN_CURSO o EN_VERIFICACION, con ese número) → AUTORIZADO, con el CAE y lo que dijo ARCA tal cual. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'AUTORIZADO', resultado = 'A', cae = :cae,
              cae_vencimiento = :vencimiento, observaciones = :observaciones, errores = :errores
            where ferreteria_id = :fid and id = :id and estado = :desde and numero = :numero
            """, nativeQuery = true)
    int autorizar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("desde") String desde, @Param("cae") String cae, @Param("vencimiento") LocalDate vencimiento,
            @Param("observaciones") String observaciones, @Param("errores") String errores);

    default int autorizarScoped(long id, long numero, EstadoFiscal desde, String cae, LocalDate vencimiento,
            String observaciones, String errores) {
        return autorizar(TenantContext.requireFerreteriaId(), id, numero, desde.name(), cae, vencimiento, observaciones,
                errores);
    }

    /** EN_CURSO (con ese número) → RECHAZADO: el número se libera, y lo que dijo ARCA queda tal cual. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', numero = null, cbte_fch = null,
              observaciones = :observaciones, errores = :errores
            where ferreteria_id = :fid and id = :id and estado = 'EN_CURSO' and numero = :numero
            """, nativeQuery = true)
    int rechazar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("observaciones") String observaciones, @Param("errores") String errores);

    default int rechazarScoped(long id, long numero, String observaciones, String errores) {
        return rechazar(TenantContext.requireFerreteriaId(), id, numero, observaciones, errores);
    }

    /** PENDIENTE → FUERA_DE_FECHA, sin número y sin reintento. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'FUERA_DE_FECHA'
            where ferreteria_id = :fid and id = :id and estado = 'PENDIENTE'
            """, nativeQuery = true)
    int fueraDeFecha(@Param("fid") Long ferreteriaId, @Param("id") long id);

    default int fueraDeFechaScoped(long id) {
        return fueraDeFecha(TenantContext.requireFerreteriaId(), id);
    }

    /** EN_VERIFICACION (con ese número) → FUERA_DE_FECHA: ARCA no lo tiene y la CbteFch ya no entra para reenviarlo. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'FUERA_DE_FECHA', numero = null, cbte_fch = null
            where ferreteria_id = :fid and id = :id and estado = 'EN_VERIFICACION' and numero = :numero
            """, nativeQuery = true)
    int vencerSinReenvio(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero);

    default int vencerSinReenvioScoped(long id, long numero) {
        return vencerSinReenvio(TenantContext.requireFerreteriaId(), id, numero);
    }
}
