import { Component, ElementRef, afterNextRender, computed, input, output, viewChild } from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { TrampaFoco } from '../trampa-foco';
import { FacturaHoja } from './factura-hoja';
import { VentaFacturada } from './factura.models';

/**
 * La factura de una venta para imprimir (CU-07 paso 5, D6): A4 con `window.print()`, una copia. Enter imprime y Esc cierra,
 * como la venta imprimible. Al imprimir solo sale `.factura-imprimible` (reglas `@media print` y `@page factura` en
 * styles.css): el título, el vuelto, el aviso y los botones quedan afuera. La hoja se arma solo con `fiscal.documento`.
 */
@Component({
  selector: 'app-factura-imprimible',
  imports: [FacturaHoja, MontoPipe, TrampaFoco],
  templateUrl: './factura-imprimible.html',
  styleUrl: './factura-imprimible.css',
})
export class FacturaImprimible {
  readonly venta = input.required<VentaFacturada>();
  /** El vuelto de la venta recién cobrada en efectivo, en centavos: se ve en el diálogo y no se imprime. */
  readonly vueltoCentavos = input<number | null>(null);
  /** Mensaje del contenedor (un escaneo descartado). */
  readonly aviso = input<string | null>(null);
  /**
   * Recibe cada tecla y dice si el Enter cierra una ráfaga del lector (el mostrador le pasa su `GuardaLector`): ese Enter
   * no imprime. Por defecto nada es un escaneo.
   */
  readonly esEscaneo = input<(evento: KeyboardEvent) => boolean>(() => false);

  readonly cerrar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  protected readonly documento = computed(() => this.venta().fiscal.documento);
  /** «Factura B» o, si no es válida como factura, el número interno de la venta. */
  protected readonly titulo = computed(() => {
    const d = this.documento();
    return d.validoComoFactura ? `Factura ${d.letra}` : `Venta N.º ${this.venta().numero}`;
  });

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
      return;
    }
    const eraEscaneo = this.esEscaneo()(evento);
    if (evento.key !== 'Enter') return;
    if (eraEscaneo) {
      // También sobre un botón: anular el keydown evita su clic.
      evento.preventDefault();
      evento.stopPropagation();
      this.escaneoDescartado.emit();
      return;
    }
    // En un botón, Enter es su clic: acá solo cuando el foco está en otra parte.
    if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
    evento.preventDefault();
    evento.stopPropagation();
    this.imprimir();
  }
}
