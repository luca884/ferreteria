import { DecimalPipe } from '@angular/common';
import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  output,
  viewChildren,
} from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { Unidad } from '../articulos/articulo.models';
import { totalRenglon, totalTicket } from './centavos';
import { RenglonTicket, sinStock } from './ticket';

/** Abreviaturas del ticket: «2,5 m», «1,25 kg». La unidad por pieza no lleva sufijo. */
const SUFIJO_UNIDAD: Record<Unidad, string> = {
  UNIDAD: '',
  METRO: ' m',
  KILO: ' kg',
  LITRO: ' l',
};

/** Ticket en curso: renglones, el elegido resaltado y el total. Presentacional: no muestra costo ni margen. */
@Component({
  selector: 'app-ticket-lista',
  imports: [DecimalPipe, MontoPipe],
  templateUrl: './ticket-lista.html',
  styleUrl: './ticket-lista.css',
})
export class TicketLista {
  readonly renglones = input.required<readonly RenglonTicket[]>();
  readonly elegido = input<number | null>(null);
  readonly vaciar = output<void>();

  protected readonly sufijo = SUFIJO_UNIDAD;
  protected readonly sinStock = sinStock;
  protected readonly totalRenglon = totalRenglon;
  protected readonly totalCentavos = computed(() => totalTicket(this.renglones()));

  private readonly filas = viewChildren<ElementRef<HTMLElement>>('fila');

  constructor() {
    afterRenderEffect(() => {
      const indice = this.elegido();
      if (indice === null) return;
      // jsdom no implementa scrollIntoView.
      this.filas()[indice]?.nativeElement.scrollIntoView?.({ block: 'nearest' });
    });
  }
}
