import { Component, ElementRef, afterNextRender, computed, inject, input, output, viewChild } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { TrampaFoco } from '../../shared/trampa-foco';
import { VentaHoja } from './venta-hoja';
import { VentaConsultada } from './ventas.models';

/** La leyenda de la venta impresa mientras no haya factura electrónica (fase 5). Provisoria: se confirma con el contador. */
export const LEYENDA_VENTA_INTERNA = 'Comprobante interno, no válido como factura';

/**
 * Una venta para reimprimir (G60), como el recibo de cuenta corriente: Enter imprime con `window.print()` y Esc cierra.
 * Al imprimir solo sale `.venta-imprimible` (reglas `@media print` en styles.css), no la pantalla ni los botones.
 */
@Component({
  selector: 'app-venta-imprimible',
  imports: [TrampaFoco, VentaHoja],
  templateUrl: './venta-imprimible.html',
  styleUrl: './venta-imprimible.css',
})
export class VentaImprimible {
  private readonly auth = inject(AuthService);

  readonly venta = input.required<VentaConsultada>();
  readonly cerrar = output<void>();

  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);
  protected readonly leyenda = LEYENDA_VENTA_INTERNA;

  private readonly botonImprimir = viewChild<ElementRef<HTMLButtonElement>>('botonImprimir');

  constructor() {
    afterNextRender(() => this.botonImprimir()?.nativeElement.focus());
  }

  protected imprimir(): void {
    window.print();
  }

  /** Un clic en el fondo no debe sacar el foco del diálogo (iría al body y Esc y Enter dejarían de llegarle). */
  protected retenerFoco(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) evento.preventDefault();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.cerrar.emit();
    } else if (evento.key === 'Enter') {
      // En un botón, Enter es su clic: acá solo cuando el foco está en otra parte.
      if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
      evento.preventDefault();
      evento.stopPropagation();
      this.imprimir();
    }
  }
}
