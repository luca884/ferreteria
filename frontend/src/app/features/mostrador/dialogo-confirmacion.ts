import { Component, ElementRef, afterNextRender, computed, input, output, viewChild } from '@angular/core';
import { GuardaLector } from './guarda-lector';

/**
 * Confirma quitar un renglón (F10) o vaciar el ticket. Presentacional. El foco arranca en «Quitar» (F10 +
 * Enter es rápido) y, para vaciar, en «Cancelar» (el default seguro). Enter activa el botón enfocado.
 */
@Component({
  selector: 'app-dialogo-confirmacion',
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-confirmacion-titulo"
        (keydown)="alTeclear($event)"
      >
        <h2 id="dialogo-confirmacion-titulo">{{ mensaje() }}</h2>
        <div class="dialogo-acciones">
          <button
            #cancelarBtn
            type="button"
            class="cancelar"
            data-tecla="Esc"
            aria-keyshortcuts="Escape"
            (click)="cancelar.emit()"
          >
            Cancelar
          </button>
          <button #confirmarBtn type="button" class="confirmar peligro" (click)="confirmar.emit()">
            {{ modo() === 'quitar' ? 'Quitar' : 'Vaciar' }}
          </button>
        </div>
      </div>
    </div>
  `,
})
export class DialogoConfirmacion {
  readonly modo = input.required<'quitar' | 'vaciar'>();
  /** Descripción del renglón a quitar. */
  readonly descripcion = input('');
  /** Cantidad de renglones del ticket (para el aviso de vaciar). */
  readonly renglones = input(0);

  readonly confirmar = output<void>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  private readonly confirmarBtn = viewChild.required<ElementRef<HTMLButtonElement>>('confirmarBtn');
  private readonly cancelarBtn = viewChild.required<ElementRef<HTMLButtonElement>>('cancelarBtn');
  private readonly guarda = new GuardaLector();

  protected readonly mensaje = computed(() => {
    if (this.modo() === 'quitar') return `¿Quitar "${this.descripcion()}" del ticket?`;
    const n = this.renglones();
    return `¿Vaciar el ticket? ${n === 1 ? 'Se pierde 1 renglón.' : `Se pierden ${n} renglones.`}`;
  });

  constructor() {
    afterNextRender(() => {
      const destino = this.modo() === 'quitar' ? this.confirmarBtn() : this.cancelarBtn();
      destino.nativeElement.focus();
    });
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.cancelar.emit();
      return;
    }
    const eraEscaneo = this.guarda.alTeclear(evento);
    if (evento.key !== 'Enter') return;
    evento.preventDefault();
    evento.stopPropagation();
    if (eraEscaneo) {
      this.escaneoDescartado.emit();
      return;
    }
    if (document.activeElement === this.cancelarBtn().nativeElement) this.cancelar.emit();
    else this.confirmar.emit();
  }
}
