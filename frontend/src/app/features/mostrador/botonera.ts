import { Component, input, output } from '@angular/core';
import { AccionMostrador, Atajo, atajosDeBotonera } from './mostrador.atajos';

/** Acciones que emite la botonera: todas las del mapa menos Esc. */
export type AccionBotonera = Exclude<AccionMostrador, 'cerrar'>;

/**
 * Botonera del mostrador. Presentacional: las teclas y etiquetas salen de `ATAJOS` y emite la misma acción
 * que la tecla. Los botones no le sacan el foco a la caja de búsqueda.
 */
@Component({
  selector: 'app-botonera',
  template: `
    <div class="botonera" role="toolbar" aria-label="Acciones del mostrador">
      @for (atajo of atajos(); track atajo.accion) {
        <button
          type="button"
          class="accion-barra"
          [class]="variantes[atajo.accion] ?? ''"
          [disabled]="!habilitada(atajo.accion)"
          [attr.aria-keyshortcuts]="atajo.tecla"
          (mousedown)="$event.preventDefault()"
          (click)="emitir(atajo.accion)"
        >
          @if (atajo.tecla) {
            <kbd aria-hidden="true">{{ atajo.tecla }}</kbd>
          }
          {{ atajo.etiqueta }}
        </button>
      }
    </div>
  `,
  /* Los botones usan la clase global .accion-barra (styles.css); acá solo la fila y el separador. */
  styles: `
    :host {
      display: block;
    }
    .botonera {
      display: flex;
      align-items: stretch;
      gap: 6px;
    }
    /* Vaciar queda aparte de las acciones de la venta, detrás de una línea. */
    .peligro {
      position: relative;
      margin-left: 13px;
    }
    .peligro::before {
      content: '';
      position: absolute;
      top: 4px;
      bottom: 4px;
      left: -11px;
      width: 1px;
      background: var(--borde);
    }
  `,
})
export class Botonera {
  /** Cobrar es la acción principal (como «Aceptar» en GDS); Vaciar se marca como peligrosa. */
  protected readonly variantes: Partial<Record<AccionMostrador, string>> = {
    cobrar: 'primario',
    vaciar: 'peligro',
  };
  readonly atajos = input<readonly Atajo[]>(atajosDeBotonera());
  /** Acciones apagadas; las que no figuran están habilitadas. */
  readonly habilitadas = input<Partial<Record<AccionBotonera, boolean>>>({});
  readonly accion = output<AccionBotonera>();

  protected habilitada(accion: AccionMostrador): boolean {
    return this.habilitadas()[accion as AccionBotonera] !== false;
  }

  protected emitir(accion: AccionMostrador): void {
    if (accion === 'cerrar') return;
    this.accion.emit(accion);
  }
}
