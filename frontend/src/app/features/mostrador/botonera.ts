import { Component, input, output } from '@angular/core';
import { AccionMostrador, Atajo, atajosDeBotonera } from './mostrador.atajos';

/** «Vaciar» no tiene tecla (se evita vaciar por error): solo existe como botón. */
export type AccionBotonera = Exclude<AccionMostrador, 'cerrar'> | 'vaciar';

/**
 * Botonera del mostrador. Presentacional: las teclas y etiquetas salen de `ATAJOS` y emite la misma acción
 * que la tecla. Los botones no le sacan el foco a la caja de búsqueda.
 */
@Component({
  selector: 'app-botonera',
  template: `
    <div class="botonera" role="toolbar" aria-label="Acciones del mostrador">
      @for (atajo of atajos(); track atajo.tecla) {
        <button
          type="button"
          class="boton"
          [disabled]="!habilitada(atajo.accion)"
          (mousedown)="$event.preventDefault()"
          (click)="emitir(atajo.accion)"
        >
          <kbd>{{ atajo.tecla }}</kbd> {{ atajo.etiqueta }}
        </button>
      }
      <button
        type="button"
        class="boton"
        [disabled]="!habilitada('vaciar')"
        (mousedown)="$event.preventDefault()"
        (click)="emitir('vaciar')"
      >
        Vaciar
      </button>
    </div>
  `,
  styles: `
    .botonera {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .boton {
      padding: 8px 14px;
      border: 1px solid var(--borde);
      border-radius: 6px;
      background: var(--superficie);
      font: inherit;
      cursor: pointer;
    }
    .boton:disabled {
      opacity: 0.5;
      cursor: default;
    }
    kbd {
      font-weight: 700;
      color: var(--texto-suave);
    }
  `,
})
export class Botonera {
  readonly atajos = input<readonly Atajo[]>(atajosDeBotonera());
  /** Acciones apagadas; las que no figuran están habilitadas. */
  readonly habilitadas = input<Partial<Record<AccionBotonera, boolean>>>({});
  readonly accion = output<AccionBotonera>();

  protected habilitada(accion: AccionBotonera | AccionMostrador): boolean {
    return this.habilitadas()[accion as AccionBotonera] !== false;
  }

  protected emitir(accion: AccionMostrador | 'vaciar'): void {
    if (accion === 'cerrar') return;
    this.accion.emit(accion);
  }
}
