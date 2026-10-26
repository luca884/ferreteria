package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.TipoMovimientoCaja;
import com.ferreteria.ventas.service.ReporteCaja;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporte de ingresos, gastos y retiros de la caja (G73). Solo el Dueno. Recibe {@code desde} y {@code hasta} (ISO, dias
 * de Buenos Aires, ambos incluidos; sin fechas es hoy) y un {@code tipo} opcional; responde el rango usado, las filas y
 * los totales por tipo.
 */
@RestController
@RequestMapping("/api/reportes/caja")
@PreAuthorize("hasRole('DUENO')")
public class ReporteCajaController {

    private final ReporteCaja reporte;

    public ReporteCajaController(ReporteCaja reporte) {
        this.reporte = reporte;
    }

    @GetMapping("/movimientos")
    public ReporteCaja.Reporte movimientos(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) TipoMovimientoCaja tipo) {
        return reporte.movimientos(reporte.rango(desde, hasta), tipo);
    }
}
