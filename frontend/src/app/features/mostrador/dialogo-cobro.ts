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
    <div class="dialogo-fondo">
      <div
        class="dialogo cobro"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-cobro-titulo"
        (keydown)="alTeclear($event)"
      >
        <div class="cabecera">
          <h2 id="dialogo-cobro-titulo">Cobrar</h2>
          <p class="total">{{ totalCentavos() / 100 | monto }}</p>
        </div>
        @if (aviso(); as texto) {
          <p class="aviso" role="status">{{ texto }}</p>
        }
        <div class="bloque">
          <span class="rotulo" aria-hidden="true">
            Medio de pago
            <span class="pista"><kbd>←</kbd> <kbd>→</kbd></span>
          </span>
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
        </div>
        @if (medio() === 'EFECTIVO') {
          <div class="efectivo">
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
              <p class="vuelto">Vuelto <strong>{{ vueltoCentavos() / 100 | monto }}</strong></p>
            }
            @if (errorRecibido(); as mensaje) {
              <p class="error" role="alert">{{ mensaje }}</p>
            }
          </div>
        }
        <div class="dialogo-acciones">
          <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
            Cancelar
          </button>
          <button type="button" class="cobrar primario" data-tecla="Enter" aria-keyshortcuts="Enter" (click)="confirmarCobro()">
            Cobrar
          </button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .cobro {
      width: min(540px, 100%);
      gap: var(--e-4);
    }
    /* Total grande arriba, como el diálogo de cobro de GDS (f414). */
    .cabecera {
      margin: calc(var(--e-6) * -1) calc(var(--e-6) * -1) 0;
      padding: var(--e-4) var(--e-6) var(--e-5);
      border-radius: var(--radio-lg) var(--radio-lg) 0 0;
      background: var(--barra);
      color: #fff;
    }
    .cabecera h2 {
      color: #fdba74;
      font-size: var(--texto-sm);
      letter-spacing: 0.12em;
      text-transform: uppercase;
    }
    .total {
      margin: var(--e-1) 0 0;
      font-size: 3.25rem;
      font-weight: 700;
      line-height: 1.05;
      text-align: right;
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.02em;
    }
    .aviso {
      padding: var(--e-2) var(--e-3);
      border: 1px solid var(--advertencia-borde);
      border-left: 4px solid #d97706;
      border-radius: var(--radio);
      background: var(--advertencia-fondo);
      font-weight: 600;
    }
    .bloque {
      display: flex;
      flex-direction: column;
      gap: var(--e-2);
    }
    .rotulo {
      display: flex;
      align-items: center;
      justify-content: space-between;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    /* Selector segmentado: un solo bloque, el medio elegido relleno con el acento. */
    .medios {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      padding: 3px;
      gap: 3px;
      border: 1px solid var(--borde-fuerte);
      border-radius: var(--radio-lg);
      background: var(--superficie-3);
    }
    .medio {
      min-height: 44px;
      padding: 6px 8px;
      border: 0;
      border-radius: var(--radio);
      background: transparent;
      color: var(--texto);
      font-size: 1rem;
    }
    .medio:hover:not(:disabled) {
      background: var(--superficie);
    }
    .medio[aria-checked='true'],
    .medio[aria-checked='true']:hover {
      background: var(--acento);
      color: var(--acento-texto);
      box-shadow: var(--sombra-1);
    }
    .medio:focus-visible {
      box-shadow: 0 0 0 3px #fdba74;
    }
    .efectivo {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: var(--e-3);
      align-items: end;
    }
    .pista kbd {
      font-size: 0.95rem;
    }
    .recibido {
      display: flex;
      flex-direction: column;
      gap: var(--e-1);
    }
    .recibido input {
      width: 100%;
      min-width: 0;
      height: 56px;
      font-size: 1.75rem;
      font-weight: 700;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .vuelto {
      display: flex;
      flex-direction: column;
      justify-content: center;
      min-height: 56px;
      padding: 4px var(--e-3);
      border: 1px solid var(--exito-borde);
      border-radius: var(--radio);
      background: var(--exito-fondo);
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
      text-align: right;
    }
    .vuelto strong {
      color: var(--exito);
      font-size: 1.75rem;
      line-height: 1.1;
      font-variant-numeric: tabular-nums;
    }
    .efectivo .error {
      grid-column: 1 / -1;
    }
    .dialogo-acciones button {
      min-height: 48px;
      font-size: 1.0625rem;
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
