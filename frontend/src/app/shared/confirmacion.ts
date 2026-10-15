import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { TrampaFoco } from './trampa-foco';

let siguienteId = 0;

/**
 * Diálogo de confirmación genérico (bajas, descartar cambios, avisos antes de guardar), con el estilo de los
 * diálogos de la app. Presentacional: el contenedor decide qué hacer. Esc cancela y Enter activa el botón
 * enfocado; por defecto el foco arranca en Cancelar (lo seguro) y `foco="confirmar"` lo pone en la acción.
 */
@Component({
  selector: 'app-confirmacion',
  imports: [TrampaFoco],
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo"
        role="alertdialog"
        aria-modal="true"
        appTrampaFoco
        [attr.aria-labelledby]="id + '-titulo'"
        [attr.aria-describedby]="mensaje() ? id + '-mensaje' : null"
        (keydown)="alTeclear($event)"
      >
        <h2 [id]="id + '-titulo'">{{ titulo() }}</h2>
        @if (mensaje(); as texto) {
          <p [id]="id + '-mensaje'">{{ texto }}</p>
        }
        <div class="dialogo-acciones">
          <button
            #cancelarBtn
            type="button"
            class="cancelar secundario"
            data-tecla="Esc"
            aria-keyshortcuts="Escape"
            (click)="cancelar.emit()"
          >
            {{ textoCancelar() }}
          </button>
          <button
            #confirmarBtn
            type="button"
            class="confirmar"
            [class.peligro]="peligro()"
            [class.primario]="!peligro()"
            (click)="confirmar.emit()"
          >
            {{ textoConfirmar() }}
          </button>
        </div>
      </div>
    </div>
  `,
})
export class Confirmacion {
  readonly titulo = input.required<string>();
  readonly mensaje = input<string | null>(null);
  readonly textoConfirmar = input.required<string>();
  readonly textoCancelar = input('Cancelar');
  /** Acción destructiva (baja, descartar): botón rojo. */
  readonly peligro = input(false);
  readonly foco = input<'confirmar' | 'cancelar'>('cancelar');

  readonly confirmar = output<void>();
  readonly cancelar = output<void>();

  protected readonly id = `confirmacion-${++siguienteId}`;
  private readonly confirmarBtn = viewChild.required<ElementRef<HTMLButtonElement>>('confirmarBtn');
  private readonly cancelarBtn = viewChild.required<ElementRef<HTMLButtonElement>>('cancelarBtn');

  constructor() {
    afterNextRender(() => {
      (this.foco() === 'confirmar' ? this.confirmarBtn() : this.cancelarBtn()).nativeElement.focus();
    });
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.cancelar.emit();
      return;
    }
    if (evento.key !== 'Enter') return;
    evento.preventDefault();
    evento.stopPropagation();
    if (document.activeElement === this.confirmarBtn().nativeElement) this.confirmar.emit();
    else this.cancelar.emit();
  }
}
