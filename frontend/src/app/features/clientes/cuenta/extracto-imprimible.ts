import { Component, ElementRef, afterNextRender, computed, inject, input, output, viewChild } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';
import { MontoPipe } from '../../../core/formato/monto';
import { TrampaFoco } from '../../../shared/trampa-foco';
import { textoDeSaldo } from './ajuste.models';
import { fechaCompletaLocal } from './cobranza.models';
import { Extracto, TIPO_MOVIMIENTO_LABELS, TOPE_IMPRESION, muestraMotivo, textoPeriodo } from './extracto.models';

/** La leyenda del impreso. Texto a confirmar con el contador (P7): está acá, aislada, para ajustarla en un solo lugar. */
export const LEYENDA_EXTRACTO = 'Resumen interno de cuenta, no válido como comprobante fiscal';

const FORMATO_EMITIDO = new Intl.DateTimeFormat('es-AR', {
  timeZone: 'America/Argentina/Buenos_Aires',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** «21/10/2026 12:30»: el instante dicho en hora de Buenos Aires, que es la del negocio. */
function emitidoEl(ahora: Date): string {
  const p = Object.fromEntries(FORMATO_EMITIDO.formatToParts(ahora).map((parte) => [parte.type, parte.value]));
  return `${p['day']}/${p['month']}/${p['year']} ${p['hour']}:${p['minute']}`;
}

/**
 * El extracto de una cuenta, para ver e imprimir (CC-099). Lleva la razón social del cliente y no su documento (CU-12.6). Presentacional: recibe el extracto COMPLETO que pidió el padre
 * (`impresion=true`, hasta TOPE_IMPRESION líneas, en un solo pedido). Enter imprime con `window.print()` y Esc cierra. Es un
 * resumen INTERNO, no un comprobante fiscal: la leyenda lo dice. Si el extracto vino `truncado`, el aviso es parte del
 * documento (sale en la pantalla y en la impresión): se imprime lo que hay, los primeros movimientos. Al imprimir solo sale
 * `.extracto-imprimible` (reglas `@media print` en styles.css).
 */
@Component({
  selector: 'app-extracto-imprimible',
  imports: [MontoPipe, TrampaFoco],
  templateUrl: './extracto-imprimible.html',
  styleUrl: './extracto-imprimible.css',
})
export class ExtractoImprimible {
  private readonly auth = inject(AuthService);

  readonly extracto = input.required<Extracto>();
  readonly cerrar = output<void>();

  protected readonly leyenda = LEYENDA_EXTRACTO;
  protected readonly tope = TOPE_IMPRESION;
  protected readonly fecha = fechaCompletaLocal;
  protected readonly tipoLabels = TIPO_MOVIMIENTO_LABELS;
  protected readonly muestraMotivo = muestraMotivo;
  /** El saldo como arriba en la cuenta: «$ 500,00 a favor» si es negativo (#84). */
  protected readonly textoDeSaldo = textoDeSaldo;
  protected readonly emitido = emitidoEl(new Date());

  protected readonly ferreteria = computed(() => this.auth.sesion()?.ferreteriaNombre ?? null);
  protected readonly periodo = computed(() => textoPeriodo(this.extracto().desde, this.extracto().hasta));
  protected readonly tipo = computed(() => {
    const tipo = this.extracto().tipo;
    return tipo ? TIPO_MOVIMIENTO_LABELS[tipo] : 'Todos';
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
