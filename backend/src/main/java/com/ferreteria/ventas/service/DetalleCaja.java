package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.EstadoCaja;
import com.ferreteria.ventas.model.MovimientoCaja;
import com.ferreteria.ventas.model.ResumenCaja;

import java.util.List;
import java.util.Map;

/** Una caja con sus totales y movimientos, y el nombre de cada usuario que aparece (por id). */
public record DetalleCaja(Caja caja, EstadoCaja estado, ResumenCaja resumen, List<MovimientoCaja> movimientos,
        Map<Long, String> nombres) {}
