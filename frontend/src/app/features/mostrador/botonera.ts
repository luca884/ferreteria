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
          class="boton"
          [class]="variantes[atajo.accion] ?? ''"
          [disabled]="!habilitada(atajo.accion)"
          [attr.aria-keyshortcuts]="atajo.tecla"
          (mousedown)="$event.preventDefault()"
          (click)="emitir(atajo.accion)"
        >
          @if (atajo.tecla) {
            <kbd>{{ atajo.tecla }}</kbd>
          }
          {{ atajo.etiqueta }}
        </button>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .botonera {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: var(--e-2);
      padding: var(--e-2);
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
      background: var(--superficie);
      box-shadow: var(--sombra-1);
    }
    .boton {
      justify-content: flex-start;
      gap: var(--e-3);
      min-height: 52px;
      padding: 8px 14px;
      font-size: 1.0625rem;
    }
    kbd {
      min-width: 2.6em;
      font-size: 0.85rem;
    }
    .boton.primario kbd {
      border-color: rgb(255 255 255 / 0.45);
      background: rgb(255 255 255 / 0.16);
      color: var(--acento-texto);
    }
    .boton:disabled kbd {
      border-color: var(--borde);
      background: var(--superficie-2);
      color: var(--texto-tenue);
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
