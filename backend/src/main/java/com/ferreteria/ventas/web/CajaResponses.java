package com.ferreteria.ventas.web;

import com.ferreteria.common.PageResponse;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.MovimientoCaja;
import com.ferreteria.ventas.model.ResumenCaja;
import com.ferreteria.ventas.model.TipoMovimientoCaja;
import com.ferreteria.ventas.service.CajaVigente;
import com.ferreteria.ventas.service.DetalleCaja;
import com.ferreteria.ventas.service.HistorialCajas;
import com.ferreteria.ventas.service.MovimientoRegistrado;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Las respuestas de la caja (CU-10). {@link Vigente} y {@link CajaAbierta} las ve cualquier usuario y NO llevan
 * totales, esperado ni diferencia (cierre ciego). {@link Detalle} los lleva: lo reciben el Dueno (en vivo o del
 * historial) y quien cierra, recien en la respuesta del cierre.
 */
public final class CajaResponses {

    private CajaResponses() {
    }

    /** La caja abierta, sin plata salvo el efectivo inicial que cargo quien la abrio. */
    public record CajaAbierta(Long id, String estado, LocalDateTime fechaApertura, String abiertaPor,
            BigDecimal efectivoInicial) {

        static CajaAbierta de(CajaVigente vigente) {
            Caja caja = vigente.caja();
            return new CajaAbierta(caja.getId(), vigente.estado().name(), caja.getFechaApertura(), vigente.abiertaPor(),
                    caja.getEfectivoInicial());
        }
    }

    /** Estado de la caja de la ferreteria: SIN_CAJA, ABIERTA o DE_OTRO_DIA, y el efectivo propuesto para abrir. */
    public record Vigente(String estado, CajaAbierta caja, BigDecimal efectivoPropuesto) {

        static Vigente de(CajaVigente vigente) {
            if (vigente.caja() == null) {
                return new Vigente("SIN_CAJA", null, vigente.efectivoPropuesto());
            }
            return new Vigente(vigente.estado().name(), CajaAbierta.de(vigente), vigente.efectivoPropuesto());
        }
    }

    public record Movimiento(Long id, TipoMovimientoCaja tipo, BigDecimal importe, String motivo, LocalDateTime fecha,
            String usuario) {

        static Movimiento de(MovimientoCaja m, String usuario) {
            return new Movimiento(m.getId(), m.getTipo(), m.getImporte(), m.getMotivo(), m.getFecha(), usuario);
        }

        static Movimiento de(MovimientoRegistrado registrado) {
            return de(registrado.movimiento(), registrado.usuario());
        }
    }

    /** Totales, movimientos y quien abrio y cerro. Abierta: contado, diferencia y cierre en null. */
    public record Detalle(Long id, String estado, LocalDateTime fechaApertura, String abiertaPor,
            BigDecimal efectivoInicial, BigDecimal efectivoCierreAnterior, BigDecimal diferenciaApertura,
            LocalDateTime fechaCierre, String cerradaPor, Map<MedioPago, BigDecimal> ventas, BigDecimal totalVentas,
            BigDecimal ingresos, BigDecimal gastos, BigDecimal retiros, BigDecimal efectivoEsperado,
            BigDecimal efectivoContado, BigDecimal diferencia, List<Movimiento> movimientos) {

        static Detalle de(DetalleCaja detalle) {
            Caja c = detalle.caja();
            ResumenCaja r = detalle.resumen();
            Map<Long, String> nombres = detalle.nombres();
            // Cerrada: el esperado es el que quedo guardado al cerrar; abierta: el de ahora.
            BigDecimal esperado = c.estaAbierta() ? r.efectivoEsperado() : c.getEfectivoEsperado();
            return new Detalle(c.getId(), detalle.estado().name(), c.getFechaApertura(),
                    nombres.get(c.getUsuarioAperturaId()), c.getEfectivoInicial(), c.getEfectivoCierreAnterior(),
                    c.getDiferenciaApertura(), c.getFechaCierre(),
                    c.getUsuarioCierreId() == null ? null : nombres.get(c.getUsuarioCierreId()),
                    r.ventasPorMedio(), r.totalVentas(), r.ingresos(), r.gastos(), r.retiros(), esperado,
                    c.getEfectivoContado(), c.getDiferencia(),
                    detalle.movimientos().stream().map(m -> Movimiento.de(m, nombres.get(m.getUsuarioId()))).toList());
        }
    }

    /** Una fila del historial de cajas cerradas. */
    public record Cerrada(Long id, LocalDateTime fechaApertura, LocalDateTime fechaCierre, String abiertaPor,
            String cerradaPor, BigDecimal efectivoInicial, BigDecimal diferenciaApertura, BigDecimal efectivoEsperado,
            BigDecimal efectivoContado, BigDecimal diferencia) {

        static PageResponse<Cerrada> pagina(HistorialCajas historial) {
            Map<Long, String> nombres = historial.nombres();
            return PageResponse.from(historial.pagina(), c -> new Cerrada(c.getId(), c.getFechaApertura(),
                    c.getFechaCierre(), nombres.get(c.getUsuarioAperturaId()), nombres.get(c.getUsuarioCierreId()),
                    c.getEfectivoInicial(), c.getDiferenciaApertura(), c.getEfectivoEsperado(), c.getEfectivoContado(),
                    c.getDiferencia()));
        }
    }
}
