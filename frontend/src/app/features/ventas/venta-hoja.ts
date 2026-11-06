import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { porcentajeDescuentoGeneral } from '../../shared/descuento-general';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { VentaConsultada, clienteDeVenta, esNotaDeCredito } from './ventas.models';

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
  /** La nota de crédito (CU-08) dice quién la hizo, no quién cobró. */
  protected readonly esNota = computed(() => esNotaDeCredito(this.venta()));
  protected readonly porcentajeGeneral = computed(() => porcentajeDescuentoGeneral(this.venta()));
  /**
   * Los renglones como suman a la vista (QA B-2 y C): «Dto.» es el efectivo, el del renglón con el global encima
   * (5 % y 10 % dan 14,5 %), y el total va sin el recargo de la forma de pago, que se muestra abajo.
   */
  protected readonly renglones = computed(() => {
    const global = this.venta().descuentoPorcentaje;
    return this.venta().renglones.map((r) => ({
      ...r,
      descuentoEfectivo: 100 - ((100 - r.descuentoPorcentaje) * (100 - global)) / 100,
      totalSinRecargo: Math.round((r.total - (r.recargo ?? 0)) * 100) / 100,
    }));
  });
  /** De dónde salió la venta, si no se armó en el mostrador. */
  protected readonly origen = computed(() => {
    const v = this.venta();
    if (v.presupuestoId !== undefined) return 'Salió de un presupuesto';
    if (v.remitoId !== undefined) return 'Salió de un remito';
    return null;
  });
}
