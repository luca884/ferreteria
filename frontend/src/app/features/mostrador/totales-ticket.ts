import { Component, computed, input } from '@angular/core';
import { MontoPipe, RestadoPipe } from '../../core/formato/monto';
import { subtotalTicket, totalTicket } from './centavos';
import { RenglonTicket } from './ticket';

/**
 * Totales del ticket, al pie del panel derecho como en GDS: el TOTAL grande y la cantidad de renglones.
 * No se cuentan «artículos»: 2 unidades + 2,5 m daría un número que no significa nada. Presentacional.
 */
@Component({
  selector: 'app-totales-ticket',
  imports: [MontoPipe, RestadoPipe],
  template: `
    <section class="totales" aria-label="Totales">
      <div class="cabecera">
        <span id="totales-etiqueta" class="etiqueta-total">Total</span>
        <p class="conteo">
          {{ renglones().length }} {{ renglones().length === 1 ? 'renglón' : 'renglones' }}
        </p>
      </div>
      @if (descuentoCentavos() > 0) {
        <p class="linea subtotal">Subtotal <span>{{ subtotalCentavos() / 100 | monto }}</span></p>
        <p class="linea descuento">Descuento <span>{{ descuentoCentavos() / 100 | restado }}</span></p>
      }
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
    .linea {
      display: flex;
      justify-content: space-between;
      gap: var(--e-3);
      margin: var(--e-1) 0 0;
      color: var(--profundo-texto-tenue);
      font-size: var(--texto-sm);
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
    /*
     * Debajo de escritorio el TOTAL es una barra fija abajo (S6, S12d), con el Cobrar de la botonera fijado a su derecha:
     * cobrar deja de depender de llegar al pie del panel. El alto y el ancho del botón los define el mostrador.
     */
    @media screen and (max-width: 1023.98px) {
      .totales {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--e-3);
        height: var(--alto-barra-total, 64px);
        padding: 0 calc(var(--ancho-cobrar, 132px) + var(--e-3)) 0 var(--e-3);
        border-radius: 0;
        box-shadow: 0 -2px 12px rgb(15 23 42 / 0.28);
      }
      /* «Total» y el conteo en columna: entran en el alto de la barra. */
      .cabecera {
        flex-direction: column;
        align-items: flex-start;
        gap: 0;
      }
      .total {
        margin: 0;
        /* Un total largo entra igual en el medio de la barra que le queda al lado de Cobrar. */
        font-size: clamp(1.375rem, 5.5vw, 1.75rem);
      }
      /* Subtotal y descuento no entran en la barra: el descuento se ve en cada renglón y el TOTAL ya viene con él. */
      .linea {
        display: none;
      }
    }
  `,
})
export class TotalesTicket {
  readonly renglones = input.required<readonly RenglonTicket[]>();
  /** Descuento global del ticket en centésimas de punto porcentual; se aplica a cada renglón. */
  readonly descuentoGlobalBp = input(0);

  protected readonly totalCentavos = computed(() => totalTicket(this.renglones(), this.descuentoGlobalBp()));
  protected readonly subtotalCentavos = computed(() => subtotalTicket(this.renglones()));
  protected readonly descuentoCentavos = computed(() => this.subtotalCentavos() - this.totalCentavos());
}
