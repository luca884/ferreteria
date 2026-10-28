package com.ferreteria.facturacion.simulador;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Los comprobantes que autorizó el simulador de WSFEv1, siempre de la ferretería del request. */
public interface ComprobanteSimuladoRepository extends TenantScopedRepository<ComprobanteSimulado, Long> {

    @Query("select c from ComprobanteSimulado c, SerieSimulada s where s.id = c.serieId and s.ferreteriaId = :fid "
            + "and c.ferreteriaId = :fid and s.cuit = :cuit and s.puntoVenta = :ptoVta and s.cbteTipo = :cbteTipo "
            + "and c.cbteNro = :cbteNro")
    Optional<ComprobanteSimulado> buscar(@Param("fid") Long ferreteriaId, @Param("cuit") long cuit,
            @Param("ptoVta") int ptoVta, @Param("cbteTipo") int cbteTipo, @Param("cbteNro") long cbteNro);

    /** El comprobante autorizado de esa serie y ese número, de la ferretería del request. */
    default Optional<ComprobanteSimulado> buscarScoped(long cuit, int ptoVta, int cbteTipo, long cbteNro) {
        return buscar(TenantContext.requireFerreteriaId(), cuit, ptoVta, cbteTipo, cbteNro);
    }
}
