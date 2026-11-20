import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import {
  Component,
  DestroyRef,
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
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { MotivoDeAccion, ariaDe } from '../../shared/motivo-de-accion';
import {
  AjusteStockResponse,
  MOTIVOS_AJUSTE,
  MOTIVO_AJUSTE_LABELS,
  ModoAjuste,
  MotivoAjuste,
  Unidad,
} from './articulo.models';
import { ArticulosService } from './articulos.service';

/** Lo que el diálogo necesita del artículo. */
export interface ArticuloAAjustar {
  id: number;
  descripcion: string;
  unidad: Unidad;
  stockActual: number;
  /** Se muestra debajo del nombre si viene (el listado lo tiene a mano). */
  codigoInterno?: string;
}

type Campo = 'stockReal' | 'cantidad' | 'motivo' | 'detalle';
type Signo = 'ENTRA' | 'SALE';

/** El motivo elige el modo por defecto (el usuario lo puede cambiar): conteo cuenta, el resto suma o resta. */
const MODO_POR_MOTIVO: Record<MotivoAjuste, ModoAjuste> = {
  INGRESO_MERCADERIA: 'CANTIDAD',
  ROTURA: 'CANTIDAD',
  CONTEO: 'STOCK_REAL',
  OTRO: 'CANTIDAD',
};

/** En el orden en que se ven: primero sumar o restar, después el conteo. */
const MODOS: readonly ModoAjuste[] = ['CANTIDAD', 'STOCK_REAL'];
const SIGNOS: readonly Signo[] = ['ENTRA', 'SALE'];

const ETIQUETAS_MODO: Record<ModoAjuste, { titulo: string; detalle: string }> = {
  CANTIDAD: { titulo: 'Sumar o restar', detalle: 'Entra o sale mercadería' },
  STOCK_REAL: { titulo: 'Conteo', detalle: 'Cargás lo que contaste' },
};

/** Flechas de un grupo de opciones: → y ↓ avanzan, ← y ↑ retroceden. */
const PASO_POR_TECLA: Partial<Record<string, 1 | -1>> = {
  ArrowRight: 1,
  ArrowDown: 1,
  ArrowLeft: -1,
  ArrowUp: -1,
};

/** DECIMAL(15, 3) del backend. */
const MAXIMO = 999_999_999_999.999;

const NUMERO = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 3 });

/** La pantalla del celular (la misma consulta de todo el CSS móvil); sin `matchMedia` (pruebas, impresión) no lo es. */
const consultaCelular = (): MediaQueryList | null =>
  typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 639.98px)') : null;

/** Las acciones del celular que pueden quedar apagadas y explican por qué (S5 de movil-sectores). */
type AccionApagable = 'restar' | 'sumar' | 'guardar';

/** Lo escrito en el campo numérico, ya leído: «-0» también lleva signo (`negativo`), aunque valga cero. */
interface Lectura {
  valor: number;
  decimales: number;
  negativo: boolean;
}

/**
 * «1.500» sin coma puede ser 1500 (punto de miles) o 1,5: no se adivina, se pregunta. Solo con 1 a 3 cifras sin 0
 * delante y exactamente 3 después del punto; «0.250» y «1000.250» no pueden ser de miles y se leen con decimales.
 * «-1.500» no se pregunta: se lea como se lea es negativo, y avisa eso.
 */
const AMBIGUO = /^([1-9]\d{0,2})\.(\d{3})$/;

/** La pregunta para un número ambiguo, o null si no lo es. */
function preguntaSiEsAmbiguo(texto: string): string | null {
  const partes = AMBIGUO.exec(texto.trim());
  if (!partes) return null;
  const [, entero, resto] = partes;
  const decimales = resto.replace(/0+$/, '');
  const conComa = decimales ? `${entero},${decimales}` : entero;
  return `¿Quisiste decir ${entero}${resto} o ${conComa}? Usá la coma para los decimales.`;
}

/** «145,5» o «145.5» → número y cuántos decimales se escribieron; null si no es un número o si es ambiguo. */
function leerNumero(texto: string): Lectura | null {
  if (preguntaSiEsAmbiguo(texto)) return null;
  const limpio = texto.trim().includes(',') ? texto.trim().replace(/\./g, '').replace(',', '.') : texto.trim();
  if (!/^-?\d+(\.\d+)?$/.test(limpio)) return null;
  return {
    valor: Number(limpio),
    decimales: limpio.split('.')[1]?.replace(/0+$/, '').length ?? 0,
    negativo: limpio.startsWith('-'),
  };
}

type ErrorDeFormato = 'NEGATIVO' | 'MAXIMO' | 'DECIMALES_UNIDAD' | 'DECIMALES';

/**
 * La única regla de formato de una cantidad o de un stock real (la misma que AjusteStock.java): sin signo, hasta
 * el máximo, entero en UNIDAD y hasta 3 decimales. La usan la vista previa y `validar()`, así no pueden discrepar.
 */
function errorDeFormato(leido: Lectura, unidad: Unidad): ErrorDeFormato | null {
  if (leido.negativo) return 'NEGATIVO';
  if (leido.valor > MAXIMO) return 'MAXIMO';
  if (unidad === 'UNIDAD' && leido.decimales > 0) return 'DECIMALES_UNIDAD';
  if (leido.decimales > 3) return 'DECIMALES';
  return null;
}

interface MensajesDelCampo {
  vacio: string;
  noEsNumero: string;
  formato: Record<ErrorDeFormato, string>;
}

const MENSAJES: Record<'cantidad' | 'stockReal', MensajesDelCampo> = {
  cantidad: {
    vacio: 'Escribí la cantidad que entra o sale.',
    noEsNumero: 'Escribí un número, por ejemplo 12 o 2,5.',
    formato: {
      NEGATIVO: 'Escribí la cantidad sin signo y elegí si entra o sale.',
      MAXIMO: 'La cantidad es demasiado grande: el máximo es 999.999.999.999,999.',
      DECIMALES_UNIDAD: 'Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.',
      DECIMALES: 'La cantidad admite hasta 3 decimales.',
    },
  },
  stockReal: {
    vacio: 'Escribí el stock real que contaste.',
    noEsNumero: 'Escribí un número, por ejemplo 12 o 145,5.',
    formato: {
      NEGATIVO: 'El stock real no puede ser negativo.',
      MAXIMO: 'El stock real es demasiado grande: el máximo es 999.999.999.999,999.',
      DECIMALES_UNIDAD: 'Este artículo se cuenta por unidad: el stock real tiene que ser un número entero.',
      DECIMALES: 'El stock real admite hasta 3 decimales.',
    },
  },
};

/**
 * CU-09 Ajustar stock, como panel lateral derecho. El Dueño suma o resta lo que entra o sale, o carga el stock
 * real que contó, y ve en vivo cómo queda («25 → 49 unidades»). Valida como el backend (UNIDAD enteros, el resto
 * hasta 3 decimales, motivo obligatorio, detalle con OTRO) y muestra todos los errores juntos, cada uno junto a su
 * campo. Es un <dialog> nativo abierto con showModal(): el navegador encierra el foco y Esc lo cancela; al
 * cerrarse el foco vuelve a quien lo abrió.
 *
 * movil-sectores T5 (5e): en el celular (`celular()`, que sigue a la pantalla) el mismo <dialog> cubre toda la pantalla (artboard 07 de
 * movil-articulos): barra «Volver», «Cómo cargarlo» y «Entra o sale» como control segmentado, − y + junto a la
 * cantidad y el pie fijo. Es el mismo formulario con el mismo estado y los mismos manejadores; lo único que se agrega
 * en el DOM es la barra, los rótulos de los grupos, los dos botones − y + y el «Queda». El escritorio no cambia (S5).
 */
@Component({
  selector: 'app-dialogo-ajuste-stock',
  imports: [NgTemplateOutlet],
  template: `
    <dialog
      #dialogo
      class="panel-lateral"
      [class.ajuste-celular]="celular()"
      aria-labelledby="ajuste-titulo ajuste-articulo"
      (cancel)="alCancelar($event)"
    >
      <form class="ajuste" novalidate (submit)="$event.preventDefault(); confirmar()">
        @if (celular()) {
          <div class="ficha-barra">
            <button type="button" class="ficha-volver" aria-label="Volver" (click)="cancelar.emit()">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="m15 6-6 6 6 6" />
              </svg>
            </button>
            <h2 id="ajuste-titulo">Ajustar stock</h2>
          </div>
        }
        <header>
          @if (!celular()) {
            <h2 id="ajuste-titulo" class="sobretitulo">Ajustar stock</h2>
          }
          <p id="ajuste-articulo" class="articulo">{{ articulo().descripcion }}</p>
          <p class="referencia">
            @if (articulo().codigoInterno; as codigo) {<span class="codigo">{{ codigo }}</span> · hoy } @else {Hoy hay }<span class="stock-actual">{{ conUnidad(articulo().stockActual) }}</span>
          </p>
        </header>

        <div class="campo">
          <label for="motivo">Motivo</label>
          <select
            id="motivo"
            name="motivo"
            [value]="motivo() ?? ''"
            [attr.aria-invalid]="errores()['motivo'] ? 'true' : null"
            [attr.aria-describedby]="errores()['motivo'] ? 'motivo-error' : null"
            (change)="cambiar('motivo', $any($event.target).value)"
          >
            <option value="" disabled>Elegí un motivo…</option>
            @for (m of motivos; track m) {
              <option [value]="m">{{ motivoLabels[m] }}</option>
            }
          </select>
          @if (errores()['motivo']; as mensaje) {
            <span id="motivo-error" class="campo-error">{{ mensaje }}</span>
          }
        </div>

        <div class="modos" role="radiogroup" aria-label="Cómo cargar el ajuste" (keydown)="alTeclearModo($event)">
          @if (celular()) {
            <span class="etq" aria-hidden="true">Cómo cargarlo</span>
          }
          @for (m of modos; track m) {
            <button
              type="button"
              role="radio"
              class="modo"
              [attr.data-modo]="m"
              [attr.aria-checked]="modo() === m"
              [attr.tabindex]="modo() === m ? 0 : -1"
              (click)="elegirModo(m)"
            >
              <span>{{ etiquetasModo[m].titulo }}</span>
              <span class="modo-detalle">{{ etiquetasModo[m].detalle }}</span>
            </button>
          }
        </div>

        @if (modo() === 'STOCK_REAL') {
          <div class="campo" [class.con-paso]="celular()">
            <label for="stock-real">Stock real (lo que contaste)</label>
            @if (celular()) {
              <ng-container *ngTemplateOutlet="restar" />
            }
            <input
              #numero
              id="stock-real"
              name="stockReal"
              class="numero"
              type="text"
              inputmode="decimal"
              autocomplete="off"
              spellcheck="false"
              [value]="valorTexto()"
              [attr.aria-invalid]="errores()['stockReal'] ? 'true' : null"
              [attr.aria-describedby]="errores()['stockReal'] ? 'stock-real-error' : null"
              (input)="cambiar('stockReal', $any($event.target).value)"
            />
            @if (celular()) {
              <ng-container *ngTemplateOutlet="sumar" />
            }
            @if (errores()['stockReal']; as mensaje) {
              <span id="stock-real-error" class="campo-error">{{ mensaje }}</span>
            }
          </div>
        } @else {
          <div class="carga">
            <div class="signos" role="radiogroup" aria-label="Entra o sale" (keydown)="alTeclearSigno($event)">
              @if (celular()) {
                <span class="etq" aria-hidden="true">Entra o sale</span>
              }
              @for (s of signos; track s) {
                <button
                  type="button"
                  role="radio"
                  class="signo"
                  [class.entra]="s === 'ENTRA'"
                  [class.sale]="s === 'SALE'"
                  [attr.data-signo]="s"
                  [attr.aria-checked]="signo() === s"
                  [attr.tabindex]="signo() === s ? 0 : -1"
                  (click)="elegirSigno(s)"
                >
                  {{ s === 'ENTRA' ? '+ Entra' : '− Sale' }}
                </button>
              }
            </div>
            <div class="campo" [class.con-paso]="celular()">
              <label for="cantidad">Cantidad que {{ signo() === 'ENTRA' ? 'entra' : 'sale' }}</label>
              @if (celular()) {
                <ng-container *ngTemplateOutlet="restar" />
              }
              <input
                #numero
                id="cantidad"
                name="cantidad"
                class="numero"
                type="text"
                inputmode="decimal"
                autocomplete="off"
                spellcheck="false"
                [value]="valorTexto()"
                [attr.aria-invalid]="errores()['cantidad'] ? 'true' : null"
                [attr.aria-describedby]="errores()['cantidad'] ? 'cantidad-error' : null"
                (input)="cambiar('cantidad', $any($event.target).value)"
              />
              @if (celular()) {
                <ng-container *ngTemplateOutlet="sumar" />
              }
            </div>
            @if (errores()['cantidad']; as mensaje) {
              <span id="cantidad-error" class="campo-error error-carga">{{ mensaje }}</span>
            }
          </div>
        }

        <div class="vista-previa">
          @if (celular()) {
            <span class="queda-rotulo">Queda</span>
          }
          <span class="desde">{{ numero(articulo().stockActual) }}&nbsp;<span aria-hidden="true">→</span></span>&ngsp;
          <span class="resultado">
            <strong
              class="quedara"
              [class.sube]="queda() !== null && queda()! > articulo().stockActual"
              [class.baja]="queda() !== null && queda()! < articulo().stockActual"
              >{{ textoQuedara() }}</strong
            >
            @if (modo() === 'STOCK_REAL') {
              <span
                class="diferencia"
                [class.sube]="diferencia() !== null && diferencia()! > 0"
                [class.baja]="diferencia() !== null && diferencia()! < 0"
                >{{ textoDiferencia() }}</span
              >
            }
          </span>
        </div>

        @if (celular()) {
          @if (explicacion.visible(); as texto) {
            <p class="motivo-accion" role="status">{{ texto }}</p>
          }
        }

        <div class="campo">
          <label for="detalle">
            Detalle
            @if (motivo() !== 'OTRO') {
              <span class="opcional">opcional</span>
            }
          </label>
          <input
            id="detalle"
            name="detalle"
            type="text"
            maxlength="150"
            autocomplete="off"
            placeholder="Ej: remito 0001-123…"
            [value]="detalle()"
            [attr.aria-invalid]="errores()['detalle'] ? 'true' : null"
            [attr.aria-describedby]="errores()['detalle'] ? 'detalle-error' : null"
            (input)="cambiar('detalle', $any($event.target).value)"
          />
          @if (errores()['detalle']; as mensaje) {
            <span id="detalle-error" class="campo-error">{{ mensaje }}</span>
          }
        </div>

        @if (errorGeneral(); as mensaje) {
          <p class="error" role="alert">{{ mensaje }}</p>
        }

        <div class="acciones-panel" [class.ficha-pie]="celular()">
          <button type="button" class="cancelar secundario" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
            Cancelar
          </button>
          <!-- En el celular un botón apagado explica por qué (S5): aria-disabled deja llegar el toque; en el escritorio sigue disabled. -->
          <button
            type="submit"
            class="confirmar"
            data-tecla="Enter"
            [disabled]="guardando() && !celular()"
            [attr.aria-disabled]="celular() ? ariaDe(apagadas().guardar) : null"
          >Guardar ajuste</button>
        </div>
      </form>
    </dialog>

    <!-- − y + del celular: sólo cambian el campo numérico de siempre (la misma señal que al escribir). -->
    <ng-template #restar>
      <button
        type="button"
        class="paso-boton paso-menos"
        aria-label="Restar uno"
        [attr.aria-disabled]="ariaDe(apagadas().restar)"
        (click)="paso(-1)"
      >
        −
      </button>
    </ng-template>
    <ng-template #sumar>
      <button
        type="button"
        class="paso-boton paso-mas"
        aria-label="Sumar uno"
        [attr.aria-disabled]="ariaDe(apagadas().sumar)"
        (click)="paso(1)"
      >
        +
      </button>
    </ng-template>
  `,
  styles: `
    /* Panel lateral derecho de 460 px a todo el alto, sobre el velo del ::backdrop. */
    .panel-lateral {
      box-sizing: border-box;
      position: fixed;
      inset: 0 0 0 auto;
      width: min(460px, 100vw);
      max-width: 100vw;
      height: 100dvh;
      max-height: none;
      margin: 0;
      padding: var(--e-6);
      border: 0;
      background: var(--superficie);
      color: var(--texto);
      box-shadow: -16px 0 40px rgb(15 23 42 / 0.2);
      overflow-y: auto;
      overscroll-behavior: contain;
    }
    .panel-lateral::backdrop {
      background: var(--velo, rgb(15 23 42 / 0.45));
    }
    .panel-lateral[open] {
      animation: entrar 180ms ease-out;
    }
    @keyframes entrar {
      from {
        transform: translateX(24px);
        opacity: 0;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .panel-lateral[open] {
        animation: none;
      }
    }
    .ajuste {
      display: flex;
      flex-direction: column;
      gap: 18px;
      min-height: 100%;
    }
    .sobretitulo {
      margin: 0;
      color: var(--texto-tenue);
      font-size: var(--texto-xs);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    .articulo {
      margin: var(--e-1) 0 0;
      font-size: 1.25rem;
      font-weight: 700;
      line-height: 1.25;
      text-wrap: balance;
    }
    .referencia {
      margin: 2px 0 0;
      color: var(--texto-tenue);
      font-family: var(--fuente-mono);
      font-size: var(--texto-sm);
    }
    .opcional {
      margin-left: var(--e-1);
      color: var(--texto-tenue);
      font-weight: 400;
    }
    /* Modo: dos tarjetas con título y explicación. */
    .modos {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--e-2);
    }
    .modo {
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      min-height: 0;
      padding: 10px 12px;
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
      background: var(--superficie);
      color: var(--texto);
      font-size: var(--texto-base);
      font-weight: 600;
      text-align: left;
      white-space: normal;
    }
    .modo[aria-checked='true'],
    .modo[aria-checked='true']:hover {
      border-color: var(--acento);
      background: var(--acento-suave);
      box-shadow: inset 0 0 0 1px var(--acento);
      color: var(--acento-profundo);
    }
    .modo-detalle {
      color: var(--texto-suave);
      font-size: var(--texto-xs);
      font-weight: 400;
    }
    /* Sumar o restar: Entra / Sale a la izquierda, la cantidad grande a la derecha. */
    .carga {
      display: grid;
      grid-template-columns: 140px minmax(0, 1fr);
      gap: 6px 10px;
    }
    .signos {
      display: grid;
      grid-template-rows: 1fr 1fr;
      gap: 6px;
    }
    .signo {
      min-height: 0;
      border-color: var(--borde);
      color: var(--texto-suave);
    }
    .signo.entra[aria-checked='true'],
    .signo.entra[aria-checked='true']:hover {
      border-color: var(--exito-borde);
      background: var(--exito-fondo);
      color: var(--exito);
      font-weight: 600;
    }
    .signo.sale[aria-checked='true'],
    .signo.sale[aria-checked='true']:hover {
      border-color: var(--error-borde);
      background: var(--error-fondo);
      color: var(--error);
      font-weight: 600;
    }
    .error-carga {
      grid-column: 1 / -1;
    }
    .numero {
      height: 64px;
      padding: 0 14px;
      border-width: 2px;
      border-radius: var(--radio-lg);
      font-family: var(--fuente-mono);
      font-size: 1.875rem;
      font-weight: 600;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    /* «25 → 49 unidades». */
    .vista-previa {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--e-3);
      padding: 14px 16px;
      border-radius: var(--radio-lg);
      background: var(--superficie-2);
      font-size: var(--texto-md);
      font-variant-numeric: tabular-nums;
    }
    .desde {
      color: var(--texto-suave);
      white-space: nowrap;
    }
    .resultado {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      min-width: 0;
    }
    .quedara {
      font-size: 1.625rem;
      font-weight: 700;
      line-height: 1.2;
    }
    .diferencia {
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    .diferencia.sube {
      color: var(--exito);
    }
    .diferencia.baja {
      color: var(--error);
    }
    .ajuste .error {
      margin: 0;
    }
    .acciones-panel {
      display: flex;
      gap: 10px;
      margin-top: auto;
    }
    .acciones-panel button {
      min-height: 48px;
      border-radius: var(--radio-lg);
      font-size: var(--texto-md);
    }
    .acciones-panel .cancelar {
      flex: 1;
    }
    .acciones-panel .confirmar {
      flex: 2;
    }
  `,
})
export class DialogoAjusteStock {
  private readonly service = inject(ArticulosService);

  readonly articulo = input.required<ArticuloAAjustar>();
  readonly ajustado = output<AjusteStockResponse>();
  readonly cancelar = output<void>();

  protected readonly motivos = MOTIVOS_AJUSTE;
  protected readonly motivoLabels = MOTIVO_AJUSTE_LABELS;
  protected readonly modos = MODOS;
  protected readonly signos = SIGNOS;
  protected readonly etiquetasModo = ETIQUETAS_MODO;

  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Lo escrito en el campo numérico: el stock real o la cantidad, según el modo. */
  protected readonly valorTexto = signal('');
  protected readonly modo = signal<ModoAjuste>('STOCK_REAL');
  protected readonly signo = signal<Signo>('ENTRA');
  protected readonly motivo = signal<MotivoAjuste | null>(null);
  protected readonly detalle = signal('');
  protected readonly errores = signal<Partial<Record<Campo, string>>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);
  /** Pantalla del celular: se lee al abrir, como la ficha del artículo (movil-sectores T5, 5c y 5e). */
  protected readonly celular = signal(consultaCelular()?.matches ?? false);
  protected readonly ariaDe = ariaDe;

  private readonly dialogo = viewChild.required<ElementRef<HTMLDialogElement>>('dialogo');
  private readonly campoNumero = viewChild<ElementRef<HTMLInputElement>>('numero');

  /**
   * Lo escrito, solo si el artículo lo acepta según `errorDeFormato`. Si no, null y la vista previa muestra «—» en
   * vez de un resultado imposible. Sacar más de lo que hay o pasarse del máximo al sumar no es de formato: el
   * resultado que se ve (negativo o enorme) explica el error.
   */
  private readonly leidoValido = computed(() => {
    const leido = leerNumero(this.valorTexto());
    return leido && errorDeFormato(leido, this.articulo().unidad) === null ? leido : null;
  });

  /** Diferencia (con signo) contra el stock actual, en vivo; null si lo escrito no es válido. */
  protected readonly diferencia = computed(() => {
    const leido = this.leidoValido();
    if (!leido) return null;
    return (Math.round(leido.valor * 1000) - Math.round(this.articulo().stockActual * 1000)) / 1000;
  });

  /** Cantidad con signo del modo sumar o restar; null si lo escrito no es válido. */
  private readonly delta = computed(() => {
    const leido = this.leidoValido();
    if (!leido) return null;
    return this.signo() === 'SALE' ? -leido.valor : leido.valor;
  });

  /** Stock resultante en vivo: lo contado en el conteo, o el actual más o menos la cantidad. */
  protected readonly queda = computed(() => {
    if (this.modo() === 'STOCK_REAL') return this.leidoValido()?.valor ?? null;
    const d = this.delta();
    if (d === null) return null;
    return (Math.round(this.articulo().stockActual * 1000) + Math.round(d * 1000)) / 1000;
  });

  protected readonly textoQuedara = computed(() => {
    const q = this.queda();
    return q === null ? '—' : this.conUnidad(q);
  });

  /** Adonde llega − o +: lo escrito (0 si está vacío o no se lee) ± 1; null si se saldría de 0 a MAXIMO. En UNIDAD, entero. */
  private destinoDelPaso(delta: 1 | -1): number | null {
    const leido = leerNumero(this.valorTexto().trim() === '' ? '0' : this.valorTexto());
    const base = leido && !leido.negativo ? Math.round(Math.min(leido.valor, MAXIMO) * 1000) / 1000 : 0;
    const destino =
      this.articulo().unidad === 'UNIDAD'
        ? delta > 0
          ? Math.floor(base) + 1
          : Math.ceil(base) - 1
        : Math.round((base + delta) * 1000) / 1000;
    return destino < 0 || destino > MAXIMO ? null : destino;
  }

  /** Por qué − y + (y Guardar) están apagados, con los textos de los errores de formato de hoy; null si se pueden usar. */
  protected readonly apagadas = computed(() => {
    const mensajes = MENSAJES[this.modo() === 'CANTIDAD' ? 'cantidad' : 'stockReal'].formato;
    return {
      restar: this.destinoDelPaso(-1) === null ? mensajes.NEGATIVO : null,
      sumar: this.destinoDelPaso(1) === null ? mensajes.MAXIMO : null,
      guardar: this.guardando() ? 'Guardando el ajuste: esperá un momento.' : null,
    } satisfies Record<AccionApagable, string | null>;
  });
  protected readonly explicacion = new MotivoDeAccion<AccionApagable>(() => this.apagadas());

  protected readonly textoDiferencia = computed(() => {
    const d = this.diferencia();
    if (d === null) return '—';
    if (d === 0) return 'Sin diferencia';
    return `${d > 0 ? '+' : ''}${this.conUnidad(d)}`;
  });

  constructor() {
    // Quien abrió el panel (el botón «Ajustar stock»): recupera el foco al cerrarse.
    const abridor = inject(DOCUMENT).activeElement as HTMLElement | null;
    let abierto: HTMLDialogElement | null = null;
    afterNextRender(() => {
      abierto = this.dialogo().nativeElement;
      // jsdom no tiene showModal: ahí queda abierto como diálogo común.
      if (typeof abierto.showModal === 'function') abierto.showModal();
      else abierto.setAttribute('open', '');
      this.campoNumero()?.nativeElement.focus();
    });
    // Girar el teléfono con el ajuste abierto cambia de pantalla completa a panel lateral (y al revés) sin cerrarlo.
    const consulta = consultaCelular();
    const alCambiarDePantalla = (evento: MediaQueryListEvent) => this.celular.set(evento.matches);
    consulta?.addEventListener?.('change', alCambiarDePantalla);
    inject(DestroyRef).onDestroy(() => {
      consulta?.removeEventListener?.('change', alCambiarDePantalla);
      if (abierto?.open && typeof abierto.close === 'function') abierto.close();
      abridor?.focus?.();
    });
  }

  /** Cambia de modo a mano. Lo escrito significa otra cosa en el otro modo, así que se borra. */
  protected elegirModo(modo: ModoAjuste, enfocar = true): void {
    if (modo === this.modo()) return;
    this.modo.set(modo);
    this.valorTexto.set('');
    this.errores.update(({ stockReal: _s, cantidad: _c, ...resto }) => resto);
    this.errorGeneral.set(null);
    if (enfocar) afterNextRender(() => this.campoNumero()?.nativeElement.focus(), { injector: this.injector });
  }

  protected elegirSigno(signo: Signo): void {
    this.signo.set(signo);
    this.errores.update(({ cantidad: _, ...resto }) => resto);
    this.errorGeneral.set(null);
  }

  /** Roving tabindex: las flechas mueven la elección dentro del grupo y el foco la acompaña. */
  protected alTeclearModo(evento: KeyboardEvent): void {
    const siguiente = this.vecino(evento, MODOS, this.modo());
    if (!siguiente) return;
    this.elegirModo(siguiente, false);
    this.enfocarOpcion(`[data-modo="${siguiente}"]`);
  }

  protected alTeclearSigno(evento: KeyboardEvent): void {
    const siguiente = this.vecino(evento, SIGNOS, this.signo());
    if (!siguiente) return;
    this.elegirSigno(siguiente);
    this.enfocarOpcion(`[data-signo="${siguiente}"]`);
  }

  protected alCancelar(evento: Event): void {
    // El padre saca el panel; si el navegador lo cerrara solo, quedaría abierto en el estado del padre.
    evento.preventDefault();
    this.cancelar.emit();
  }

  protected conUnidad(cantidad: number): string {
    return cantidadConUnidad(cantidad, this.articulo().unidad);
  }

  protected numero(cantidad: number): string {
    return NUMERO.format(cantidad);
  }

  protected cambiar(campo: Campo, valor: string): void {
    if (campo === 'stockReal' || campo === 'cantidad') this.valorTexto.set(valor);
    else if (campo === 'motivo') {
      const motivo = valor as MotivoAjuste;
      this.motivo.set(motivo);
      this.signo.set(motivo === 'ROTURA' ? 'SALE' : 'ENTRA');
      this.elegirModo(MODO_POR_MOTIVO[motivo], false);
    } else this.detalle.set(valor);
    this.errores.update(({ [campo]: _, ...resto }) => resto);
    this.errorGeneral.set(null);
  }

  /** − o +: cambia el mismo campo numérico que al escribir (borra su error), o explica por qué no se puede. */
  protected paso(delta: 1 | -1): void {
    if (this.explicacion.mostrar(delta > 0 ? 'sumar' : 'restar')) return;
    const destino = this.destinoDelPaso(delta)!;
    this.cambiar(this.modo() === 'CANTIDAD' ? 'cantidad' : 'stockReal', String(destino).replace('.', ','));
  }

  protected confirmar(): void {
    if (this.explicacion.mostrar('guardar')) return;
    const errores = this.validar();
    this.errores.set(errores);
    this.errorGeneral.set(null);
    if (Object.keys(errores).length > 0) return;

    const detalle = this.detalle().trim();
    this.guardando.set(true);
    this.service
      .ajustarStock(
        this.articulo().id,
        this.modo() === 'CANTIDAD'
          ? { modo: 'CANTIDAD', cantidad: this.delta()!, motivo: this.motivo()!, detalle: detalle === '' ? null : detalle }
          : {
              modo: 'STOCK_REAL',
              stockReal: this.leidoValido()!.valor,
              motivo: this.motivo()!,
              detalle: detalle === '' ? null : detalle,
            },
      )
      .subscribe({
        next: (respuesta) => {
          this.guardando.set(false);
          this.ajustado.emit(respuesta);
        },
        error: (err: unknown) => {
          this.guardando.set(false);
          const entradas = Object.entries(erroresPorCampo(err));
          if (entradas.length > 0) {
            // Cada error va junto a su campo; el de un campo que no está a la vista (el otro modo) va arriba de
            // los botones, para que no se pierda.
            const visibles: string[] = ['motivo', 'detalle', this.modo() === 'CANTIDAD' ? 'cantidad' : 'stockReal'];
            this.errores.set(Object.fromEntries(entradas.filter(([campo]) => visibles.includes(campo))));
            const ocultos = entradas.filter(([campo]) => !visibles.includes(campo)).map(([, mensaje]) => mensaje);
            this.errorGeneral.set(ocultos.length > 0 ? ocultos.join(' ') : null);
          } else {
            this.errorGeneral.set(mensajeDe(err, 'No se pudo ajustar el stock.'));
          }
        },
      });
  }

  /** La opción vecina según la flecha apretada (en ronda); null si no es una flecha. */
  private vecino<T>(evento: KeyboardEvent, opciones: readonly T[], actual: T): T | null {
    const paso = PASO_POR_TECLA[evento.key];
    if (!paso) return null;
    evento.preventDefault();
    return opciones[(opciones.indexOf(actual) + paso + opciones.length) % opciones.length];
  }

  private enfocarOpcion(selector: string): void {
    this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus();
  }

  /** Las mismas reglas que AjusteStock.java y MotivoAjuste.java, todas juntas. */
  private validar(): Partial<Record<Campo, string>> {
    const errores: Partial<Record<Campo, string>> = {};
    const campo = this.modo() === 'CANTIDAD' ? 'cantidad' : 'stockReal';
    const mensajes = MENSAJES[campo];
    const texto = this.valorTexto().trim();
    const leido = leerNumero(texto);
    const formato = leido && errorDeFormato(leido, this.articulo().unidad);
    const pregunta = preguntaSiEsAmbiguo(texto);
    if (texto === '') errores[campo] = mensajes.vacio;
    else if (pregunta) errores[campo] = pregunta;
    else if (!leido) errores[campo] = mensajes.noEsNumero;
    else if (formato) errores[campo] = mensajes.formato[formato];
    else if (campo === 'stockReal') {
      if (this.diferencia() === 0) errores.stockReal = 'El stock real es igual al actual: no hay nada que ajustar.';
    } else if (leido.valor === 0) errores.cantidad = 'La cantidad no puede ser cero.';
    else if (this.queda()! < 0 && this.signo() === 'SALE')
      errores.cantidad = `No podés sacar ${String(leido.valor).replace('.', ',')}: hay ${this.conUnidad(this.articulo().stockActual)}.`;
    else if (this.queda()! > MAXIMO)
      errores.cantidad = 'El stock quedaría demasiado grande: el máximo es 999.999.999.999,999.';

    if (!this.motivo()) errores.motivo = 'Elegí el motivo del ajuste.';
    else if (this.motivo() === 'OTRO' && this.detalle().trim() === '')
      errores.detalle = 'Contá el motivo del ajuste.';
    return errores;
  }
}
