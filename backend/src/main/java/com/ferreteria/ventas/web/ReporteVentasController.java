package com.ferreteria.ventas.web;

import com.ferreteria.ventas.service.ReporteVentas;
import com.ferreteria.ventas.service.ReporteVentas.Agrupar;
import com.ferreteria.ventas.service.ReporteVentas.Rango;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reportes de ventas (como el menu Ventas de GDS). Solo el Dueno. Todos reciben {@code desde} y {@code hasta}
 * (ISO, dias de Buenos Aires, ambos incluidos; sin fechas es hoy) y responden el rango usado junto con las filas.
 */
@RestController
@RequestMapping("/api/reportes/ventas")
@PreAuthorize("hasRole('DUENO')")
public class ReporteVentasController {

    /** Un reporte en filas, con el rango que se uso. */
    public record Filas<T>(LocalDate desde, LocalDate hasta, List<T> filas) {}

    public record PeriodoResponse(String periodo, int cantidad, BigDecimal total, BigDecimal ganancia,
            int ventasSinCosto) {}

    private final ReporteVentas reportes;

    public ReporteVentasController(ReporteVentas reportes) {
        this.reportes = reportes;
    }

    @GetMapping("/resumen")
    public ReporteVentas.Resumen resumen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.resumen(reportes.rango(desde, hasta));
    }

    @GetMapping("/por-usuario")
    public Filas<ReporteVentas.PorUsuario> porUsuario(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Rango rango = reportes.rango(desde, hasta);
        return new Filas<>(rango.desde(), rango.hasta(), reportes.porUsuario(rango));
    }

    @GetMapping("/por-medio-pago")
    public Filas<ReporteVentas.PorMedioPago> porMedioPago(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Rango rango = reportes.rango(desde, hasta);
        return new Filas<>(rango.desde(), rango.hasta(), reportes.porMedioPago(rango));
    }

    @GetMapping("/por-periodo")
    public Filas<PeriodoResponse> porPeriodo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "DIA") Agrupar agrupar) {
        Rango rango = reportes.rango(desde, hasta);
        List<PeriodoResponse> filas = reportes.porPeriodo(rango, agrupar).stream()
                .map(g -> new PeriodoResponse(g.clave(), g.cantidad(), g.total(), g.ganancia(), g.ventasSinCosto()))
                .toList();
        return new Filas<>(rango.desde(), rango.hasta(), filas);
    }

    @GetMapping("/por-articulo")
    public Filas<ReporteVentas.PorArticulo> porArticulo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Rango rango = reportes.rango(desde, hasta);
        return new Filas<>(rango.desde(), rango.hasta(), reportes.porArticulo(rango));
    }

    @GetMapping("/por-rubro")
    public Filas<ReporteVentas.PorRubro> porRubro(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Rango rango = reportes.rango(desde, hasta);
        return new Filas<>(rango.desde(), rango.hasta(), reportes.porRubro(rango));
    }

    @GetMapping("/con-descuento")
    public Filas<ReporteVentas.ConDescuento> conDescuento(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Rango rango = reportes.rango(desde, hasta);
        return new Filas<>(rango.desde(), rango.hasta(), reportes.conDescuento(rango));
    }
}
