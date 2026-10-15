import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  input,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { MontoPipe } from '../../core/formato/monto';
import { desdeTexto } from './centavos';
import { GuardaLector } from './guarda-lector';
import { MEDIOS_PAGO, MEDIO_PAGO_LABELS, MedioPago } from './venta.models';

export interface ConfirmacionCobro {
  medioPago: MedioPago;
  /** Solo en efectivo. Es para el vuelto y no viaja al servidor. */
  recibidoCentavos: number | null;
}

const centavosATexto = (c: number) => `${Math.floor(c / 100)},${String(c % 100).padStart(2, '0')}`;

/**
 * Cobro (F4). Presentacional: abre en efectivo con el recibido igual al total, así F4 + Enter cobra.
 * ← y → cambian de medio. Mientras `enVuelo` (o después de confirmar, hasta que termine) ignora Enter.
 */
@Component({
  selector: 'app-dialogo-cobro',
  imports: [MontoPipe],
  template: `
    <div class="fondo">
      <div
        class="dialogo"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-cobro-titulo"
        (keydown)="alTeclear($event)"
      >
        <h2 id="dialogo-cobro-titulo">Cobrar</h2>
        <p class="total">{{ totalCentavos() / 100 | monto }}</p>
        @if (aviso(); as texto) {
          <p class="aviso" role="status">{{ texto }}</p>
        }
        <div class="medios" role="radiogroup" aria-label="Medio de pago">
          @for (m of medios; track m) {
            <button
              #botonMedio
              type="button"
              role="radio"
              class="medio"
              [attr.aria-checked]="m === medio()"
              [tabindex]="m === medio() ? 0 : -1"
              (click)="elegir(m)"
            >
              {{ etiquetas[m] }}
            </button>
          }
        </div>
        @if (medio() === 'EFECTIVO') {
          <label class="recibido">
            Recibido
            <input
              #importe
              type="text"
              inputmode="decimal"
              autocomplete="off"
              [value]="textoRecibido()"
              (input)="escrito.set($any($event.target).value)"
            />
          </label>
          @if (recibido() !== null && recibido()! >= totalCentavos()) {
            <p class="vuelto">Vuelto {{ vueltoCentavos() / 100 | monto }}</p>
          }
          @if (errorRecibido(); as mensaje) {
            <p class="error" role="alert">{{ mensaje }}</p>
          }
        }
        <div class="acciones">
          <button type="button" class="cancelar" (click)="cancelar.emit()">Cancelar</button>
          <button type="button" class="cobrar" (click)="confirmarCobro()">Cobrar</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .fondo {
      position: fixed;
      inset: 0;
      display: grid;
      place-items: center;
      background: rgb(0 0 0 / 0.4);
      z-index: 20;
    }
    .dialogo {
      background: var(--superficie);
      border-radius: 10px;
      padding: 20px;
      min-width: 360px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    h2,
    p {
      margin: 0;
    }
    .total {
      font-size: 2rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .medios {
      display: flex;
      gap: 6px;
    }
    .medio {
      flex: 1;
      padding: 8px;
      border: 1px solid var(--borde);
      border-radius: 6px;
      background: var(--superficie);
      font: inherit;
      cursor: pointer;
    }
    .medio[aria-checked='true'] {
      border-color: var(--acento);
      box-shadow: inset 0 0 0 2px var(--acento);
      font-weight: 700;
    }
    .recibido {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .recibido input {
      font: inherit;
      font-size: 1.4rem;
      padding: 8px;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .vuelto {
      font-size: 1.3rem;
      font-weight: 600;
    }
    .error {
      color: #b00020;
    }
    .aviso {
      background: #fff1e8;
      padding: 8px;
      border-radius: 6px;
    }
    .acciones {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
  `,
})
export class DialogoCobro {
  readonly totalCentavos = input.required<number>();
  /** El contenedor lo prende mientras el POST está en curso. */
  readonly enVuelo = input(false);
  /** Mensaje del contenedor (por ejemplo, un precio que cambió en el servidor). */
  readonly aviso = input<string | null>(null);

  readonly confirmar = output<ConfirmacionCobro>();
  readonly cancelar = output<void>();
  /** Un escaneo llegó con el diálogo abierto: se ignora y el contenedor avisa. */
  readonly escaneoDescartado = output<void>();

  protected readonly medios = MEDIOS_PAGO;
  protected readonly etiquetas = MEDIO_PAGO_LABELS;
  protected readonly medio = signal<MedioPago>('EFECTIVO');
  protected readonly escrito = signal<string | null>(null);

  private readonly importe = viewChild<ElementRef<HTMLInputElement>>('importe');
  private readonly botonesMedio = viewChildren<ElementRef<HTMLButtonElement>>('botonMedio');
  private readonly guarda = new GuardaLector();
  private readonly bloqueado = signal(false);
  private estuvoEnVuelo = false;

  /** Mientras no escribió, el recibido acompaña al total (también si cambia por un 409). */
  protected readonly textoRecibido = computed(() => this.escrito() ?? centavosATexto(this.totalCentavos()));

  protected readonly recibido = computed<number | null>(() => {
    try {
      return desdeTexto(this.textoRecibido(), 2);
    } catch {
      return null;
    }
  });

  protected readonly vueltoCentavos = computed(() => Math.max(0, (this.recibido() ?? 0) - this.totalCentavos()));

  protected readonly errorRecibido = computed(() => {
    const recibido = this.recibido();
    if (recibido === null) return this.escrito() === null ? null : 'Escribí el importe recibido.';
    return recibido < this.totalCentavos() ? 'El importe recibido no alcanza.' : null;
  });

  constructor() {
    effect(() => {
      if (this.enVuelo()) this.estuvoEnVuelo = true;
      else if (this.estuvoEnVuelo) {
        this.estuvoEnVuelo = false;
        this.bloqueado.set(false);
      }
    });
    // Foco: el importe en efectivo; con otro medio, el botón de ese medio.
    afterRenderEffect(() => {
      const actual = this.medio();
      if (actual === 'EFECTIVO') {
        const campo = this.importe()?.nativeElement;
        campo?.focus();
        campo?.select();
      } else {
        this.botonesMedio()[MEDIOS_PAGO.indexOf(actual)]?.nativeElement.focus();
      }
    });
  }

  protected elegir(m: MedioPago): void {
    this.medio.set(m);
  }

  private ciclar(delta: 1 | -1): void {
    const n = MEDIOS_PAGO.length;
    this.medio.set(MEDIOS_PAGO[(MEDIOS_PAGO.indexOf(this.medio()) + delta + n) % n]);
  }

  protected alTeclear(evento: KeyboardEvent): void {
    switch (evento.key) {
      case 'Escape':
        evento.preventDefault();
        evento.stopPropagation();
        this.cancelar.emit();
        return;
      case 'ArrowRight':
      case 'ArrowLeft':
        evento.preventDefault();
        evento.stopPropagation();
        this.ciclar(evento.key === 'ArrowRight' ? 1 : -1);
        return;
    }
    const eraEscaneo = this.guarda.alTeclear(evento);
    if (evento.key !== 'Enter') return;
    evento.preventDefault();
    evento.stopPropagation();
    if (eraEscaneo) {
      this.escrito.set(null);
      const campo = this.importe()?.nativeElement;
      if (campo) campo.value = this.textoRecibido();
      this.escaneoDescartado.emit();
      return;
    }
    this.confirmarCobro();
  }

  protected confirmarCobro(): void {
    if (this.enVuelo() || this.bloqueado()) return;
    const efectivo = this.medio() === 'EFECTIVO';
    if (efectivo && (this.recibido() === null || this.errorRecibido() !== null)) return;
    this.bloqueado.set(true);
    this.confirmar.emit({ medioPago: this.medio(), recibidoCentavos: efectivo ? this.recibido() : null });
  }
}
