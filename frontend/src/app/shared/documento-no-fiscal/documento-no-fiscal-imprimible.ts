import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { DatosFiscales } from '../../features/datos-fiscales/datos-fiscales.service';
import { TrampaFoco } from '../trampa-foco';
import { DocumentoNoFiscalHoja } from './documento-no-fiscal-hoja';
import { DocumentoNoFiscal } from './documento-no-fiscal.models';

/**
 * El remito o el presupuesto para imprimir (cu-07-remito-presupuesto-imprimible, S1): A4 con `window.print()`, como la factura
 * imprimible. Enter imprime y Esc cierra. Al imprimir solo sale `.documento-imprimible` (reglas `@media print` y `@page
 * documento` en styles.css): el título y los botones quedan afuera.
 */
@Component({
  selector: 'app-documento-no-fiscal-imprimible',
  imports: [DocumentoNoFiscalHoja, TrampaFoco],
  templateUrl: './documento-no-fiscal-imprimible.html',
  styleUrl: './documento-no-fiscal-imprimible.css',
})
export class DocumentoNoFiscalImprimible {
  readonly documento = input.required<DocumentoNoFiscal>();
  readonly emisor = input.required<DatosFiscales>();
  readonly cerrar = output<void>();

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
