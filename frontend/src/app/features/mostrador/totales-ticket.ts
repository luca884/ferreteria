import { Component, computed, input } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { totalTicket } from './centavos';
import { RenglonTicket, conteoTicket } from './ticket';

/** Totales del ticket, al pie del panel derecho como en GDS: el TOTAL grande y el conteo. Presentacional. */
@Component({
  selector: 'app-totales-ticket',
  imports: [MontoPipe],
  template: `
    <section class="totales" aria-label="Totales">
      <span class="etiqueta-total">Total</span>
      <p class="total" aria-label="Total del ticket">{{ totalCentavos() / 100 | monto }}</p>
      <p class="conteo">
        {{ conteo().articulos }} {{ conteo().articulos === 1 ? 'artículo' : 'artículos' }} ·
        {{ conteo().renglones }} {{ conteo().renglones === 1 ? 'renglón' : 'renglones' }}
      </p>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .totales {
      padding: var(--e-3) var(--e-4) var(--e-4);
      border-radius: var(--radio-lg);
      background: var(--barra);
      color: #fff;
      text-align: right;
    }
    .etiqueta-total {
      display: block;
      color: #fdba74;
      font-size: var(--texto-sm);
      font-weight: 700;
      letter-spacing: 0.12em;
      text-align: left;
      text-transform: uppercase;
    }
    .total {
      margin: 0;
      font-size: clamp(2.25rem, 3.4vw, 3.25rem);
      font-weight: 700;
      line-height: 1.1;
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.02em;
      white-space: nowrap;
    }
    .conteo {
      margin: var(--e-1) 0 0;
      color: var(--barra-texto);
      font-size: var(--texto-sm);
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class TotalesTicket {
  readonly renglones = input.required<readonly RenglonTicket[]>();

  protected readonly totalCentavos = computed(() => totalTicket(this.renglones()));
  protected readonly conteo = computed(() => conteoTicket(this.renglones()));
}
