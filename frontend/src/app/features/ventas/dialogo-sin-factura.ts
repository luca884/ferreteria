import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { TrampaFoco } from '../../shared/trampa-foco';

/** El motivo de «Dejar sin factura» tiene hasta 200 caracteres (S12, C3). */
export const MOTIVO_SIN_FACTURA_MAXIMO = 200;

/**
 * El error del motivo, o null si vale: la misma regla y los mismos textos que el servidor (C3). Se recorta: en blanco es
 * vacío. El servidor vuelve a validar.
 */
export function validarMotivoSinFactura(texto: string): string | null {
  const largo = texto.trim().length;
  if (largo === 0) return 'Contá por qué la venta queda sin factura.';
  if (largo > MOTIVO_SIN_FACTURA_MAXIMO) return `El motivo tiene hasta ${MOTIVO_SIN_FACTURA_MAXIMO} caracteres.`;
  return null;
}

/**
 * «Dejar sin factura» (CU-16 punto 7, D10): pide el motivo, obligatorio y de hasta 200 caracteres, y avisa que es
 * definitivo (S12). Presentacional: Enter confirma solo con un motivo válido y Esc cierra; mientras `enVuelo`, ninguno hace
 * nada. El rechazo del servidor llega por `error` y el diálogo no se cierra: el motivo escrito se conserva.
 */
@Component({
  selector: 'app-dialogo-sin-factura',
  imports: [TrampaFoco],
  templateUrl: './dialogo-sin-factura.html',
  styleUrl: './dialogo-sin-factura.css',
})
export class DialogoSinFactura {
  private readonly injector = inject(Injector);

  /** El número interno de la venta. */
  readonly numero = input.required<number>();
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** El rechazo del servidor (400 del motivo, 409), tal cual. */
  readonly error = input<string | null>(null);

  /** El motivo ya recortado. */
  readonly confirmar = output<string>();
  readonly cancelar = output<void>();

  protected readonly maximo = MOTIVO_SIN_FACTURA_MAXIMO;
  protected readonly motivo = signal('');
  /** Se intentó confirmar con el motivo inválido: recién ahí se marca el error. */
  private readonly intentado = signal(false);
  /** Entre el Enter y el siguiente pintado, para que un segundo Enter no mande el pedido dos veces. */
  private bloqueado = false;

  private readonly campoMotivo = viewChild<ElementRef<HTMLInputElement>>('campoMotivo');

  protected readonly errorMotivo = computed(() => (this.intentado() ? validarMotivoSinFactura(this.motivo()) : null));

  constructor() {
    afterNextRender(() => this.campoMotivo()?.nativeElement.focus());
  }

  /** Un clic en el fondo no debe sacar el foco del diálogo (iría al body y Esc y Enter dejarían de llegarle). */
  protected retenerFoco(evento: MouseEvent): void {
    if (evento.target === evento.currentTarget) evento.preventDefault();
  }

  protected alTeclear(evento: KeyboardEvent): void {
    switch (evento.key) {
      case 'Escape':
        evento.preventDefault();
        evento.stopPropagation();
        this.cancelarSinFactura();
        return;
      case 'Enter':
        // En un botón, Enter es su clic: Cancelar cancela.
        if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
        evento.preventDefault();
        evento.stopPropagation();
        this.confirmarSinFactura();
        return;
    }
  }

  protected cancelarSinFactura(): void {
    if (!this.enVuelo()) this.cancelar.emit();
  }

  protected confirmarSinFactura(): void {
    if (this.enVuelo() || this.bloqueado) return;
    if (validarMotivoSinFactura(this.motivo()) !== null) {
      this.intentado.set(true);
      this.campoMotivo()?.nativeElement.focus();
      return;
    }
    // Cuando el contenedor ya pintó, `enVuelo` dice si sigue en curso; si el rechazo fue inmediato, se puede reintentar.
    this.bloqueado = true;
    afterNextRender(() => (this.bloqueado = false), { injector: this.injector });
    this.confirmar.emit(this.motivo().trim());
  }
}
