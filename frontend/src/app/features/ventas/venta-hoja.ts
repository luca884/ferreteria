import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { cantidadConUnidad, porUnidad } from '../../core/formato/unidad';
import { MontoPipe, RestadoPipe } from '../../core/formato/monto';
import { porcentajeDescuentoGeneral } from '../../shared/descuento-general';
import { conOrigen } from '../../shared/documento-por-ruta';
import { fechaCompletaLocal } from '../clientes/cuenta/cobranza.models';
import { VentaConsultada, clienteDeVenta, esNotaDeCredito } from './ventas.models';

/**
 * El contenido de una venta: datos, renglones, totales y pagos. Presentacional: lo usan el detalle de la consulta y la
 * venta imprimible, así lo que se ve y lo que se imprime dicen lo mismo.
 */
@Component({
  selector: 'app-venta-hoja',
  imports: [DecimalPipe, MontoPipe, RestadoPipe, RouterLink],
  templateUrl: './venta-hoja.html',
  styleUrl: './venta-hoja.css',
})
export class VentaHoja {
  readonly venta = input.required<VentaConsultada>();
  /** En el detalle, el origen es un link a su pop-up (informacion-y-links, S2); en la hoja para imprimir, texto. */
  readonly conLinks = input(false);

  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly porUnidad = porUnidad;

  /** El `state` del link al presupuesto o al remito: al cerrarlo se vuelve a esta venta (bugs-relevamiento-celular, S7). */
  protected readonly estadoDelLink = computed(() => conOrigen(`/ventas/${this.venta().ventaId}`));
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
  /**
   * De dónde salió la venta, si no se armó en el mostrador: «Salió del remito N.º 2», con la ruta de su pop-up. Sin el
   * número (un servidor anterior) dice «Salió de un remito», sin link.
   */
  protected readonly origen = computed<{ texto: string; numero: string | null; ruta: string | null } | null>(() => {
    const v = this.venta();
    if (v.presupuestoId != null) {
      return v.presupuestoNumero != null
        ? { texto: 'Salió del ', numero: `presupuesto N.º ${v.presupuestoNumero}`, ruta: `/presupuestos/${v.presupuestoId}` }
        : { texto: 'Salió de un presupuesto', numero: null, ruta: null };
    }
    if (v.remitoId != null) {
      return v.remitoNumero != null
        ? { texto: 'Salió del ', numero: `remito N.º ${v.remitoNumero}`, ruta: `/remitos/${v.remitoId}` }
        : { texto: 'Salió de un remito', numero: null, ruta: null };
    }
    return null;
  });
  /** El caption de los renglones, como el comprobante: «de la nota de crédito» o «de la anulación» (la interna). */
  protected readonly renglonesDe = computed(() => {
    if (!this.esNota()) return 'Renglones de la venta';
    return this.venta().fiscal ? 'Renglones de la nota de crédito' : 'Renglones de la anulación';
  });
}
