package com.ferreteria.ventas.web;

/**
 * El estado al que se pasa un presupuesto (PP-005). Viaja como texto y lo valida el servicio, para que un valor que no existe,
 * uno que no se elige a mano (FACTURADO) y la falta del campo den el mismo 400 con el mismo mensaje.
 */
public record CambiarEstadoPresupuestoRequest(String estado) {}
