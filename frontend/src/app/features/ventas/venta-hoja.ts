import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { VentaConsultada, clienteDeVenta } from './ventas.models';

/**
 * El contenido de una venta: datos, renglones, totales y pagos. Presentacional: lo usan el detalle de la consulta y la
 * venta imprimible, así lo que se ve y lo que se imprime dicen lo mismo.
 */
@Component({
  selector: 'app-venta-hoja',
  imports: [DecimalPipe, MontoPipe],
  templateUrl: './venta-hoja.html',
  styleUrl: './venta-hoja.css',
})
export class VentaHoja {
  readonly venta = input.required<VentaConsultada>();

  protected readonly fecha = computed(() => fechaCompletaLocal(this.venta().fecha));
  protected readonly cliente = computed(() => clienteDeVenta(this.venta().clienteNombre));
  protected readonly pagos = computed(() => this.venta().pagos ?? []);
  /** De dónde salió la venta, si no se armó en el mostrador. */
  protected readonly origen = computed(() => {
    const v = this.venta();
    if (v.presupuestoId !== undefined) return 'Salió de un presupuesto';
    if (v.remitoId !== undefined) return 'Salió de un remito';
    return null;
  });
}
