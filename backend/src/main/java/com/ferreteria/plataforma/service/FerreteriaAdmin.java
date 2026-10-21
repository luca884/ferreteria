package com.ferreteria.plataforma.service;

import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import java.time.LocalDateTime;

/** Una ferretería vista por el super admin: sus datos de alta y cuántos usuarios activos tiene. Nada de ventas. */
public record FerreteriaAdmin(Long id, String razonSocial, String cuit, CondicionIva condicionIva, EstadoFerreteria estado,
        LocalDateTime creadoEn, long duenosActivos, long vendedoresActivos, Long duenoId, String emailDueno) {}
