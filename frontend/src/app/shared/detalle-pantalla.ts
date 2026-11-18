import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { TrampaFoco } from './trampa-foco';

let siguienteId = 0;

/**
 * El detalle de un documento a pantalla completa en el celular (S7 de `odd/tasks/movil-sectores.md`): una barra con la
 * flecha «Volver» y el título, el contenido proyectado con scroll propio y, si hay, un pie fijo con las acciones
 * principales. Es la versión del celular del pop-up `app-dialogo-detalle`: lo usa quien ya sabe que está en el celular
 * (`esCelular()`) y deja el pop-up para el escritorio y la tablet.
 *
 *   <app-detalle-pantalla titulo="Venta N.º 5" (cerrar)="cerrar()">
 *     <button mas type="button" aria-label="Más opciones">…</button>   ← opcional, a la derecha de la barra
 *     …el contenido…
 *     <button pie type="button" class="primario">Imprimir</button>     ← opcionales, al pie
 *   </app-detalle-pantalla>
 *
 * Al abrir, el foco va a «Volver» (en un campo subiría el teclado); Esc cierra, salvo que haya otro diálogo encima (una
 * confirmación, la hoja del ⋯), que se queda con su Esc. Al cerrar, `appTrampaFoco` devuelve el foco a lo que lo tenía.
 * Cubre toda la pantalla, también la barra de destinos; un diálogo (z-dialogo) queda encima. Los estilos están en
 * styles.css («Ventas: consulta y detalle en el celular») porque alcanzan al contenido proyectado.
 */
@Component({
  selector: 'app-detalle-pantalla',
  imports: [TrampaFoco],
  // En el documento y no en la pantalla: si el botón que tenía el foco desaparece, el foco cae al body y Esc sigue andando.
  host: { '(document:keydown)': 'alTeclear($event)' },
  template: `
    <div
      #pantalla
      class="detalle-pantalla"
      tabindex="-1"
      role="dialog"
      aria-modal="true"
      appTrampaFoco
      [attr.aria-labelledby]="id"
    >
      <div class="ficha-barra">
        <button #volver type="button" class="ficha-volver" aria-label="Volver" (click)="cerrar.emit()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="m15 6-6 6 6 6" />
          </svg>
        </button>
        <h2 [id]="id">{{ titulo() }}</h2>
        <ng-content select="[mas]" />
      </div>
      <div class="detalle-pantalla-cuerpo">
        <ng-content />
      </div>
      <footer class="ficha-pie detalle-pantalla-pie">
        <ng-content select="[pie]" />
      </footer>
    </div>
  `,
})
export class DetallePantalla {
  readonly titulo = input.required<string>();
  readonly cerrar = output<void>();

  protected readonly id = `detalle-pantalla-${++siguienteId}`;
  private readonly pantalla = viewChild.required<ElementRef<HTMLElement>>('pantalla');
  private readonly volver = viewChild.required<ElementRef<HTMLButtonElement>>('volver');
  private readonly trampa = viewChild.required(TrampaFoco);

  constructor() {
    afterNextRender(() => this.volver().nativeElement.focus());
  }

  protected alTeclear(tecla: KeyboardEvent): void {
    if (tecla.key !== 'Escape' || tecla.defaultPrevented) return;
    // Con otro diálogo abierto encima (Anular, Dejar sin factura, la hoja del ⋯), Esc es de ese aunque el foco se le haya caído al body.
    if (!this.trampa().esLaDeArriba()) return;
    const desde = tecla.target instanceof Element ? tecla.target.closest('[aria-modal="true"]') : null;
    if (desde !== null && desde !== this.pantalla().nativeElement) return;
    tecla.preventDefault();
    tecla.stopPropagation();
    this.cerrar.emit();
  }
}
