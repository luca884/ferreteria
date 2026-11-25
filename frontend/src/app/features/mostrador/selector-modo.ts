import { Component, input, output } from '@angular/core';

/** Qué se está armando en el mostrador: una venta (se cobra), un presupuesto o un remito. El ticket es el mismo en los tres. */
export type ModoMostrador = 'venta' | 'presupuesto' | 'remito';

const MODOS: readonly { modo: ModoMostrador; etiqueta: string }[] = [
  { modo: 'venta', etiqueta: 'Venta' },
  { modo: 'presupuesto', etiqueta: 'Presupuesto' },
  { modo: 'remito', etiqueta: 'Remito' },
];

/**
 * Selector de modo del mostrador. Presentacional: muestra los tres modos y emite el que se toca. Como los botones de la
 * botonera, no le sacan el foco a la caja de búsqueda: el lector de código sigue escribiendo ahí.
 */
@Component({
  selector: 'app-selector-modo',
  template: `
    <div class="modos" role="group" aria-label="Qué querés hacer">
      @for (m of modos; track m.modo) {
        <button
          type="button"
          [attr.aria-pressed]="modo() === m.modo"
          (mousedown)="$event.preventDefault()"
          (click)="elegir.emit(m.modo)"
        >
          {{ m.etiqueta }}
        </button>
      }
    </div>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    .modos {
      display: inline-flex;
      gap: 4px;
      padding: 4px;
      background: var(--superficie-3);
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
    }
    button {
      min-height: var(--alto-control);
      padding: 6px 22px;
      border: 0;
      border-radius: var(--radio);
      background: transparent;
      color: var(--texto-suave);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }
    button[aria-pressed='true'] {
      background: var(--superficie);
      color: var(--acento-fuerte);
      box-shadow: 0 1px 3px rgb(15 23 42 / 15%);
    }
    @media screen and (max-width: 639.98px) {
      :host,
      .modos {
        display: flex;
      }
      button {
        flex: 1;
        padding-inline: 8px;
        min-height: var(--alto-tactil);
      }
    }
  `,
})
export class SelectorModo {
  readonly modo = input.required<ModoMostrador>();
  readonly elegir = output<ModoMostrador>();
  protected readonly modos = MODOS;
}
