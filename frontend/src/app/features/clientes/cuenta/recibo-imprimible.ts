import { Component, ElementRef, afterNextRender, computed, inject, input, output, viewChild } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';
import { MontoPipe } from '../../../core/formato/monto';
import { TrampaFoco } from '../../../shared/trampa-foco';
import { MEDIO_COBRANZA_LABELS, Recibo, fechaCompletaLocal } from './cobranza.models';

/**
 * El recibo de una cobranza, para ver e imprimir (CC-036). Presentacional: Enter imprime con `window.print()` y Esc
 * cierra. Es un recibo INTERNO, no un comprobante fiscal: la leyenda lo dice (texto a confirmar con el contador, P7). Al
 * imprimir solo sale `.recibo-imprimible` (reglas `@media print` en styles.css), no la pantalla ni los botones.
 */
@Component({
  selector: 'app-recibo-imprimible',
  imports: [MontoPipe, TrampaFoco],
  templateUrl: './recibo-imprimible.html',
  styleUrl: './recibo-imprimible.css',
})
export class ReciboImprimible {
  private readonly auth = inject(AuthService);

  readonly recibo = input.required<Recibo>();
  readonly cerrar = output<void>();

  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);
  protected readonly fecha = computed(() => fechaCompletaLocal(this.recibo().fecha));
  protected readonly medio = computed(() => MEDIO_COBRANZA_LABELS[this.recibo().medio]);

  private readonly botonImprimir = viewChild<ElementRef<HTMLButtonElement>>('botonImprimir');

  constructor() {
    afterNextRender(() => this.botonImprimir()?.nativeElement.focus());
  }

  protected imprimir(): void {
    window.print();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.cerrar.emit();
    } else if (evento.key === 'Enter') {
      // En un botón, Enter es su clic (Cerrar cierra, Imprimir imprime): acá solo cuando el foco está en otra parte.
      if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
      evento.preventDefault();
      evento.stopPropagation();
      this.imprimir();
    }
  }
}
