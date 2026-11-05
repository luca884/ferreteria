import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, afterRenderEffect, input, output, viewChildren } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { Unidad } from '../articulos/articulo.models';
import { totalRenglon } from './centavos';
import { RenglonTicket, sinStock } from './ticket';

/** Abreviaturas del ticket: «2,5 m», «1,25 kg». La unidad por pieza no lleva sufijo. */
const SUFIJO_UNIDAD: Record<Unidad, string> = {
  UNIDAD: '',
  METRO: ' m',
  KILO: ' kg',
  LITRO: ' l',
};

/**
 * Grilla del ticket en curso (Cant. | Código | Descripción | P. unit. | Total), con el renglón elegido
 * resaltado. El total va aparte, en `TotalesTicket`. Presentacional: no muestra costo ni margen.
 */
@Component({
  selector: 'app-ticket-lista',
  imports: [DecimalPipe, MontoPipe],
  templateUrl: './ticket-lista.html',
  styleUrl: './ticket-lista.css',
})
export class TicketLista {
  readonly renglones = input.required<readonly RenglonTicket[]>();
  readonly elegido = input<number | null>(null);
  /** Con la lista de resultados encima, el aviso de ticket vacío asomaría por debajo: se oculta. */
  readonly conMensajeVacio = input(true);
  /** Clic en un renglón: su índice, para elegirlo (F3, F7 y F10 actúan sobre el elegido). */
  readonly elegir = output<number>();

  protected readonly sufijo = SUFIJO_UNIDAD;
  protected readonly sinStock = sinStock;
  protected readonly totalRenglon = totalRenglon;

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
