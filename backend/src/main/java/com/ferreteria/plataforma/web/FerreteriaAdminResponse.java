package com.ferreteria.plataforma.web;

import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import com.ferreteria.plataforma.service.FerreteriaAdmin;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Una ferretería para el super admin: datos de alta, usuarios activos por rol y su configuración de ARCA. {@code ambiente}
 * es SIMULADOR, HOMOLOGACION, PRODUCCION o null; {@code habilitadaEn} (UTC) es null mientras no facture. Sin ningún dato
 * de ventas.
 */
public record FerreteriaAdminResponse(Long id, String razonSocial, String cuit, CondicionIva condicionIva,
        EstadoFerreteria estado, LocalDateTime creadoEn, long duenosActivos, long vendedoresActivos, Long duenoId,
        String emailDueno, boolean dePrueba, String ambiente, Integer puntoVenta, boolean facturacionHabilitada,
        Instant habilitadaEn) {

    public static FerreteriaAdminResponse de(FerreteriaAdmin f) {
        return new FerreteriaAdminResponse(f.id(), f.razonSocial(), f.cuit(), f.condicionIva(), f.estado(), f.creadoEn(),
                f.duenosActivos(), f.vendedoresActivos(), f.duenoId(), f.emailDueno(), f.arca().dePrueba(),
                f.arca().ambiente(), f.arca().puntoVenta(), f.arca().facturacionHabilitada(), f.arca().habilitadaEn());
    }
}
