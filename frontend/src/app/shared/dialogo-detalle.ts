import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { TrampaFoco } from './trampa-foco';

let siguienteId = 0;

/**
 * El pop-up grande donde se abre el detalle de una venta, presupuesto, remito, caja o compra (detalle-en-popup),
 * encima de la lista oscurecida. Presentacional: título y ✕ arriba, el contenido proyectado en un cuerpo con scroll
 * propio y un pie fijo con las acciones: la destructiva (`accion-peligro`, Anular) sola a la izquierda; a la derecha
 * Cerrar y las demás (`accion`, la principal en `primario`).
 *
 * Al abrir, el foco va a la ✕; Esc cierra; Enter no hace nada por su cuenta (hay acciones como Anular, y un Enter
 * en un botón ya es su clic). Si hay otro diálogo abierto adentro, Esc es de ese. Al cerrar, `appTrampaFoco` devuelve
 * el foco a la fila desde la que se abrió.
 */
@Component({
  selector: 'app-dialogo-detalle',
  imports: [TrampaFoco],
  template: `
    <!-- El mousedown sobre el velo no mueve el foco al body: si no, Esc (que escucha el pop-up) dejaría de andar. -->
    <div class="dialogo-fondo" (mousedown)="retenerFoco($event)">
      <div
        #dialogo
        class="dialogo dialogo-detalle"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        appTrampaFoco
        [attr.aria-labelledby]="id"
        (keydown)="alTeclear($event)"
      >
        <header class="dialogo-detalle-cabecera">
          <h2 [id]="id">{{ titulo() }}</h2>
          <button #equis type="button" class="dialogo-detalle-equis" aria-label="Cerrar" (click)="cerrar.emit()">
            <span aria-hidden="true">✕</span>
          </button>
        </header>
        <div class="dialogo-detalle-cuerpo">
          <ng-content />
        </div>
        <footer class="dialogo-acciones dialogo-detalle-pie">
          <div class="pie-peligro">
            <ng-content select="[accion-peligro]" />
          </div>
          <div class="pie-acciones">
            <button
              type="button"
              class="cerrar secundario"
              data-tecla="Esc"
              aria-keyshortcuts="Escape"
              (click)="cerrar.emit()"
            >
              Cerrar
            </button>
            <ng-content select="[accion]" />
          </div>
        </footer>
      </div>
    </div>
  `,
})
export class DialogoDetalle {
  readonly titulo = input.required<string>();
  readonly cerrar = output<void>();

  protected readonly id = `dialogo-detalle-${++siguienteId}`;
  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');
  private readonly equis = viewChild.required<ElementRef<HTMLButtonElement>>('equis');

  constructor() {
    afterNextRender(() => this.equis().nativeElement.focus());
  }

  /** Un clic en el velo no debe sacar el foco del pop-up (iría al body y Esc dejaría de llegarle). */
  protected retenerFoco(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) evento.preventDefault();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'Escape') return;
    // Un Esc que viene de otro diálogo abierto adentro (Imprimir) es de ese, aunque no corte la propagación.
    const desde = evento.target instanceof Element ? evento.target.closest('[aria-modal="true"]') : null;
    if (desde !== this.dialogo().nativeElement) return;
    evento.preventDefault();
    evento.stopPropagation();
    this.cerrar.emit();
  }
}
