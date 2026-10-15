import { Component, computed, input } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { totalTicket } from './centavos';
import { RenglonTicket } from './ticket';

/**
 * Totales del ticket, al pie del panel derecho como en GDS: el TOTAL grande y la cantidad de renglones.
 * No se cuentan «artículos»: 2 unidades + 2,5 m daría un número que no significa nada. Presentacional.
 */
@Component({
  selector: 'app-totales-ticket',
  imports: [MontoPipe],
  template: `
    <section class="totales" aria-label="Totales">
      <div class="cabecera">
        <span id="totales-etiqueta" class="etiqueta-total">Total</span>
        <p class="conteo">
          {{ renglones().length }} {{ renglones().length === 1 ? 'renglón' : 'renglones' }}
        </p>
      </div>
      <!-- <output>: un resultado calculado que admite nombre (un aria-label en un <p> se ignora). -->
      <output class="total" aria-labelledby="totales-etiqueta">{{ totalCentavos() / 100 | monto }}</output>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .totales {
      padding: var(--e-4) 18px;
      border-radius: var(--radio-lg);
      background: var(--acento-profundo);
      color: var(--acento-texto);
    }
    .cabecera {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--e-3);
      color: var(--profundo-texto-tenue);
      font-size: var(--texto-sm);
    }
    .etiqueta-total {
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }
    .conteo {
      margin: 0;
      font-variant-numeric: tabular-nums;
    }
    .total {
      display: block;
      margin: var(--e-1) 0 0;
      font-size: clamp(2rem, 2.9vw, 2.625rem);
      font-weight: 700;
      line-height: 1.1;
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.03em;
      text-align: right;
      white-space: nowrap;
    }
  `,
})
export class TotalesTicket {
  readonly renglones = input.required<readonly RenglonTicket[]>();

  protected readonly totalCentavos = computed(() => totalTicket(this.renglones()));
}
