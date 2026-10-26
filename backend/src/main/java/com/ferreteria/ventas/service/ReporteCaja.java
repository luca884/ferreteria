package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.MovimientoCaja;
import com.ferreteria.ventas.model.TipoMovimientoCaja;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.MovimientoCajaRepository;
import com.ferreteria.ventas.service.ReporteVentas.Rango;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporte de ingresos, gastos y retiros de la caja del Dueno (G73), de un rango de dias de Buenos Aires. Entran los
 * movimientos de todas las cajas, cerradas y abierta, por su propia fecha. El rango se pide igual que en los reportes de
 * ventas ({@link ReporteVentas#rango}).
 */
@Service
public class ReporteCaja {

    public record Movimiento(Long id, LocalDateTime fecha, TipoMovimientoCaja tipo, String motivo, String usuario,
            BigDecimal importe) {}

    /** Las filas del rango y, por tipo, lo que suman esas filas (0 en los tipos sin filas). */
    public record Reporte(LocalDate desde, LocalDate hasta, List<Movimiento> filas,
            Map<TipoMovimientoCaja, BigDecimal> totales) {}

    private final MovimientoCajaRepository movimientos;
    private final CajaRepository cajas;
    private final ReporteVentas reporteVentas;

    public ReporteCaja(MovimientoCajaRepository movimientos, CajaRepository cajas, ReporteVentas reporteVentas) {
        this.movimientos = movimientos;
        this.cajas = cajas;
        this.reporteVentas = reporteVentas;
    }

    /** El rango pedido: sin fechas es hoy; «hasta» no puede ser anterior a «desde» y el rango es de hasta 366 dias. */
    public Rango rango(LocalDate desde, LocalDate hasta) {
        return reporteVentas.rango(desde, hasta);
    }

    /** Los movimientos del rango, de un tipo o de todos si {@code tipo} es null. */
    @Transactional(readOnly = true)
    public Reporte movimientos(Rango rango, TipoMovimientoCaja tipo) {
        List<MovimientoCaja> del = movimientos
                .delRangoScoped(rango.desde().atStartOfDay(), rango.hasta().plusDays(1).atStartOfDay()).stream()
                .filter(m -> tipo == null || m.getTipo() == tipo)
                .toList();
        Map<Long, String> nombres = cajas.nombresScoped(
                del.stream().map(MovimientoCaja::getUsuarioId).collect(Collectors.toSet()));
        Map<TipoMovimientoCaja, BigDecimal> totales = new EnumMap<>(TipoMovimientoCaja.class);
        for (TipoMovimientoCaja t : TipoMovimientoCaja.values()) {
            totales.put(t, BigDecimal.ZERO);
        }
        del.forEach(m -> totales.merge(m.getTipo(), m.getImporte(), BigDecimal::add));
        List<Movimiento> filas = del.stream()
                .map(m -> new Movimiento(m.getId(), m.getFecha(), m.getTipo(), m.getMotivo(),
                        nombres.get(m.getUsuarioId()), m.getImporte()))
                .toList();
        return new Reporte(rango.desde(), rango.hasta(), filas, totales);
    }
}
