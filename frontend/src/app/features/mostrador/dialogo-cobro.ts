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
import { FormaPago } from '../formas-pago/forma-pago.models';
import { desdeTexto, recargoDeCobro } from './centavos';
import { GuardaLector } from './guarda-lector';
import { MedioPago, PagoCobro } from './venta.models';

export interface ConfirmacionCobro {
  /** La forma de pago única, o null si es un pago mixto (entonces vienen `pagos`). */
  formaPagoId: number | null;
  /** El medio de la forma única (para saber si es efectivo), o null en el pago mixto. */
  medio: MedioPago | null;
  /** Solo en efectivo único. Es para el vuelto y no viaja al servidor. */
  recibidoCentavos: number | null;
  /** El recargo de las formas elegidas (GDS-11), en centavos: el total que se cobra lo incluye. 0 si no hay. */
  recargoCentavos: number;
  /** Solo en el pago mixto (GDS-07): las formas con su parte del total sin recargo, que suman el total. */
  pagos?: PagoCobro[];
  /** Solo si el cajero tildó «No facturable» (NF-003): la venta cobra igual pero no se factura nunca. */
  noFacturable?: true;
}

/** «Mixto» es la última opción: reparte el total entre varias formas. Las demás son el id de una forma. */
type Opcion = number | 'MIXTO';

const MENSAJE_SUMA = 'Los pagos tienen que sumar el total.';
const MENSAJE_IMPORTES = 'Revisá los importes: usá números con hasta 2 decimales.';

type Montos = Record<number, string | null>;

/** Tope del vuelto: $ 1.000.000. Un recibido que supere el total en más que eso es un error de tipeo. */
const VUELTO_MAXIMO_CENTAVOS = 100_000_000;

const PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/** El recargo de la forma en centésimas de punto porcentual (1000 = 10 %). */
const puntosBase = (forma: FormaPago): number => Math.round(forma.recargoPorcentaje * 100);

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
          <p class="total">{{ aCobrarCentavos() / 100 | monto }}</p>
        </div>
        @if (recargoCentavos() > 0) {
          <p class="recargo">
            Recargo{{ porcentajeDeLaForma() }} <strong>{{ recargoCentavos() / 100 | monto }}</strong>
          </p>
        }
        @if (aviso(); as texto) {
          <p class="aviso" role="status">{{ texto }}</p>
        }
        <div class="bloque">
          <div class="medios" role="radiogroup" aria-label="Medio de pago">
            @for (o of opciones(); track o.id) {
              <button
                #botonMedio
                type="button"
                role="radio"
                class="medio"
                [attr.aria-checked]="o.id === seleccion()"
                [tabindex]="o.id === seleccion() ? 0 : -1"
                (click)="elegir(o.id)"
              >
                {{ o.nombre }}
                @if (o.recargo) {
                  <small class="marca-recargo">+{{ o.recargo }}&nbsp;%</small>
                }
              </button>
            }
          </div>
          <p class="pista-medios" aria-hidden="true"><kbd>←</kbd> <kbd>→</kbd> cambian el medio</p>
        </div>
        @if (seleccion() === 'MIXTO') {
          <div class="mixto">
            @for (f of formas(); track f.id) {
              <label class="fila-pago">
                {{ f.nombre }}
                <input
                  #filaMonto
                  type="text"
                  inputmode="decimal"
                  autocomplete="off"
                  spellcheck="false"
                  [attr.data-forma]="f.id"
                  [value]="montos()[f.id] ?? ''"
                  (input)="escribirMonto(f.id, $any($event.target).value)"
                />
              </label>
            }
            <p class="resumen-mixto" [class.falta-mixto]="restoCentavos() > 0" [class.pasa-mixto]="restoCentavos() < 0" aria-live="polite">
              @if (restoCentavos() > 0) {
                Falta {{ restoCentavos() / 100 | monto }}
              } @else if (restoCentavos() < 0) {
                Se pasa {{ -restoCentavos() / 100 | monto }}
              } @else {
                Completo
              }
            </p>
            @if (errorMixto(); as mensaje) {
              <p class="error" role="alert">{{ mensaje }}</p>
            }
          </div>
        } @else if (formaElegida()?.medio === 'EFECTIVO') {
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
        <label class="no-facturable">
          <input type="checkbox" [checked]="noFacturable()" (change)="noFacturable.set($any($event.target).checked)" />
          No facturable
          <small>cobra igual, pero no se factura</small>
        </label>
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
      grid-template-columns: repeat(auto-fit, minmax(96px, 1fr));
      gap: var(--e-2);
    }
    .medio {
      min-height: 54px;
      padding: 6px 8px;
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
      background: var(--superficie);
      color: var(--texto);
      font-size: var(--texto-sm);
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
    .marca-recargo {
      display: block;
      font-size: var(--texto-xs);
      font-weight: 600;
    }
    /* El recargo de la forma elegida: la cuenta de cuánto se suma al total. */
    .recargo {
      display: flex;
      justify-content: space-between;
      margin: 0;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    .recargo strong {
      color: var(--texto);
      font-variant-numeric: tabular-nums;
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
    .no-facturable {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: var(--texto-sm);
      cursor: pointer;
    }
    .no-facturable small {
      color: var(--texto-suave);
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
    /* Pago mixto: una fila por forma y, abajo, lo que falta o se pasa. */
    .mixto {
      display: grid;
      gap: var(--e-2);
    }
    .fila-pago {
      display: grid;
      grid-template-columns: 9rem minmax(0, 1fr);
      align-items: center;
      gap: var(--e-3);
    }
    .fila-pago input {
      height: 46px;
      padding: 0 14px;
      font-family: var(--fuente-mono);
      font-size: 1.25rem;
      font-weight: 600;
      text-align: right;
    }
    .resumen-mixto {
      margin: 0;
      font-size: var(--texto-md);
      font-weight: 700;
      text-align: right;
      color: var(--exito);
      font-variant-numeric: tabular-nums;
    }
    .resumen-mixto.falta-mixto,
    .resumen-mixto.pasa-mixto {
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
  /** El total del ticket, sin el recargo de la forma de pago (GDS-11). */
  readonly totalCentavos = input.required<number>();
  /** Las formas de pago activas, en el orden del Dueño: son las opciones del cobro. */
  readonly formas = input<readonly FormaPago[]>([]);
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

  /** Lo que eligió el cajero; null mientras no eligió (se usa la primera forma en efectivo). */
  protected readonly elegida = signal<Opcion | null>(null);
  protected readonly escrito = signal<string | null>(null);
  /** «No facturable»: arranca destildado en cada cobro, para que sea una decisión de esa venta. */
  protected readonly noFacturable = signal(false);
  /** Pago mixto: lo escrito en cada fila, por id de forma; sin clave o null es una fila sin tocar. */
  protected readonly montos = signal<Montos>({});
  private readonly mensajeMixto = signal<string | null>(null);

  /** Una opción por forma, con la marca de su recargo, y «Mixto» al final. */
  protected readonly opciones = computed<{ id: Opcion; nombre: string; recargo: string | null }[]>(() => [
    ...this.formas().map((f) => ({
      id: f.id as Opcion,
      nombre: f.nombre,
      recargo: f.recargoPorcentaje > 0 ? PORCENTAJE.format(f.recargoPorcentaje) : null,
    })),
    { id: 'MIXTO' as Opcion, nombre: 'Mixto', recargo: null },
  ]);

  /** Abre en la primera forma en efectivo (F4 y Enter cobra); sin ninguna, en la primera. */
  private readonly formaInicial = computed(() => {
    const formas = this.formas();
    return (formas.find((f) => f.medio === 'EFECTIVO') ?? formas[0])?.id ?? null;
  });

  /** Lo elegido si sigue existiendo; si la forma elegida desaparece mientras está abierto, vuelve a la inicial. */
  protected readonly seleccion = computed<Opcion | null>(() => {
    const elegida = this.elegida();
    if (elegida === 'MIXTO') return 'MIXTO';
    if (elegida !== null && this.formas().some((f) => f.id === elegida)) return elegida;
    return this.formaInicial();
  });

  protected readonly formaElegida = computed(() => {
    const seleccion = this.seleccion();
    return typeof seleccion === 'number' ? (this.formas().find((f) => f.id === seleccion) ?? null) : null;
  });

  private readonly importe = viewChild<ElementRef<HTMLInputElement>>('importe');
  private readonly filasMonto = viewChildren<ElementRef<HTMLInputElement>>('filaMonto');
  private readonly botonesMedio = viewChildren<ElementRef<HTMLButtonElement>>('botonMedio');
  private readonly guarda = new GuardaLector();
  private readonly bloqueado = signal(false);
  private estuvoEnVuelo = false;

  /** El importe de cada fila en centavos; null si lo escrito no es un número. Una fila vacía es 0. */
  private readonly centavosMixto = computed<Record<number, number | null>>(() => {
    const montos = this.montos();
    const leer = (texto: string | null | undefined): number | null => {
      if (texto === null || texto === undefined || texto.trim() === '') return 0;
      try {
        const c = desdeTexto(texto, 2);
        return c >= 0 ? c : null;
      } catch {
        return null;
      }
    };
    const filas: Record<number, number | null> = {};
    for (const f of this.formas()) filas[f.id] = leer(montos[f.id]);
    return filas;
  });

  protected readonly importesMixtoValidos = computed(() =>
    this.formas().every((f) => this.centavosMixto()[f.id] !== null),
  );

  /** Lo que falta (positivo) o lo que se pasa (negativo) del total sin recargo. */
  protected readonly restoCentavos = computed(() => {
    const c = this.centavosMixto();
    return this.totalCentavos() - this.formas().reduce((acc, f) => acc + (c[f.id] ?? 0), 0);
  });

  /**
   * El recargo (GDS-11): con una forma, su % sobre todo el total; en el pago mixto, la suma del de cada parte cargada,
   * redondeado por parte, igual que el servidor. Es lo que se suma al total del ticket.
   */
  protected readonly recargoCentavos = computed(() => {
    if (this.seleccion() === 'MIXTO') {
      const c = this.centavosMixto();
      return this.formas().reduce((acc, f) => acc + recargoDeCobro(c[f.id] ?? 0, puntosBase(f)), 0);
    }
    const forma = this.formaElegida();
    return forma ? recargoDeCobro(this.totalCentavos(), puntosBase(forma)) : 0;
  });

  /** El total que se cobra: el del ticket más el recargo. */
  protected readonly aCobrarCentavos = computed(() => this.totalCentavos() + this.recargoCentavos());

  /** « (10 %)» al lado del recargo de una forma única; nada en el mixto, donde cada parte tiene el suyo. */
  protected readonly porcentajeDeLaForma = computed(() => {
    const forma = this.formaElegida();
    return forma && forma.recargoPorcentaje > 0 ? ` (${PORCENTAJE.format(forma.recargoPorcentaje)} %)` : '';
  });

  /** Mientras no escribió, el recibido acompaña al total a cobrar (también si cambia por un 409). */
  protected readonly textoRecibido = computed(() => this.escrito() ?? centavosATexto(this.aCobrarCentavos()));

  protected readonly recibido = computed<number | null>(() => {
    try {
      return desdeTexto(this.textoRecibido(), 2);
    } catch {
      return null;
    }
  });

  protected readonly vueltoCentavos = computed(() => Math.max(0, (this.recibido() ?? 0) - this.aCobrarCentavos()));

  /** Lo que falta para llegar al total a cobrar; mientras sea mayor que 0 no se puede cobrar. */
  protected readonly faltaCentavos = computed(() => {
    const recibido = this.recibido();
    return recibido !== null && recibido < this.aCobrarCentavos() ? this.aCobrarCentavos() - recibido : 0;
  });

  protected readonly errorRecibido = computed(() => {
    const recibido = this.recibido();
    if (recibido === null) return this.escrito() === null ? null : 'Escribí el importe recibido.';
    // Un error de tipeo (un cero de más) no debe terminar en un vuelto absurdo.
    return recibido - this.aCobrarCentavos() > VUELTO_MAXIMO_CENTAVOS ? 'El importe recibido es demasiado alto.' : null;
  });

  protected readonly errorMixto = computed(() => this.mensajeMixto());

  constructor() {
    effect(() => {
      if (this.enVuelo()) this.estuvoEnVuelo = true;
      else if (this.estuvoEnVuelo) {
        this.estuvoEnVuelo = false;
        this.bloqueado.set(false);
      }
    });
    // Foco: el importe en efectivo; con otra forma, el botón de esa forma.
    afterRenderEffect(() => {
      const actual = this.seleccion();
      if (actual === 'MIXTO') {
        this.filasMonto()[0]?.nativeElement.focus();
      } else if (this.formaElegida()?.medio === 'EFECTIVO') {
        const campo = this.importe()?.nativeElement;
        campo?.focus();
        campo?.select();
      } else {
        const indice = this.opciones().findIndex((o) => o.id === actual);
        this.botonesMedio()[indice]?.nativeElement.focus();
      }
    });
  }

  protected elegir(opcion: Opcion): void {
    this.elegida.set(opcion);
  }

  private ciclar(delta: 1 | -1): void {
    const ids = this.opciones().map((o) => o.id);
    const n = ids.length;
    const actual = ids.indexOf(this.seleccion() as Opcion);
    this.elegida.set(ids[(actual + delta + n) % n]);
  }

  protected escribirMonto(id: number, texto: string): void {
    this.montos.update((montos) => ({ ...montos, [id]: texto }));
    this.mensajeMixto.set(null);
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
    if (this.seleccion() === 'MIXTO') {
      this.enterEnMixto(evento.target as HTMLElement);
      return;
    }
    this.confirmarCobro();
  }

  /**
   * Enter en una fila del pago mixto (como en GDS): si todo está cubierto cobra; si falta, pasa a la fila siguiente y
   * le sugiere lo que falta, para que cargar el resto sea solo Enter.
   */
  private enterEnMixto(origen: HTMLElement): void {
    if (this.importesMixtoValidos() && this.restoCentavos() === 0) {
      this.confirmarCobro();
      return;
    }
    const filas = this.filasMonto().map((f) => f.nativeElement);
    const i = filas.indexOf(origen as HTMLInputElement);
    const siguiente = filas[i + 1];
    if (!this.importesMixtoValidos() || !siguiente || this.restoCentavos() < 0) {
      this.confirmarCobro();
      return;
    }
    const forma = this.formas()[i + 1];
    if ((this.montos()[forma.id] ?? null) === null) {
      const texto = centavosATexto(this.restoCentavos());
      this.escribirMonto(forma.id, texto);
      siguiente.value = texto;
    }
    siguiente.focus();
    siguiente.select();
  }

  protected confirmarCobro(): void {
    if (this.enVuelo() || this.bloqueado()) return;
    if (this.seleccion() === 'MIXTO') {
      this.confirmarMixto();
      return;
    }
    const forma = this.formaElegida();
    if (!forma) return;
    const efectivo = forma.medio === 'EFECTIVO';
    if (efectivo && (this.recibido() === null || this.errorRecibido() !== null || this.faltaCentavos() > 0)) return;
    this.bloqueado.set(true);
    this.confirmar.emit({
      formaPagoId: forma.id,
      medio: forma.medio,
      recibidoCentavos: efectivo ? this.recibido() : null,
      recargoCentavos: this.recargoCentavos(),
      ...(this.noFacturable() ? { noFacturable: true as const } : {}),
    });
  }

  private confirmarMixto(): void {
    if (!this.importesMixtoValidos()) {
      this.mensajeMixto.set(MENSAJE_IMPORTES);
      return;
    }
    if (this.restoCentavos() !== 0) {
      this.mensajeMixto.set(MENSAJE_SUMA);
      return;
    }
    const c = this.centavosMixto();
    const pagos = this.formas()
      .filter((f) => (c[f.id] ?? 0) > 0)
      .map((f) => ({ formaPagoId: f.id, centavos: c[f.id] as number }));
    this.bloqueado.set(true);
    this.confirmar.emit({
      formaPagoId: null,
      medio: null,
      recibidoCentavos: null,
      recargoCentavos: this.recargoCentavos(),
      pagos,
      ...(this.noFacturable() ? { noFacturable: true as const } : {}),
    });
  }
}
