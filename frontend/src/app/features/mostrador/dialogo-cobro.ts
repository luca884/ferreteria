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
import { centavosDeImporte, normalizarImporte } from '../../core/formato/importe';
import { MontoPipe, formatearMonto } from '../../core/formato/monto';
import { ImporteInput } from '../../shared/importe-input';
import { TrampaFoco } from '../../shared/trampa-foco';
import { CuentaCliente } from '../clientes/cliente.models';
import { FormaPago } from '../formas-pago/forma-pago.models';
import { recargoDeCobro } from './centavos';
import { GuardaLector } from './guarda-lector';
import { ExcesoCuentaCorriente, MedioPago, PagoCobro } from './venta.models';

/**
 * La cuenta corriente del cliente del ticket, como la tiene el contenedor al abrir el cobro (CC-017): leyéndose, leída o
 * con error. Nunca se guarda en el ticket: se pide fresca cada vez que se abre el cobro.
 */
export type CuentaDelCobro =
  | { estado: 'cargando' }
  | { estado: 'error' }
  | { estado: 'listo'; cuenta: CuentaCliente };

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
  /** Solo el Dueño, y solo si la venta a cuenta corriente se pasa del límite: autoriza el exceso (CC-013). */
  autorizaExceso?: true;
}

/**
 * Qué comprobante sale al cobrar (CU-07 paso 5): «Factura B · Consumidor Final», «Factura A · <cliente>» o solo «Factura»,
 * con la marca del ambiente («SIMULADO», «HOMOLOGACIÓN») fuera de producción. Lo arma el contenedor con las letras del
 * servidor (C3); sin facturación habilitada no hay rótulo.
 */
export interface RotuloFactura {
  texto: string;
  ambiente: string | null;
}

/** «Mixto» es la última opción: reparte el total entre varias formas. Las demás son el id de una forma. */
type Opcion = number | 'MIXTO';

const MENSAJE_SUMA = 'Los pagos tienen que sumar el total.';
const MENSAJE_IMPORTES = 'Revisá los importes: usá números con hasta 2 decimales.';

type Montos = Record<number, string | null>;

/** Tope del vuelto: $ 1.000.000. Un recibido que supere el total en más que eso es un error de tipeo. */
const VUELTO_MAXIMO_CENTAVOS = 100_000_000;

const PORCENTAJE = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 });

/** El ajuste de la forma en %: positivo es recargo y negativo, descuento (una forma lleva uno o el otro). */
const ajuste = (forma: FormaPago): number => forma.recargoPorcentaje - forma.descuentoPorcentaje;

/** El ajuste de la forma en centésimas de punto porcentual (1000 = 10 %; -1000, 10 % de descuento). */
const puntosBase = (forma: FormaPago): number => Math.round(ajuste(forma) * 100);

/** Los centavos como lo que se ve en un campo con máscara: «1.300,50» (puntos de miles y siempre 2 decimales). */
const centavosATexto = (c: number) => `${normalizarImporte(String(Math.floor(c / 100)))},${String(c % 100).padStart(2, '0')}`;

/**
 * Cobro (F4). Presentacional: abre en efectivo con el recibido igual al total, así F4 + Enter cobra.
 * ← y → cambian de medio. Mientras `enVuelo` (o después de confirmar, hasta que termine) ignora Enter.
 */
@Component({
  selector: 'app-dialogo-cobro',
  imports: [ImporteInput, MontoPipe, TrampaFoco],
  template: `
    <!-- El mousedown sobre el fondo no mueve el foco al body: si no, Esc y Enter (que escucha el diálogo) dejarían de andar. -->
    <div class="dialogo-fondo" (mousedown)="retenerFoco($event)">
      <div
        class="dialogo cobro"
        tabindex="-1"
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
            @if (rotuloFactura(); as rotulo) {
              <p class="comprobante">
                @if (noFacturable()) {
                  Sin factura
                } @else {
                  {{ rotulo.texto }}
                  @if (rotulo.ambiente; as ambiente) {
                    <strong class="ambiente">{{ ambiente }}</strong>
                  }
                }
              </p>
            }
          </div>
          <p class="total">{{ aCobrarCentavos() / 100 | monto }}</p>
        </div>
        @if (recargoCentavos() > 0) {
          <p class="recargo">
            Recargo{{ porcentajeDeLaForma() }} <strong>{{ recargoCentavos() / 100 | monto }}</strong>
          </p>
        } @else if (recargoCentavos() < 0) {
          <p class="descuento-forma">
            Descuento por forma de pago{{ porcentajeDeLaForma() }}
            <strong>−{{ -recargoCentavos() / 100 | monto }}</strong>
          </p>
        }
        @if (aviso(); as texto) {
          <p class="aviso" role="status">{{ texto }}</p>
        }
        @if (autorizando()) {
          <p class="aviso" role="status">Autorizando con ARCA…</p>
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
                [attr.aria-disabled]="o.deshabilitada ? 'true' : null"
                [attr.aria-describedby]="o.deshabilitada ? 'razon-medio-' + o.id : null"
                [tabindex]="o.id === seleccion() ? 0 : -1"
                (click)="elegir(o.id)"
              >
                {{ o.nombre }}
                @if (o.recargo) {
                  <small class="marca-recargo">+{{ o.recargo }}&nbsp;%</small>
                } @else if (o.descuento) {
                  <small class="marca-recargo marca-descuento">−{{ o.descuento }}&nbsp;%</small>
                }
              </button>
            }
          </div>
          <!-- La razón de cada opción deshabilitada: visible y enlazada (no se oculta con disabled para que se lea). -->
          @for (o of opciones(); track o.id) {
            @if (o.deshabilitada; as razon) {
              <p class="razon-medio" [id]="'razon-medio-' + o.id">{{ razon }}</p>
            }
          }
          <p class="pista-medios" aria-hidden="true"><kbd>←</kbd> <kbd>→</kbd> cambian el medio</p>
        </div>
        @if (infoCuenta(); as texto) {
          <p class="cuenta-corriente" [class.excede]="exceso() !== null" aria-live="polite">{{ texto }}</p>
        }
        @if (seleccion() === 'MIXTO') {
          <div class="mixto">
            @for (f of formas(); track f.id) {
              <label class="fila-pago">
                {{ f.nombre }}
                <input
                  #filaMonto
                  appImporte
                  [attr.data-forma]="f.id"
                  [disabled]="razonesMixto()[f.id] !== null"
                  [attr.aria-describedby]="razonesMixto()[f.id] ? 'razon-fila-' + f.id : null"
                  [value]="montos()[f.id] ?? ''"
                  (input)="escribirMonto(f.id, $any($event.target).value)"
                />
              </label>
              @if (razonesMixto()[f.id]; as razon) {
                <small class="razon-fila" [id]="'razon-fila-' + f.id">{{ razon }}</small>
              }
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
                appImporte
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
        @if (eligeFactura()) {
          <!-- Bloque 2, S4: facturar o no se elige con un clic; Enter no cobra, para que nadie facture sin querer. -->
          <p class="pista-factura">Elegí con un clic si la venta se factura.</p>
          <div class="dialogo-acciones elige-factura">
            <button type="button" class="sin-factura secundario" (click)="confirmarCobro(true)">
              {{ autoriza() ? 'Autorizar exceso y cobrar sin factura' : 'Cobrar sin factura' }}
            </button>
            <button type="button" class="facturar primario" (click)="confirmarCobro(false)">
              {{ autoriza() ? 'Autorizar exceso, cobrar y facturar' : 'Cobrar y facturar con ARCA' }}
            </button>
            <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
              Cancelar
            </button>
          </div>
        } @else {
          <p class="sin-arca">Sin factura: la facturación con ARCA no está activa.</p>
          <label class="no-facturable">
            <input type="checkbox" [checked]="noFacturable()" (change)="noFacturable.set($any($event.target).checked)" />
            No facturable
            <small>cobra igual, pero no se factura nunca</small>
          </label>
          <div class="dialogo-acciones">
            <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
              Cancelar
            </button>
            <button type="button" class="cobrar primario" data-tecla="Enter" aria-keyshortcuts="Enter" (click)="confirmarCobro()">
              {{ autoriza() ? 'Autorizar exceso y cobrar' : 'Cobrar' }}
            </button>
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    .cobro {
      width: min(540px, 100%);
      gap: 18px;
      overflow: hidden;
    }
    /* El diálogo recibe el foco con un clic en una zona sin controles: no necesita el aro. */
    .cobro:focus {
      outline: none;
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
    .conteo,
    .comprobante {
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
    /* El botón global no parte el texto (nowrap): un nombre largo de forma lo desbordaba y pisaba al vecino. */
    .medio {
      min-width: 0;
      min-height: 54px;
      padding: 6px 8px;
      white-space: normal;
      overflow-wrap: anywhere;
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
    /* Una opción que hoy no se puede elegir (cuenta corriente sin cliente, sin cupo...): se ve apagada y se explica abajo. */
    .medio[aria-disabled='true'],
    .medio[aria-disabled='true']:hover {
      border-style: dashed;
      border-color: var(--borde);
      background: var(--superficie-2);
      color: var(--texto-suave);
      font-weight: 500;
      cursor: not-allowed;
    }
    .razon-medio,
    .razon-fila {
      margin: 0;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
    }
    .razon-fila {
      margin-top: calc(var(--e-1) * -1);
    }
    .cuenta-corriente {
      margin: 0;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    /* Pasarse del límite es una decisión del Dueño: se marca con el color de aviso. */
    .cuenta-corriente.excede {
      padding: var(--e-2) var(--e-3);
      border: 1px solid var(--advertencia-borde);
      border-left: 4px solid var(--advertencia-marca);
      border-radius: var(--radio);
      background: var(--advertencia-fondo);
      color: var(--texto);
    }
    .mixto input:disabled {
      cursor: not-allowed;
      background: var(--superficie-2);
    }
    .marca-recargo {
      display: block;
      white-space: nowrap;
      overflow-wrap: normal;
      font-size: var(--texto-xs);
      font-weight: 600;
    }
    /* El recargo (o el descuento) de la forma elegida: la cuenta de cuánto se suma (o se resta) al total. */
    .recargo,
    .descuento-forma {
      display: flex;
      justify-content: space-between;
      margin: 0;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-weight: 600;
    }
    .recargo strong,
    .descuento-forma strong {
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
    .dialogo-acciones {
      flex-wrap: wrap;
    }
    .dialogo-acciones button {
      min-height: 48px;
    }
    .dialogo-acciones .cobrar {
      padding-inline: var(--e-6);
    }
    /* Las dos formas de cobrar, lado a lado y del mismo ancho; Cancelar abajo. */
    .elige-factura {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }
    .elige-factura .cancelar {
      grid-column: 1 / -1;
      justify-self: start;
    }
    .pista-factura,
    .sin-arca {
      margin: 0;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
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
  /** El nombre del cliente del ticket (F9), o null si es Consumidor Final: la cuenta corriente lo necesita (CC-012). */
  readonly clienteNombre = input<string | null>(null);
  /** La cuenta corriente de ese cliente, que el contenedor pide al abrir. Sin dato con cliente se lee como «verificando». */
  readonly cuenta = input<CuentaDelCobro | null>(null);
  /** Solo el Dueño ve saldo, límite y disponible y puede autorizar un exceso (CC-007). */
  readonly esDueno = input(false);
  /** El rechazo del servidor por el límite (CC-013): trae las cifras para el Dueño y `puedeAutorizar`. */
  readonly avisoLimite = input<ExcesoCuentaCorriente | null>(null);
  /** Qué factura sale (CU-07 paso 5). null: la ferretería no factura y no se dice nada, como antes. */
  readonly rotuloFactura = input<RotuloFactura | null>(null);
  /**
   * Bloque 2, S4: la ferretería factura con ARCA, o todavía no se sabe. Entonces no hay casilla: el cajero elige con un
   * clic «Cobrar sin factura» o «Cobrar y facturar con ARCA», y Enter no cobra. false: el servidor dijo que no factura;
   * queda «Cobrar» con Enter y la casilla «No facturable», como antes.
   */
  readonly eligeFactura = input(false);

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

  /**
   * Por qué la cuenta corriente no se puede usar, sea una forma única o una parte del mixto (CC-012, CC-017): falta el
   * cliente, la cuenta se está leyendo o no se pudo leer (falla cerrada) o no está habilitada. Null si se puede.
   */
  private readonly razonDeLaCuenta = computed<string | null>(() => {
    const cliente = this.clienteNombre();
    if (cliente === null) return 'Elegí un cliente (F9) para vender a cuenta corriente.';
    const cuenta = this.cuenta();
    if (cuenta === null || cuenta.estado === 'cargando') return 'Verificando la cuenta corriente…';
    if (cuenta.estado === 'error') return 'No se pudo verificar la cuenta corriente. Cerrá y reintentá.';
    if (!cuenta.cuenta.habilitada) {
      return this.esDueno()
        ? `${cliente} no tiene cuenta corriente habilitada. Habilitala desde Clientes.`
        : `${cliente} no tiene cuenta corriente habilitada. Pedile al Dueño que se la habilite.`;
    }
    return null;
  });

  /**
   * Como forma única, además: el Vendedor no puede pasarse del límite (CC-006). Sin cifras: solo sabe que no alcanza. Al
   * Dueño no se le deshabilita: ve el exceso y lo autoriza.
   */
  private readonly razonDeLaCuentaUnica = computed<string | null>(() => {
    const razon = this.razonDeLaCuenta();
    if (razon !== null) return razon;
    const cuenta = this.cuenta();
    if (!this.esDueno() && cuenta?.estado === 'listo' && cuenta.cuenta.alcanza === false) {
      return 'No alcanza el límite de la cuenta corriente. Pedile al Dueño.';
    }
    return null;
  });

  private razonDeLaForma(forma: FormaPago): string | null {
    return forma.medio === 'CUENTA_CORRIENTE' ? this.razonDeLaCuentaUnica() : null;
  }

  /** En el mixto no hay pre-chequeo del importe (ADR-9): decide el servidor. Solo se bloquea lo que ya se sabe. */
  protected readonly razonesMixto = computed<Record<number, string | null>>(() => {
    const razon = this.razonDeLaCuenta();
    const razones: Record<number, string | null> = {};
    for (const f of this.formas()) razones[f.id] = f.medio === 'CUENTA_CORRIENTE' ? razon : null;
    return razones;
  });

  /**
   * Una opción por forma, con la marca de su recargo o su descuento y, si hoy no se puede elegir, su razón; y «Mixto»
   * al final.
   */
  protected readonly opciones = computed<
    { id: Opcion; nombre: string; recargo: string | null; descuento: string | null; deshabilitada: string | null }[]
  >(() => [
    ...this.formas().map((f) => ({
      id: f.id as Opcion,
      nombre: f.nombre,
      recargo: f.recargoPorcentaje > 0 ? PORCENTAJE.format(f.recargoPorcentaje) : null,
      descuento: f.descuentoPorcentaje > 0 ? PORCENTAJE.format(f.descuentoPorcentaje) : null,
      deshabilitada: this.razonDeLaForma(f),
    })),
    { id: 'MIXTO' as Opcion, nombre: 'Mixto', recargo: null, descuento: null, deshabilitada: null },
  ]);

  /**
   * Abre en la primera forma en efectivo (F4 y Enter cobra); sin ninguna, en la primera que se pueda elegir. La cuenta
   * corriente nunca abre elegida por sí sola.
   */
  private readonly formaInicial = computed(() => {
    const formas = this.formas();
    const inicial =
      formas.find((f) => f.medio === 'EFECTIVO') ?? formas.find((f) => this.razonDeLaForma(f) === null) ?? formas[0];
    return inicial?.id ?? null;
  });

  /**
   * Lo elegido si sigue existiendo y se puede usar; si la forma desaparece o pasa a estar deshabilitada mientras está
   * abierto (la cuenta dejó de alcanzar), vuelve a la inicial.
   */
  protected readonly seleccion = computed<Opcion | null>(() => {
    const elegida = this.elegida();
    if (elegida === 'MIXTO') return 'MIXTO';
    const forma = elegida === null ? undefined : this.formas().find((f) => f.id === elegida);
    if (forma && this.razonDeLaForma(forma) === null) return forma.id;
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
      return centavosDeImporte(texto);
    };
    const razones = this.razonesMixto();
    const filas: Record<number, number | null> = {};
    // Una fila deshabilitada no aporta nada, aunque se hubiera escrito antes de que se deshabilitara.
    for (const f of this.formas()) filas[f.id] = razones[f.id] ? 0 : leer(montos[f.id]);
    return filas;
  });

  /** Si el cobro mete plata en la cuenta corriente: la forma única elegida, o una parte del mixto con importe. */
  private readonly usaCuentaCorriente = computed(() => {
    if (this.seleccion() === 'MIXTO') {
      const c = this.centavosMixto();
      return this.formas().some((f) => f.medio === 'CUENTA_CORRIENTE' && (c[f.id] ?? 0) > 0);
    }
    return this.formaElegida()?.medio === 'CUENTA_CORRIENTE';
  });

  /**
   * Lo que se pasa del límite, solo para el Dueño (CC-007, CC-013): del rechazo del servidor si lo hubo; si no, de la cuenta
   * leída cuando la forma única elegida no alcanza. El mixto no se pre-chequea: solo muestra lo que rechazó el servidor.
   */
  protected readonly exceso = computed<{ exceso: number; limite: number; saldo: number } | null>(() => {
    if (!this.esDueno() || !this.usaCuentaCorriente()) return null;
    const aviso = this.avisoLimite();
    if (aviso?.puedeAutorizar && aviso.exceso !== undefined && aviso.limite !== undefined && aviso.saldo !== undefined) {
      return { exceso: aviso.exceso, limite: aviso.limite, saldo: aviso.saldo };
    }
    const cuenta = this.cuenta();
    if (this.seleccion() === 'MIXTO' || cuenta?.estado !== 'listo') return null;
    const { alcanza, limite, saldo, disponible } = cuenta.cuenta;
    if (alcanza !== false || limite === undefined || saldo === undefined || disponible === undefined) return null;
    return { exceso: (this.totalCentavos() - Math.round(disponible * 100)) / 100, limite, saldo };
  });

  /** El botón pasa a «Autorizar exceso y cobrar»: solo el Dueño, solo si el cobro usa la cuenta y se pasa del límite. */
  protected readonly autoriza = computed(
    () =>
      this.esDueno() &&
      this.usaCuentaCorriente() &&
      (this.exceso() !== null || this.avisoLimite()?.puedeAutorizar === true),
  );

  /** Lo que se dice de la cuenta debajo de los medios: el exceso (Dueño), el disponible (Dueño) o «Alcanza» (Vendedor). */
  protected readonly infoCuenta = computed<string | null>(() => {
    const exceso = this.exceso();
    if (exceso) {
      return `Supera el límite por ${formatearMonto(exceso.exceso)} (límite ${formatearMonto(exceso.limite)}, saldo ${formatearMonto(exceso.saldo)})`;
    }
    const cuenta = this.cuenta();
    if (this.formaElegida()?.medio !== 'CUENTA_CORRIENTE' || cuenta?.estado !== 'listo' || !cuenta.cuenta.habilitada) {
      return null;
    }
    if (this.esDueno()) {
      return cuenta.cuenta.disponible === undefined ? null : `Disponible ${formatearMonto(cuenta.cuenta.disponible)}`;
    }
    return cuenta.cuenta.alcanza ? 'Alcanza' : null;
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

  /** « (10 %)» al lado del recargo o del descuento de una forma única; nada en el mixto, donde cada parte tiene el suyo. */
  protected readonly porcentajeDeLaForma = computed(() => {
    const forma = this.formaElegida();
    const porcentaje = forma ? Math.abs(ajuste(forma)) : 0;
    return porcentaje > 0 ? ` (${PORCENTAJE.format(porcentaje)} %)` : '';
  });

  /** Mientras no escribió, el recibido acompaña al total a cobrar (también si cambia por un 409). */
  protected readonly textoRecibido = computed(() => this.escrito() ?? centavosATexto(this.aCobrarCentavos()));

  protected readonly recibido = computed<number | null>(() => {
    return centavosDeImporte(this.textoRecibido());
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

  /** Mientras viaja una venta que se factura, el servidor espera el CAE: se avisa para que nadie reintente. */
  protected readonly autorizando = computed(
    () => this.enVuelo() && this.rotuloFactura() !== null && !this.noFacturable(),
  );

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
        this.filasMonto().find((f) => !f.nativeElement.disabled)?.nativeElement.focus();
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
    // Una opción deshabilitada no se elige con el clic (su razón está a la vista).
    if (this.opciones().find((o) => o.id === opcion)?.deshabilitada) return;
    this.elegida.set(opcion);
  }

  /** ← y → recorren las opciones que se pueden elegir: las deshabilitadas se saltean. */
  private ciclar(delta: 1 | -1): void {
    const ids = this.opciones()
      .filter((o) => !o.deshabilitada)
      .map((o) => o.id);
    const n = ids.length;
    const actual = ids.indexOf(this.seleccion() as Opcion);
    this.elegida.set(ids[(actual + delta + n) % n]);
  }

  protected escribirMonto(id: number, texto: string): void {
    this.montos.update((montos) => ({ ...montos, [id]: texto }));
    this.mensajeMixto.set(null);
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
    this.confirmarConEnter();
  }

  /**
   * Con la elección de factura a la vista (bloque 2, S4) Enter no cobra: solo el clic en uno de los dos botones. En el
   * pago mixto igual avisa si los importes no cierran, para que Enter no quede mudo.
   */
  private confirmarConEnter(): void {
    if (!this.eligeFactura()) {
      this.confirmarCobro();
      return;
    }
    if (this.seleccion() === 'MIXTO') this.avisarSiElMixtoNoCierra();
  }

  /**
   * Enter en una fila del pago mixto (como en GDS): si todo está cubierto cobra; si falta, pasa a la fila siguiente y
   * le sugiere lo que falta, para que cargar el resto sea solo Enter.
   */
  private enterEnMixto(origen: HTMLElement): void {
    if (this.importesMixtoValidos() && this.restoCentavos() === 0) {
      this.confirmarConEnter();
      return;
    }
    const filas = this.filasMonto().map((f) => f.nativeElement);
    const i = filas.indexOf(origen as HTMLInputElement);
    // La siguiente fila habilitada: una deshabilitada (cuenta corriente sin cliente) no recibe el foco.
    let j = i + 1;
    while (j < filas.length && filas[j].disabled) j++;
    const siguiente = filas[j];
    if (!this.importesMixtoValidos() || !siguiente || this.restoCentavos() < 0) {
      this.confirmarConEnter();
      return;
    }
    const forma = this.formas()[j];
    if ((this.montos()[forma.id] ?? null) === null) {
      const texto = centavosATexto(this.restoCentavos());
      this.escribirMonto(forma.id, texto);
      siguiente.value = texto;
    }
    siguiente.focus();
    siguiente.select();
  }

  /** `noFacturable` lo pasa el botón que se eligió (bloque 2, S4); sin él vale la casilla. */
  protected confirmarCobro(noFacturable?: boolean): void {
    if (this.enVuelo() || this.bloqueado()) return;
    if (this.seleccion() === 'MIXTO') {
      this.confirmarMixto(noFacturable);
      return;
    }
    const forma = this.formaElegida();
    if (!forma || this.razonDeLaForma(forma) !== null) return;
    const efectivo = forma.medio === 'EFECTIVO';
    if (efectivo && (this.recibido() === null || this.errorRecibido() !== null || this.faltaCentavos() > 0)) return;
    if (noFacturable !== undefined) this.noFacturable.set(noFacturable);
    this.bloqueado.set(true);
    this.confirmar.emit({
      formaPagoId: forma.id,
      medio: forma.medio,
      recibidoCentavos: efectivo ? this.recibido() : null,
      recargoCentavos: this.recargoCentavos(),
      ...(this.noFacturable() ? { noFacturable: true as const } : {}),
      ...(this.autoriza() ? { autorizaExceso: true as const } : {}),
    });
  }

  /** Muestra por qué el pago mixto no se puede cobrar todavía; true si no cierra. */
  private avisarSiElMixtoNoCierra(): boolean {
    if (!this.importesMixtoValidos()) {
      this.mensajeMixto.set(MENSAJE_IMPORTES);
      return true;
    }
    if (this.restoCentavos() !== 0) {
      this.mensajeMixto.set(MENSAJE_SUMA);
      return true;
    }
    return false;
  }

  private confirmarMixto(noFacturable: boolean | undefined): void {
    if (this.avisarSiElMixtoNoCierra()) return;
    if (noFacturable !== undefined) this.noFacturable.set(noFacturable);
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
      ...(this.autoriza() ? { autorizaExceso: true as const } : {}),
    });
  }
}
