package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.MovimientoCaja;

/** El movimiento de caja guardado y el nombre de quien lo cargo. */
public record MovimientoRegistrado(MovimientoCaja movimiento, String usuario) {}
