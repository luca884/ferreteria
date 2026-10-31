package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.common.PageResponse;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.MovimientoCaja;
import com.ferreteria.ventas.model.ResumenCaja;
import com.ferreteria.ventas.model.TipoMovimientoCaja;
import com.ferreteria.ventas.model.VentasAnuladas;
import com.ferreteria.ventas.service.CajaVigente;
import com.ferreteria.ventas.service.DetalleCaja;
import com.ferreteria.ventas.service.EgresoSinEfectivoException;
import com.ferreteria.ventas.service.HistorialCajas;
import com.ferreteria.ventas.service.MovimientoRegistrado;

import java.math.BigDecimal;
import java.time.Instant;
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

    /**
     * El rechazo de un gasto o retiro mayor al efectivo (GDS-36): los campos de {@code ApiError}, el motivo y el detalle.
     * Las cifras del detalle solo van al Dueño; al Vendedor no se serializan (cierre ciego).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EgresoRechazado(int status, String message, Instant timestamp, String motivo, Efectivo efectivo) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Efectivo(boolean puedeConfirmar, BigDecimal disponible, BigDecimal importe, BigDecimal faltante) {}

        static EgresoRechazado de(EgresoSinEfectivoException e) {
            return new EgresoRechazado(422, e.getMessage(), Instant.now(), "EFECTIVO_INSUFICIENTE",
                    new Efectivo(e.puedeConfirmar(), e.disponible(), e.importe(), e.faltante()));
        }
    }

    /**
     * Totales, movimientos y quien abrio y cerro. Abierta: contado, diferencia y cierre en null. {@code cobranzas} son los
     * recibos de cuenta corriente por medio (CC-053): la de efectivo suma al esperado, la transferencia es informativa.
     * {@code anulaciones} son los recibos anulados en ESTA caja (CC-081): la de efectivo resta del esperado, la de
     * transferencia es informativa; las cobranzas siguen brutas. {@code ventasAnuladas} son las ventas de esta caja con
     * nota de credito: informativo, porque {@code ventas} y {@code totalVentas} son brutos (hallazgos T1).
     */
    public record Detalle(Long id, String estado, LocalDateTime fechaApertura, String abiertaPor,
            BigDecimal efectivoInicial, BigDecimal efectivoCierreAnterior, BigDecimal diferenciaApertura,
            LocalDateTime fechaCierre, String cerradaPor, Map<MedioPago, BigDecimal> ventas, BigDecimal totalVentas,
            VentasAnuladas ventasAnuladas, Map<MedioPago, BigDecimal> cobranzas, BigDecimal totalCobranzas,
            Map<MedioPago, BigDecimal> anulaciones, BigDecimal totalAnulaciones, BigDecimal ingresos, BigDecimal gastos,
            BigDecimal retiros, BigDecimal efectivoEsperado, BigDecimal efectivoContado, BigDecimal diferencia,
            List<Movimiento> movimientos) {

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
                    r.ventasPorMedio(), r.totalVentas(), detalle.ventasAnuladas(), r.cobranzasPorMedio(), r.totalCobranzas(), r.anulacionesPorMedio(), r.totalAnulaciones(), r.ingresos(), r.gastos(),
                    r.retiros(), esperado,
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
