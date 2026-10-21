import {
  Component,
  ElementRef,
  InjectionToken,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MontoPipe, formatearMonto } from '../../../core/formato/monto';
import { TrampaFoco } from '../../../shared/trampa-foco';
import { diaDeCaja } from '../../caja/caja.models';
import {
  MEDIO_COBRANZA_LABELS,
  MOTIVO_MAXIMO,
  Recibo,
  fechaCompletaLocal,
  hoyEnBuenosAires,
  validarMotivo,
} from './cobranza.models';

/** El día de hoy («yyyy-MM-dd», Buenos Aires): un token para que el spec fije la fecha sin tocar el reloj. */
export const HOY = new InjectionToken<() => string>('HOY', {
  providedIn: 'root',
  factory: () => () => hoyEnBuenosAires(new Date()),
});

/**
 * Anulación de un cobro (CC-056, CC-097). Presentacional: muestra qué se anula, qué vuelve a quedar pendiente y cómo toca
 * la caja, y pide el motivo (5 a 200 caracteres). Enter confirma solo con un motivo válido, Esc cierra; mientras `enVuelo`
 * ninguno hace nada. El rechazo del servidor llega por `error` y el diálogo no se cierra: el motivo escrito se conserva.
 */
@Component({
  selector: 'app-dialogo-anulacion',
  imports: [MontoPipe, TrampaFoco],
  templateUrl: './dialogo-anulacion.html',
  styleUrl: './dialogo-anulacion.css',
})
export class DialogoAnulacion {
  private readonly injector = inject(Injector);
  private readonly hoy = inject(HOY);

  readonly recibo = input.required<Recibo>();
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** El rechazo del servidor (caja cerrada, cliente desactivado, red…). */
  readonly error = input<string | null>(null);

  /** El motivo ya recortado. */
  readonly confirmar = output<string>();
  readonly cancelar = output<void>();

  protected readonly maximo = MOTIVO_MAXIMO;
  protected readonly medioLabels = MEDIO_COBRANZA_LABELS;
  protected readonly fecha = fechaCompletaLocal;

  protected readonly motivo = signal('');
  /** Se intentó confirmar con el motivo inválido: recién ahí se marca el error. */
  private readonly intentado = signal(false);
  /** Entre el Enter y el siguiente pintado, para que un segundo Enter no mande la anulación dos veces. */
  private bloqueado = false;

  private readonly campoMotivo = viewChild<ElementRef<HTMLInputElement>>('campoMotivo');

  protected readonly errorMotivo = computed(() => (this.intentado() ? validarMotivo(this.motivo()) : null));

  protected readonly esEfectivo = computed(() => this.recibo().medio === 'EFECTIVO');

  protected readonly textoEfectivo = computed(
    () => `Esto resta ${formatearMonto(this.recibo().total)} del efectivo esperado de la caja de hoy.`,
  );

  /** Un cobro en efectivo de otro día: la anulación resta de la caja de hoy, pero la caja de aquel día no cambia. */
  protected readonly textoOtroDia = computed(() => {
    const r = this.recibo();
    if (r.medio !== 'EFECTIVO' || r.fecha.slice(0, 10) === this.hoy()) return null;
    return `El cobro es del ${diaDeCaja(r.fecha)}: esa caja no cambia.`;
  });

  constructor() {
    afterNextRender(() => this.campoMotivo()?.nativeElement.focus());
  }

  protected escribir(texto: string): void {
    this.motivo.set(texto);
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
        if (!this.enVuelo()) this.cancelar.emit();
        return;
      case 'Enter':
        // En un botón, Enter es su clic: Cancelar cancela, no se confirma la anulación.
        if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
        evento.preventDefault();
        evento.stopPropagation();
        this.confirmarAnulacion();
        return;
    }
  }

  protected cancelarAnulacion(): void {
    if (!this.enVuelo()) this.cancelar.emit();
  }

  protected confirmarAnulacion(): void {
    if (this.enVuelo() || this.bloqueado) return;
    if (validarMotivo(this.motivo()) !== null) {
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
