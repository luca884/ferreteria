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
import { TrampaFoco } from '../../shared/trampa-foco';
import { desdeTexto } from './centavos';
import { GuardaLector } from './guarda-lector';
import { MEDIOS_PAGO, MEDIO_PAGO_LABELS, MedioPago } from './venta.models';

export interface ConfirmacionCobro {
  medioPago: MedioPago;
  /** Solo en efectivo. Es para el vuelto y no viaja al servidor. */
  recibidoCentavos: number | null;
}

/** Tope del vuelto: $ 1.000.000. Un recibido que supere el total en más que eso es un error de tipeo. */
const VUELTO_MAXIMO_CENTAVOS = 100_000_000;

const centavosATexto = (c: number) => `${Math.floor(c / 100)},${String(c % 100).padStart(2, '0')}`;

/**
 * Cobro (F4). Presentacional: abre en efectivo con el recibido igual al total, así F4 + Enter cobra.
 * ← y → cambian de medio. Mientras `enVuelo` (o después de confirmar, hasta que termine) ignora Enter.
 */
@Component({
  selector: 'app-dialogo-cobro',
  imports: [MontoPipe, TrampaFoco],
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo cobro"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-cobro-titulo"
        appTrampaFoco
        (keydown)="alTeclear($event)"
      >
        <div class="cabecera">
          <div class="titulo">
            <h2 id="dialogo-cobro-titulo">Cobrar</h2>
            @if (renglones(); as n) {
              <p class="conteo">{{ n }} {{ n === 1 ? 'renglón' : 'renglones' }}</p>
            }
          </div>
          <p class="total">{{ totalCentavos() / 100 | monto }}</p>
        </div>
        @if (aviso(); as texto) {
          <p class="aviso" role="status">{{ texto }}</p>
        }
        <div class="bloque">
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
          <p class="pista-medios" aria-hidden="true"><kbd>←</kbd> <kbd>→</kbd> cambian el medio</p>
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
                spellcheck="false"
                [attr.aria-invalid]="errorRecibido() ? 'true' : null"
                [attr.aria-describedby]="errorRecibido() ? 'dialogo-cobro-error' : null"
                [value]="textoRecibido()"
                (input)="escrito.set($any($event.target).value)"
              />
            </label>
            @if (faltaCentavos() > 0) {
              <!-- Sin role="status": ese rol es atómico y re-leería todo el renglón en cada tecla. -->
              <p class="falta" aria-live="polite">Faltan <strong>{{ faltaCentavos() / 100 | monto }}</strong></p>
            } @else if (recibido() !== null && errorRecibido() === null) {
              <p class="vuelto">Vuelto <strong>{{ vueltoCentavos() / 100 | monto }}</strong></p>
            }
            @if (errorRecibido(); as mensaje) {
              <p id="dialogo-cobro-error" class="error" role="alert">{{ mensaje }}</p>
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
      gap: 18px;
      overflow: hidden;
    }
    /* Cabecera azul profunda con el TOTAL grande a la derecha. */
    .cabecera {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--e-4);
      margin: calc(var(--e-6) * -1) calc(var(--e-6) * -1) 2px;
      padding: 18px var(--e-6) var(--e-5);
      background: var(--acento-profundo);
      color: var(--acento-texto);
    }
    .titulo {
      line-height: 1.5;
    }
    .cabecera h2 {
      color: var(--acento-texto);
      font-size: var(--texto-sm);
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }
    .conteo {
      color: var(--profundo-texto-tenue);
      font-size: var(--texto-sm);
    }
    .total {
      font-size: clamp(2.25rem, 6vw, 2.875rem);
      font-weight: 700;
      line-height: 1.05;
      text-align: right;
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.03em;
    }
    .aviso {
      padding: var(--e-2) var(--e-3);
      border: 1px solid var(--advertencia-borde);
      border-left: 4px solid var(--advertencia-marca);
      border-radius: var(--radio);
      background: var(--advertencia-fondo);
      font-weight: 600;
    }
    .bloque {
      display: flex;
      flex-direction: column;
      gap: var(--e-2);
    }
    /* Cuatro botones sueltos; el elegido con borde de acento y fondo suave. */
    .medios {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--e-2);
    }
    .medio {
      min-height: 54px;
      padding: 6px 8px;
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
      background: var(--superficie);
      color: var(--texto);
      font-size: var(--texto-base);
      font-weight: 500;
    }
    .medio:hover:not(:disabled) {
      border-color: var(--borde-fuerte);
      background: var(--superficie-2);
    }
    .medio[aria-checked='true'],
    .medio[aria-checked='true']:hover {
      border-color: var(--acento);
      background: var(--acento-suave);
      color: var(--acento-profundo);
      font-weight: 700;
      box-shadow: inset 0 0 0 1px var(--acento);
    }
    .pista-medios {
      color: var(--texto-tenue);
      font-size: var(--texto-xs);
    }
    .pista-medios kbd {
      font-size: 0.9em;
    }
    .efectivo {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--e-3);
      align-items: end;
    }
    .recibido {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .recibido input {
      width: 100%;
      min-width: 0;
      height: 58px;
      padding: 0 14px;
      border-width: 2px;
      border-radius: var(--radio-lg);
      font-family: var(--fuente-mono);
      font-size: 1.625rem;
      font-weight: 600;
      text-align: right;
    }
    /* Vuelto (o lo que falta): el rótulo arriba, como «Recibido», y el importe en su recuadro. */
    .vuelto,
    .falta {
      display: flex;
      flex-direction: column;
      gap: 6px;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    .vuelto strong,
    .falta strong {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      height: 58px;
      padding: 0 14px;
      overflow: hidden;
      border: 1px solid var(--exito-borde);
      border-radius: var(--radio-lg);
      background: var(--exito-fondo);
      color: var(--exito);
      font-size: 1.875rem;
      font-weight: 700;
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.02em;
    }
    /* Falta plata: mismo lugar y tamaño que el vuelto, en rojo para que no se confunda. */
    .falta strong {
      border-color: var(--error-borde);
      background: var(--error-fondo);
      color: var(--error);
    }
    .efectivo .error {
      grid-column: 1 / -1;
    }
    .dialogo-acciones button {
      min-height: 48px;
    }
    .dialogo-acciones .cobrar {
      padding-inline: var(--e-6);
    }
  `,
})
export class DialogoCobro {
  readonly totalCentavos = input.required<number>();
  /** Cuántos renglones se cobran, para la cabecera. Sin dato no se muestra. */
  readonly renglones = input<number | null>(null);
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

  /** Lo que falta para llegar al total; mientras sea mayor que 0 no se puede cobrar. */
  protected readonly faltaCentavos = computed(() => {
    const recibido = this.recibido();
    return recibido !== null && recibido < this.totalCentavos() ? this.totalCentavos() - recibido : 0;
  });

  protected readonly errorRecibido = computed(() => {
    const recibido = this.recibido();
    if (recibido === null) return this.escrito() === null ? null : 'Escribí el importe recibido.';
    // Un error de tipeo (un cero de más) no debe terminar en un vuelto absurdo.
    return recibido - this.totalCentavos() > VUELTO_MAXIMO_CENTAVOS ? 'El importe recibido es demasiado alto.' : null;
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
    if (efectivo && (this.recibido() === null || this.errorRecibido() !== null || this.faltaCentavos() > 0)) return;
    this.bloqueado.set(true);
    this.confirmar.emit({ medioPago: this.medio(), recibidoCentavos: efectivo ? this.recibido() : null });
  }
}
