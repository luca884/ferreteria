import { Component, computed, input } from '@angular/core';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { MEDIOS_PAGO, MEDIO_PAGO_LABELS } from '../mostrador/venta.models';
import { DetalleCaja, TIPO_MOVIMIENTO_LABELS, fechaHoraLocal, textoDiferencia } from './caja.models';

/**
 * Totales de una caja: ventas por medio de pago, ingresos, gastos, retiros, efectivo esperado y movimientos. Presentacional.
 * Con la caja abierta no trae contado ni diferencia; cerrada, sí.
 */
@Component({
  selector: 'app-resumen-caja',
  imports: [MontoPipe],
  templateUrl: './resumen-caja.html',
  styleUrl: './resumen-caja.css',
})
export class ResumenCaja {
  readonly detalle = input.required<DetalleCaja>();

  protected readonly medios = MEDIOS_PAGO;
  protected readonly medioLabels = MEDIO_PAGO_LABELS;
  protected readonly tipoLabels = TIPO_MOVIMIENTO_LABELS;
  protected readonly fechaHora = fechaHoraLocal;

  /** Una anulación se muestra restando: «−$ 700,00»; en 0 no lleva signo. */
  protected restado(importe: number): string {
    return importe > 0 ? `−${formatearMonto(importe)}` : formatearMonto(importe);
  }

  protected readonly textoDiferencia = computed(() => {
    const diferencia = this.detalle().diferencia;
    return diferencia === null ? null : textoDiferencia(diferencia);
  });
  protected readonly diferenciaApertura = computed(() => {
    const diferencia = this.detalle().diferenciaApertura;
    return diferencia === null || diferencia === 0 ? null : textoDiferencia(diferencia);
  });
}
