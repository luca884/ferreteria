import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MontoPipe, formatearMonto } from '../../../core/formato/monto';
import { TrampaFoco } from '../../../shared/trampa-foco';
import { fechaHoraLocal } from '../../caja/caja.models';
import { aCentavos, desdeTexto } from '../../mostrador/centavos';
import {
  CobranzaRequest,
  MEDIOS_COBRANZA,
  MEDIO_COBRANZA_LABELS,
  MedioCobranza,
  PendienteCuenta,
  aCuentaCentavos,
  centavosATexto,
  sugerirFifo,
} from './cobranza.models';

/** Lo que confirma el diálogo: el contenedor le suma la clave de idempotencia y el cliente. */
export type PedidoCobranza = Omit<CobranzaRequest, 'clave' | 'clienteId'>;

/** El mismo tope que acepta el servidor: 13 enteros y 2 decimales. */
const TOTAL_MAXIMO_CENTAVOS = 999_999_999_999_999;
const MENSAJE_DECIMALES = 'Usá un número con hasta 2 decimales.';

/** Un importe escrito, en centavos; null si no es un número de hasta 2 decimales (o es negativo). */
function leer(texto: string): number | null {
  try {
    const centavos = desdeTexto(texto, 2);
    return centavos >= 0 ? centavos : null;
  } catch {
    return null;
  }
}

/**
 * Cobranza de cuenta corriente (F4 en la pantalla de cuenta). Presentacional: arranca con el total vacío (no precarga la
 * deuda) y, al tipearlo, sugiere repartirlo en orden FIFO entre los comprobantes con deuda; la tabla se puede editar a
 * mano (importe vacío = desmarcar) y lo que no se imputa queda «a cuenta». Sin deuda es un adelanto: no hay tabla y el
 * cobro queda todo a cuenta. Con el foco en el medio, ← y → (o ↑ y ↓) lo cambian; Enter confirma, Esc cierra. Mientras `enVuelo` ignora Enter.
 * Si el envío quedó sin confirmar (`congelado`: red o 5xx) el contenido no se puede tocar —el reintento lleva la misma clave—
 * y la única salida para cambiarlo es `reiniciar`.
 */
@Component({
  selector: 'app-dialogo-cobranza',
  imports: [MontoPipe, TrampaFoco],
  templateUrl: './dialogo-cobranza.html',
  styleUrl: './dialogo-cobranza.css',
})
export class DialogoCobranza {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly clienteNombre = input.required<string>();
  /** Los comprobantes con deuda en orden FIFO, como los manda el servidor. Si cambian (409) se vuelve a sugerir. */
  readonly pendientes = input<readonly PendienteCuenta[]>([]);
  /** Lo que el cliente ya tiene a cuenta, sin imputar (en pesos). */
  readonly aCuenta = input(0);
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** Un aviso que no es un error: «Las deudas cambiaron…». */
  readonly aviso = input<string | null>(null);
  /** El rechazo del servidor (caja cerrada, cliente desactivado…). */
  readonly error = input<string | null>(null);
  /** El último envío no se pudo confirmar: total, filas y medio quedan fijos hasta reintentar o reiniciar. */
  readonly congelado = input(false);

  readonly confirmar = output<PedidoCobranza>();
  readonly cancelar = output<void>();
  /** «Cancelar y empezar de nuevo»: el contenedor suelta el envío pendiente (clave nueva) y el contenido vuelve a ser editable. */
  readonly reiniciar = output<void>();

  protected readonly medios = MEDIOS_COBRANZA;
  protected readonly medioLabels = MEDIO_COBRANZA_LABELS;
  protected readonly fecha = fechaHoraLocal;

  protected readonly medio = signal<MedioCobranza>('EFECTIVO');
  protected readonly totalTexto = signal('');
  /** Lo escrito a mano en cada fila, por comprobante; null mientras se usa la sugerencia FIFO. */
  private readonly editadas = signal<Record<number, string> | null>(null);
  /** Se intentó confirmar con errores: recién ahí se avisa que falta el total. */
  private readonly intentado = signal(false);
  /** Entre el Enter y el siguiente pintado, para que un segundo Enter no mande el cobro dos veces. */
  private bloqueado = false;

  private readonly campoTotal = viewChild<ElementRef<HTMLInputElement>>('campoTotal');

  /** El total en centavos; null si está vacío o no es un número. */
  protected readonly totalCentavos = computed<number | null>(() => {
    const texto = this.totalTexto().trim();
    return texto === '' ? null : leer(texto);
  });

  protected readonly errorTotal = computed<string | null>(() => {
    const texto = this.totalTexto().trim();
    if (texto === '') return this.intentado() ? 'Escribí el total a cobrar.' : null;
    const centavos = this.totalCentavos();
    if (centavos === null) return MENSAJE_DECIMALES;
    if (centavos === 0) return 'El total tiene que ser mayor que cero.';
    if (centavos > TOTAL_MAXIMO_CENTAVOS) return 'El total es demasiado grande.';
    return null;
  });

  private readonly sugerencia = computed(() => sugerirFifo(this.pendientes(), this.totalCentavos() ?? 0));

  /** Las filas con el texto que se ve, su importe en centavos y su error en línea. */
  protected readonly filas = computed(() => {
    const editadas = this.editadas();
    const sugeridas = this.sugerencia().imputaciones;
    return this.pendientes().map((p, i) => {
      const texto = editadas
        ? (editadas[p.comprobanteId] ?? '')
        : sugeridas[i] === 0
          ? ''
          : centavosATexto(sugeridas[i]);
      const centavos = texto.trim() === '' ? 0 : leer(texto.trim());
      let error: string | null = null;
      if (centavos === null) error = MENSAJE_DECIMALES;
      else if (centavos > aCentavos(p.pendiente)) {
        error = `No puede superar lo pendiente (${formatearMonto(p.pendiente)}).`;
      }
      return { ...p, texto, centavos: centavos ?? 0, error };
    });
  });

  private readonly imputadoCentavos = computed(() => this.filas().reduce((suma, f) => suma + f.centavos, 0));

  protected readonly errorImputado = computed<string | null>(() => {
    const total = this.totalCentavos();
    return total !== null && this.imputadoCentavos() > total ? 'Lo imputado supera el total.' : null;
  });

  /** Lo que queda sin imputar del total tipeado; 0 si el total todavía no es válido. */
  protected readonly aCuentaCentavos = computed(() => {
    const total = this.totalCentavos();
    return total === null || this.errorTotal() !== null
      ? 0
      : Math.max(0, aCuentaCentavos(total, this.filas().map((f) => f.centavos)));
  });

  private readonly valido = computed(() => {
    const total = this.totalCentavos();
    return (
      total !== null &&
      this.errorTotal() === null &&
      this.errorImputado() === null &&
      this.filas().every((f) => f.error === null)
    );
  });

  constructor() {
    // Si los pendientes cambian (el 409 los refresca), lo editado ya no vale: se vuelve a sugerir.
    effect(() => {
      this.pendientes();
      untracked(() => this.editadas.set(null));
    });
    afterNextRender(() => this.campoTotal()?.nativeElement.focus());
    // Al soltar el congelado (reiniciar, o un rechazo definitivo del reintento) «Cancelar y empezar de nuevo» se destruye:
    // si tenía el foco, caería al body y Esc y Enter (que escucha el diálogo) dejarían de llegarle. Se lo lleva al total.
    effect(() => {
      if (!this.congelado()) this.enfocarTotalTrasPintar();
    });
  }

  private enfocarTotalTrasPintar(): void {
    afterNextRender(() => this.campoTotal()?.nativeElement.focus(), { injector: this.injector });
  }

  protected escribirTotal(texto: string): void {
    if (this.congelado()) return;
    this.totalTexto.set(texto);
  }

  /** Editar una fila congela las demás tal como se ven: el total ya no las vuelve a repartir. */
  protected escribirFila(comprobanteId: number, texto: string): void {
    if (this.congelado()) return;
    const base = this.editadas() ?? Object.fromEntries(this.filas().map((f) => [f.comprobanteId, f.texto]));
    this.editadas.set({ ...base, [comprobanteId]: texto });
  }

  protected imputarEnOrden(): void {
    if (this.congelado()) return;
    this.editadas.set(null);
  }

  protected elegirMedio(medio: MedioCobranza): void {
    if (this.congelado()) return;
    this.medio.set(medio);
  }

  /** Cambia el medio con las flechas y lleva el foco al radio elegido (roving tabindex, como un grupo de radios nativo). */
  private ciclarMedio(delta: 1 | -1): void {
    if (this.congelado()) return;
    const n = this.medios.length;
    const indice = (this.medios.indexOf(this.medio()) + delta + n) % n;
    this.medio.set(this.medios[indice]);
    this.host.nativeElement.querySelectorAll<HTMLElement>('[role="radio"]')[indice]?.focus();
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
        this.cancelar.emit();
        return;
      case 'ArrowRight':
      case 'ArrowDown':
      case 'ArrowLeft':
      case 'ArrowUp':
        // Solo con el foco en el grupo de medios: dentro de un campo, las flechas mueven el cursor.
        if (!(evento.target as HTMLElement).closest('[role="radiogroup"]')) return;
        evento.preventDefault();
        evento.stopPropagation();
        this.ciclarMedio(evento.key === 'ArrowRight' || evento.key === 'ArrowDown' ? 1 : -1);
        return;
      case 'Enter':
        // En un botón, Enter es su clic: Cancelar cancela y «Imputar en orden» reaplica, no se confirma el cobro.
        if ((evento.target as HTMLElement).tagName === 'BUTTON') return;
        evento.preventDefault();
        evento.stopPropagation();
        this.confirmarCobro();
        return;
    }
  }

  protected confirmarCobro(): void {
    if (this.enVuelo() || this.bloqueado) return;
    const total = this.totalCentavos();
    if (!this.valido() || total === null) {
      this.intentado.set(true);
      this.enfocarPrimerError();
      return;
    }
    // Cuando el contenedor ya pintó, `enVuelo` dice si sigue en curso; si el rechazo fue inmediato, se puede reintentar.
    this.bloqueado = true;
    afterNextRender(() => (this.bloqueado = false), { injector: this.injector });
    this.confirmar.emit({
      medio: this.medio(),
      total: total / 100,
      imputaciones: this.filas()
        .filter((f) => f.centavos > 0)
        .map((f) => ({ comprobanteId: f.comprobanteId, importe: f.centavos / 100 })),
    });
  }

  /** Enfoca el primer campo con error; si el error recién va a aparecer, después de pintar. */
  private enfocarPrimerError(): void {
    const enfocar = () => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
    if (this.host.nativeElement.querySelector('[aria-invalid="true"]')) enfocar();
    else afterNextRender(enfocar, { injector: this.injector });
  }
}
