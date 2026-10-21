package com.ferreteria.plataforma.web;

import com.ferreteria.plataforma.service.EstadoGeneral;
import java.util.List;

/** Totales de ferreterías activas y suspendidas, y cada una. */
public record EstadoGeneralResponse(long activas, long suspendidas, List<FerreteriaAdminResponse> ferreterias) {

    public static EstadoGeneralResponse de(EstadoGeneral e) {
        return new EstadoGeneralResponse(e.activas(), e.suspendidas(),
                e.ferreterias().stream().map(FerreteriaAdminResponse::de).toList());
    }
}
