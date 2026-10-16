import { Component, computed, input, output } from '@angular/core';

export interface HojaDeLista {
  indice: number;
  nombre: string;
}

/**
 * Las hojas del archivo con una casilla cada una: cuáles se importan (presentacional). Las propuestas vienen marcadas
 * y una hoja nueva aparece sin marcar. Emite la lista completa de las elegidas, en el orden del archivo.
 */
@Component({
  selector: 'app-hojas-lista',
  template: `
    <fieldset>
      <legend>Hojas que se importan</legend>
      <div class="acciones">
        <button type="button" class="chico" (click)="marcarTodas()">Marcar todas</button>
        <button type="button" class="chico" (click)="desmarcarTodas()">Desmarcar todas</button>
        <span class="hojas-cuenta ayuda" aria-live="polite">
          {{ elegidas().length }} de {{ hojas().length }} hojas marcadas
        </span>
      </div>
      <ul class="hojas">
        @for (hoja of hojas(); track hoja.indice) {
          <li>
            <input
              type="checkbox"
              [id]="'hoja-' + hoja.indice"
              [checked]="estaElegida(hoja.indice)"
              (change)="alternar(hoja.indice)"
            />
            <label [for]="'hoja-' + hoja.indice">{{ hoja.nombre }}</label>
          </li>
        }
      </ul>
    </fieldset>
  `,
  styles: `
    :host { display: block; }
    fieldset {
      min-width: 0;
      margin: 0 0 var(--e-4);
      padding: var(--e-3) var(--e-4);
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
    }
    legend { padding: 0 var(--e-2); color: var(--texto-suave); font-size: var(--texto-sm); font-weight: 700; }
    .acciones { display: flex; flex-wrap: wrap; align-items: center; gap: var(--e-2); margin-bottom: var(--e-2); }
    .hojas-cuenta { margin: 0 0 0 var(--e-2); }
    .hojas {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: var(--e-1) var(--e-3);
      max-height: 220px;
      margin: 0;
      padding: 0;
      overflow-y: auto;
      list-style: none;
    }
    .hojas li { display: flex; align-items: center; gap: var(--e-2); }
    .hojas label { font-weight: 500; color: var(--texto); cursor: pointer; }
  `,
})
export class HojasLista {
  readonly hojas = input.required<HojaDeLista[]>();
  readonly elegidas = input.required<readonly number[]>();
  readonly elegidasCambian = output<number[]>();

  private readonly marcadas = computed(() => new Set(this.elegidas()));

  protected estaElegida(indice: number): boolean {
    return this.marcadas().has(indice);
  }

  protected alternar(indice: number): void {
    const nuevas = new Set(this.marcadas());
    if (!nuevas.delete(indice)) nuevas.add(indice);
    this.elegidasCambian.emit(this.enOrden(nuevas));
  }

  protected marcarTodas(): void {
    this.elegidasCambian.emit(this.hojas().map((h) => h.indice));
  }

  protected desmarcarTodas(): void {
    this.elegidasCambian.emit([]);
  }

  private enOrden(indices: ReadonlySet<number>): number[] {
    return this.hojas()
      .map((h) => h.indice)
      .filter((i) => indices.has(i));
  }
}
