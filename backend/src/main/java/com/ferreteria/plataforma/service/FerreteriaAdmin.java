package com.ferreteria.plataforma.service;

import com.ferreteria.facturacion.api.ConfigArcaParaPlataforma.ResumenArca;
import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import java.time.LocalDateTime;

/**
 * Una ferretería vista por el super admin: sus datos de alta, cuántos usuarios activos tiene y su configuración de ARCA
 * (marca «de prueba», ambiente, punto de venta y facturación habilitada). Nada de ventas.
 */
public record FerreteriaAdmin(Long id, String razonSocial, String cuit, CondicionIva condicionIva, EstadoFerreteria estado,
        LocalDateTime creadoEn, long duenosActivos, long vendedoresActivos, Long duenoId, String emailDueno,
        ResumenArca arca) {}
