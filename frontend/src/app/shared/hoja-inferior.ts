import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { TrampaFoco } from './trampa-foco';

let siguienteId = 0;

/**
 * La hoja inferior del celular (S7 de `odd/tasks/movil-sectores.md`): donde van las acciones de un renglón o de todo el
 * ticket («Más acciones»), en vez de una fila de botones que no entra. Sube desde abajo sobre la pantalla oscurecida,
 * con un asa, el título y, si hay, un subtítulo (el código del artículo, el número del comprobante).
 *
 *   <app-hoja-inferior titulo="Tornillo 6mm" subtitulo="ART-001" (cerrar)="menu.set(false)">
 *     <button type="button" class="accion-hoja">Editar</button>
 *     <button type="button" class="accion-hoja peligro">Dar de baja</button>
 *   </app-hoja-inferior>
 *
 * Cada acción es un `.accion-hoja` (52px de alto, renglón a todo el ancho; `peligro` en rojo, `aria-disabled="true"`
 * apagada: la explica `shared/motivo-de-accion.ts`). Al abrir, el foco entra en la hoja; Esc y un toque en el velo
 * cierran; si hay otro diálogo encima (una confirmación) Esc es de ese. Al cerrar, `appTrampaFoco` devuelve el foco al
 * botón desde el que se abrió. Los estilos están en styles.css («Hoja inferior») porque alcanzan al contenido
 * proyectado. Desde 640 px se ve como una tarjeta centrada: sólo se usa en el celular, pero no se rompe en otro lado.
 */
@Component({
  selector: 'app-hoja-inferior',
  imports: [TrampaFoco],
  // En el documento y no en la hoja: si el botón que tenía el foco desaparece, el foco cae al body y Esc seguiría andando.
  host: { '(document:keydown)': 'alTeclear($event)' },
  template: `
    <!-- El mousedown sobre el velo no mueve el foco al body: si no, Esc (que escucha el documento) no sabría de qué hoja es. -->
    <div class="dialogo-fondo hoja-inferior-fondo" (mousedown)="retenerFoco($event)" (click)="alTocarElVelo($event)">
      <div
        #hoja
        class="hoja-inferior"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        appTrampaFoco
        [attr.aria-labelledby]="id"
      >
        <span class="hoja-inferior-asa" aria-hidden="true"></span>
        <h2 [id]="id" class="hoja-inferior-titulo">{{ titulo() }}</h2>
        @if (subtitulo()) {
          <p class="hoja-inferior-subtitulo">{{ subtitulo() }}</p>
        }
        <ng-content />
      </div>
    </div>
  `,
})
export class HojaInferior {
  readonly titulo = input.required<string>();
  readonly subtitulo = input('');
  readonly cerrar = output<void>();

  protected readonly id = `hoja-inferior-${++siguienteId}`;
  private readonly hoja = viewChild.required<ElementRef<HTMLElement>>('hoja');
  private readonly trampa = viewChild.required(TrampaFoco);

  constructor() {
    afterNextRender(() => this.hoja().nativeElement.focus());
  }

  /** Un toque en el velo no debe sacar el foco de la hoja (iría al body). */
  protected retenerFoco(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) evento.preventDefault();
  }

  protected alTocarElVelo(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) this.cerrar.emit();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'Escape' || evento.defaultPrevented) return;
    // Con otra confirmación abierta encima, Esc es de esa aunque el foco se le haya caído al body.
    if (!this.trampa().esLaDeArriba()) return;
    // Un Esc que viene de otro diálogo, adentro o al lado, es de ese aunque no corte la propagación.
    const desde = evento.target instanceof Element ? evento.target.closest('[aria-modal="true"]') : null;
    if (desde !== null && desde !== this.hoja().nativeElement) return;
    evento.preventDefault();
    evento.stopPropagation();
    this.cerrar.emit();
  }
}
