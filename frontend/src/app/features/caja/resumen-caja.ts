import { ChangeDetectorRef, Component, computed, inject, input, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { emitidoEl } from '../../shared/emitido';
import { MEDIOS_PAGO, MEDIO_PAGO_LABELS } from '../mostrador/venta.models';
import { DetalleCaja, TIPO_MOVIMIENTO_LABELS, fechaHoraLocal, textoDiferencia } from './caja.models';

/**
 * Totales de una caja: ventas por medio de pago, ingresos, gastos, retiros, efectivo esperado y movimientos. Presentacional.
 * Con la caja abierta no trae contado ni diferencia; cerrada, sí. «Imprimir» saca la misma hoja que se ve, con la ferretería
 * y cuándo se emitió arriba; al imprimir solo sale `.caja-imprimible` (reglas `@media print` en styles.css).
 */
@Component({
  selector: 'app-resumen-caja',
  imports: [MontoPipe],
  templateUrl: './resumen-caja.html',
  styleUrl: './resumen-caja.css',
})
export class ResumenCaja {
  private readonly auth = inject(AuthService);
  private readonly vista = inject(ChangeDetectorRef);

  readonly detalle = input.required<DetalleCaja>();
  /** En false no lleva su botón «Imprimir»: lo pone quien lo muestra (el pie del pop-up del historial) y llama a `imprimir()`. */
  readonly conImprimir = input(true);

  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);
  protected readonly emitido = signal(emitidoEl(new Date()));

  protected readonly medios = MEDIOS_PAGO;
  protected readonly medioLabels = MEDIO_PAGO_LABELS;
  protected readonly tipoLabels = TIPO_MOVIMIENTO_LABELS;
  protected readonly fechaHora = fechaHoraLocal;

  /** La hora de «Emitido el» es la de imprimir, no la de abrir la pantalla: se renueva antes de abrir el diálogo. */
  imprimir(): void {
    this.emitido.set(emitidoEl(new Date()));
    this.vista.detectChanges();
    window.print();
  }

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
