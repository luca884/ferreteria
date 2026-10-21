package com.ferreteria.plataforma.web;

import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import com.ferreteria.plataforma.service.FerreteriaAdmin;
import java.time.LocalDateTime;

/** Una ferretería para el super admin: datos de alta y usuarios activos por rol. Sin ningún dato de ventas. */
public record FerreteriaAdminResponse(Long id, String razonSocial, String cuit, CondicionIva condicionIva,
        EstadoFerreteria estado, LocalDateTime creadoEn, long duenosActivos, long vendedoresActivos, Long duenoId,
        String emailDueno) {

    public static FerreteriaAdminResponse de(FerreteriaAdmin f) {
        return new FerreteriaAdminResponse(f.id(), f.razonSocial(), f.cuit(), f.condicionIva(), f.estado(), f.creadoEn(),
                f.duenosActivos(), f.vendedoresActivos(), f.duenoId(), f.emailDueno());
    }
}
