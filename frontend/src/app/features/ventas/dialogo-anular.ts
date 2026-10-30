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

/** El motivo de la anulación interna tiene hasta 200 caracteres (CU-08 punto 3, S13). */
export const MOTIVO_ANULACION_MAXIMO = 200;

/**
 * El error del motivo de la anulación interna, o null si vale: la misma regla y los mismos textos que el servidor (CU-08
 * punto 3). Se recorta: en blanco es vacío. El servidor vuelve a validar.
 */
export function validarMotivoAnulacion(texto: string): string | null {
  const largo = texto.trim().length;
  if (largo === 0) return 'Contá por qué se anula la venta.';
  if (largo > MOTIVO_ANULACION_MAXIMO) return `El motivo tiene hasta ${MOTIVO_ANULACION_MAXIMO} caracteres.`;
  return null;
}

/**
 * La confirmación de «Anular» (CU-08 punto 1, S15). Con `conMotivo` (la venta no tiene la factura autorizada: anulación
 * interna, D12) pide además el motivo, obligatorio y de hasta 200 caracteres (S13). Con `conCuentaCorriente` avisa que la
 * parte a cuenta corriente se descuenta de la deuda del cliente (cu-08-nc-cuenta-corriente S7). Presentacional: Esc cancela; sin
 * motivo el foco arranca en Cancelar (lo seguro) y Enter activa el botón enfocado; con motivo arranca en el campo y Enter
 * confirma solo con un motivo válido. Mientras `enVuelo`, nada responde. El rechazo del servidor llega por `error` y el
 * diálogo no se cierra.
 */
@Component({
  selector: 'app-dialogo-anular',
  imports: [TrampaFoco],
  templateUrl: './dialogo-anular.html',
  styleUrl: './dialogo-anular.css',
})
export class DialogoAnular {
  private readonly injector = inject(Injector);

  /** El número interno de la venta. */
  readonly numero = input.required<number>();
  /** Pedir el motivo: la venta se anula sin documento fiscal. */
  readonly conMotivo = input(false);
  /** La venta cargó algo a cuenta corriente: la nota lo acredita en la cuenta del cliente. */
  readonly conCuentaCorriente = input(false);
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** El rechazo del servidor (400 del motivo, 409, 422), tal cual. */
  readonly error = input<string | null>(null);

  /** El motivo ya recortado, o null en la nota de crédito. */
  readonly confirmar = output<string | null>();
  readonly cancelar = output<void>();

  protected readonly maximo = MOTIVO_ANULACION_MAXIMO;
  protected readonly motivo = signal('');
  /** Se intentó confirmar con el motivo inválido: recién ahí se marca el error. */
  private readonly intentado = signal(false);
  /** Entre el Enter y el siguiente pintado, para que un segundo Enter no mande el pedido dos veces. */
  private bloqueado = false;

  private readonly campoMotivo = viewChild<ElementRef<HTMLInputElement>>('campoMotivo');
  private readonly botonCancelar = viewChild.required<ElementRef<HTMLButtonElement>>('botonCancelar');

  protected readonly errorMotivo = computed(() =>
    this.conMotivo() && this.intentado() ? validarMotivoAnulacion(this.motivo()) : null,
  );

  constructor() {
    afterNextRender(() => (this.campoMotivo() ?? this.botonCancelar()).nativeElement.focus());
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
        this.cancelarAnulacion();
        return;
      case 'Enter':
        // En un botón, Enter es su clic: Cancelar cancela.
        if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
        evento.preventDefault();
        evento.stopPropagation();
        if (this.conMotivo()) this.confirmarAnulacion();
        return;
    }
  }

  protected cancelarAnulacion(): void {
    if (!this.enVuelo()) this.cancelar.emit();
  }

  protected confirmarAnulacion(): void {
    if (this.enVuelo() || this.bloqueado) return;
    if (this.conMotivo() && validarMotivoAnulacion(this.motivo()) !== null) {
      this.intentado.set(true);
      this.campoMotivo()?.nativeElement.focus();
      return;
    }
    // Cuando el contenedor ya pintó, `enVuelo` dice si sigue en curso; si el rechazo fue inmediato, se puede reintentar.
    this.bloqueado = true;
    afterNextRender(() => (this.bloqueado = false), { injector: this.injector });
    this.confirmar.emit(this.conMotivo() ? this.motivo().trim() : null);
  }
}
