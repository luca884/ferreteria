import { Component, ElementRef, afterNextRender, computed, inject, input, output, viewChild } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';
import { MontoPipe } from '../../../core/formato/monto';
import { TrampaFoco } from '../../../shared/trampa-foco';
import { MEDIO_COBRANZA_LABELS, Recibo, clavePartida, fechaCompletaLocal } from './cobranza.models';

/** La palabra de la marca de un recibo anulado (CC-098). Aislada: el texto exacto se confirma con el contador (P7). */
export const LEYENDA_ANULADO = 'ANULADO';

/**
 * El recibo de una cobranza, para ver e imprimir (CC-036). Presentacional: Enter imprime con `window.print()` y Esc
 * cierra. Si el recibo está ANULADO (CC-098) lleva una marca grande, el motivo y quién lo anuló: se sigue viendo y
 * reimprimiendo con sus datos originales. Es un recibo INTERNO, no un comprobante fiscal: la leyenda lo dice (texto a confirmar con el contador, P7). Al
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
  protected readonly leyendaAnulado = LEYENDA_ANULADO;
  /** Identifica cada imputación (venta o Recargo) en el `@for`: el comprobanteId solo no alcanza. */
  protected readonly clave = clavePartida;
  protected readonly anulado = computed(() => this.recibo().estado === 'ANULADO');
  /** «Dueño Tornillo el 20/10/2026 09:15»; vacío si el servidor no manda quién o cuándo. */
  protected readonly anuladoPor = computed(() => {
    const { anuladoPor, fechaAnulacion } = this.recibo();
    if (!anuladoPor || !fechaAnulacion) return null;
    return `${anuladoPor} el ${fechaCompletaLocal(fechaAnulacion)}`;
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
    } else if (evento.key === 'Enter') {
      // En un botón, Enter es su clic (Cerrar cierra, Imprimir imprime): acá solo cuando el foco está en otra parte.
      if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
      evento.preventDefault();
      evento.stopPropagation();
      this.imprimir();
    }
  }
}
