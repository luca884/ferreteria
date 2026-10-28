package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.ComprobanteFiscalAlicuota;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** El desglose por alícuota de los registros fiscales, siempre de la ferretería del request. Solo alta: nada se cambia. */
public interface ComprobanteFiscalAlicuotaRepository extends TenantScopedRepository<ComprobanteFiscalAlicuota, Long> {

    @Query("select a from ComprobanteFiscalAlicuota a where a.ferreteriaId = :fid and a.comprobanteFiscalId = :registro "
            + "order by a.ivaId")
    List<ComprobanteFiscalAlicuota> deRegistro(@Param("fid") Long ferreteriaId, @Param("registro") long registroId);

    /** Las alícuotas del registro, en el orden de su Id (el del array {@code Iva}). */
    default List<ComprobanteFiscalAlicuota> deRegistroScoped(long registroId) {
        return deRegistro(TenantContext.requireFerreteriaId(), registroId);
    }
}
