import { DOCUMENT } from '@angular/common';
import { Directive, ElementRef, OnDestroy, inject } from '@angular/core';

/** Lo que Tab recorre. `tabindex="-1"` (las opciones no elegidas de un grupo con flechas) queda afuera. */
const ENFOCABLES = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]',
]
  .map((selector) => `${selector}:not([tabindex="-1"])`)
  .join(', ');

/**
 * Trampa de foco para los diálogos modales (`role="dialog"` o `"alertdialog"` con `aria-modal`): Tab y
 * Shift+Tab ciclan entre los controles del diálogo y, al cerrarse, el foco vuelve a lo que lo tenía al
 * abrirse. No toca Esc ni Enter: cada diálogo los sigue manejando como antes.
 *
 * Es propia y no un `<dialog>` con `showModal()` porque el mostrador decide a dónde va el foco al cerrar (el
 * buscador, para el lector de código de barras) y jsdom no implementa `showModal()`.
 */
@Directive({
  selector: '[appTrampaFoco]',
  host: { '(document:keydown)': 'alTeclear($event)' },
})
export class TrampaFoco implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly documento = inject(DOCUMENT);
  /** Lo que tenía el foco al abrirse el diálogo (el botón o el campo desde el que se abrió). */
  private readonly previo = this.documento.activeElement;

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'Tab' || evento.defaultPrevented) return;
    const controles = Array.from(this.host.querySelectorAll<HTMLElement>(ENFOCABLES));
    if (controles.length === 0) {
      evento.preventDefault();
      return;
    }
    const primero = controles[0];
    const ultimo = controles[controles.length - 1];
    const activo = this.documento.activeElement;
    const adentro = activo instanceof Node && this.host.contains(activo);
    if (evento.shiftKey && (!adentro || activo === primero)) {
      evento.preventDefault();
      ultimo.focus();
    } else if (!evento.shiftKey && (!adentro || activo === ultimo)) {
      evento.preventDefault();
      primero.focus();
    }
  }

  ngOnDestroy(): void {
    // Si al cerrar alguien ya movió el foco afuera (el mostrador lo manda al buscador), se respeta.
    const activo = this.documento.activeElement;
    const sinDuenio = !activo || activo === this.documento.body || this.host.contains(activo);
    if (!sinDuenio) return;
    if (this.previo instanceof HTMLElement && this.previo.isConnected) this.previo.focus();
  }
}
