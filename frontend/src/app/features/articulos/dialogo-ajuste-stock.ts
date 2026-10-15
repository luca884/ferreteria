import { HttpErrorResponse } from '@angular/common/http';
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
import { cantidadConUnidad } from '../../core/formato/unidad';
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

/** DECIMAL(15, 3) del backend. */
const MAXIMO = 999_999_999_999.999;

/** «145,5» o «145.5» → número y cuántos decimales se escribieron; null si no es un número. */
function leerNumero(texto: string): { valor: number; decimales: number } | null {
  const limpio = texto.trim().includes(',') ? texto.trim().replace(/\./g, '').replace(',', '.') : texto.trim();
  if (!/^-?\d+(\.\d+)?$/.test(limpio)) return null;
  return { valor: Number(limpio), decimales: limpio.split('.')[1]?.replace(/0+$/, '').length ?? 0 };
}

/**
 * CU-09 Ajustar stock: el Dueño escribe el stock REAL que contó y el diálogo muestra la diferencia en vivo.
 * Valida como el backend (UNIDAD enteros, el resto hasta 3 decimales, motivo obligatorio, detalle con OTRO)
 * y muestra todos los errores juntos, cada uno junto a su campo. Hace el pedido y avisa el resultado.
 */
@Component({
  selector: 'app-dialogo-ajuste-stock',
  template: `
    <div class="dialogo-fondo">
      <form
        class="dialogo ajuste"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ajuste-titulo"
        novalidate
        (keydown)="alTeclear($event)"
        (submit)="$event.preventDefault(); confirmar()"
      >
        <div>
          <h2 id="ajuste-titulo">Ajustar stock</h2>
          <p class="articulo">{{ articulo().descripcion }}</p>
        </div>

        <dl class="resumen">
          <div class="stock-actual">
            <dt>Stock actual</dt>
            <dd>{{ conUnidad(articulo().stockActual) }}</dd>
          </div>
          @if (modo() === 'STOCK_REAL') {
            <div class="diferencia">
              <dt>Diferencia</dt>
              <dd [class.sube]="diferencia() !== null && diferencia()! > 0" [class.baja]="diferencia() !== null && diferencia()! < 0">
                {{ textoDiferencia() }}
              </dd>
            </div>
          } @else {
            <div class="quedara">
              <dt>Quedará</dt>
              <dd [class.sube]="queda() !== null && queda()! > articulo().stockActual" [class.baja]="queda() !== null && queda()! < articulo().stockActual">
                {{ textoQuedara() }}
              </dd>
            </div>
          }
        </dl>

        <div class="modos" role="radiogroup" aria-label="Cómo cargar el ajuste">
          <button type="button" role="radio" class="modo" data-modo="STOCK_REAL" [attr.aria-checked]="modo() === 'STOCK_REAL'" (click)="elegirModo('STOCK_REAL')">
            Conteo: stock real
          </button>
          <button type="button" role="radio" class="modo" data-modo="CANTIDAD" [attr.aria-checked]="modo() === 'CANTIDAD'" (click)="elegirModo('CANTIDAD')">
            Ingreso / egreso: sumar o restar
          </button>
        </div>

        @if (modo() === 'STOCK_REAL') {
          <div class="campo">
            <label for="stock-real">Stock real (lo que contaste)</label>
            <input
              #numero
              id="stock-real"
              type="text"
              inputmode="decimal"
              autocomplete="off"
              [value]="valorTexto()"
              [attr.aria-invalid]="errores()['stockReal'] ? 'true' : null"
              [attr.aria-describedby]="errores()['stockReal'] ? 'stock-real-error' : null"
              (input)="cambiar('stockReal', $any($event.target).value)"
            />
            @if (errores()['stockReal']; as mensaje) {
              <span id="stock-real-error" class="campo-error">{{ mensaje }}</span>
            }
          </div>
        } @else {
          <div class="campo">
            <label for="cantidad">Cantidad que {{ signo() === 'ENTRA' ? 'entra' : 'sale' }}</label>
            <div class="signos" role="radiogroup" aria-label="Entra o sale">
              <button type="button" role="radio" class="modo" data-signo="ENTRA" [attr.aria-checked]="signo() === 'ENTRA'" (click)="elegirSigno('ENTRA')">
                Entra (+)
              </button>
              <button type="button" role="radio" class="modo" data-signo="SALE" [attr.aria-checked]="signo() === 'SALE'" (click)="elegirSigno('SALE')">
                Sale (−)
              </button>
            </div>
            <input
              #numero
              id="cantidad"
              type="text"
              inputmode="decimal"
              autocomplete="off"
              [value]="valorTexto()"
              [attr.aria-invalid]="errores()['cantidad'] ? 'true' : null"
              [attr.aria-describedby]="errores()['cantidad'] ? 'cantidad-error' : null"
              (input)="cambiar('cantidad', $any($event.target).value)"
            />
            @if (errores()['cantidad']; as mensaje) {
              <span id="cantidad-error" class="campo-error">{{ mensaje }}</span>
            }
          </div>
        }

        <div class="campo">
          <label for="motivo">Motivo</label>
          <select
            id="motivo"
            [value]="motivo() ?? ''"
            [attr.aria-invalid]="errores()['motivo'] ? 'true' : null"
            [attr.aria-describedby]="errores()['motivo'] ? 'motivo-error' : null"
            (change)="cambiar('motivo', $any($event.target).value)"
          >
            <option value="" disabled>Elegí un motivo</option>
            @for (m of motivos; track m) {
              <option [value]="m">{{ motivoLabels[m] }}</option>
            }
          </select>
          @if (errores()['motivo']; as mensaje) {
            <span id="motivo-error" class="campo-error">{{ mensaje }}</span>
          }
        </div>

        <div class="campo">
          <label for="detalle">Detalle {{ motivo() === 'OTRO' ? '' : '(opcional)' }}</label>
          <input
            id="detalle"
            type="text"
            maxlength="150"
            autocomplete="off"
            placeholder="Ej: remito 0001-123, se cayó la caja"
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

        <div class="dialogo-acciones">
          <button type="button" class="cancelar secundario" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
            Cancelar
          </button>
          <button type="submit" class="confirmar" data-tecla="Enter" [disabled]="guardando()">Ajustar stock</button>
        </div>
      </form>
    </div>
  `,
  styles: `
    .articulo {
      margin-top: var(--e-1);
      color: var(--texto-suave);
      font-weight: 600;
    }
    .resumen {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--e-3);
      margin: 0;
    }
    .resumen > div {
      padding: var(--e-2) var(--e-3);
      border: 1px solid var(--borde);
      border-radius: var(--radio);
      background: var(--superficie-2);
    }
    dt {
      color: var(--texto-suave);
      font-size: var(--texto-xs);
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    dd {
      margin: 2px 0 0;
      font-size: var(--texto-lg);
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    dd.sube {
      color: var(--exito);
    }
    dd.baja {
      color: var(--error);
    }
    /* Selectores segmentados, como el de medios de pago del cobro. */
    .modos,
    .signos {
      display: grid;
      grid-template-columns: 1fr 1fr;
      padding: 3px;
      gap: 3px;
      border: 1px solid var(--borde-fuerte);
      border-radius: var(--radio-lg);
      background: var(--superficie-3);
    }
    .signos {
      margin-bottom: var(--e-2);
    }
    .modo {
      min-height: 44px;
      padding: 6px 8px;
      border: 0;
      border-radius: var(--radio);
      background: transparent;
      color: var(--texto);
      font-size: 1rem;
    }
    .modo:hover {
      background: var(--superficie);
    }
    .modo[aria-checked='true'],
    .modo[aria-checked='true']:hover {
      background: var(--acento);
      color: var(--acento-texto);
      box-shadow: var(--sombra-1);
    }
    .modo:focus-visible {
      box-shadow: 0 0 0 3px #fdba74;
    }
    #stock-real,
    #cantidad {
      height: 48px;
      font-size: 1.5rem;
      font-weight: 700;
      text-align: right;
      font-variant-numeric: tabular-nums;
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

  private readonly injector = inject(Injector);

  /** Lo escrito en el campo numérico: el stock real o la cantidad, según el modo. */
  protected readonly valorTexto = signal('');
  protected readonly modo = signal<ModoAjuste>('STOCK_REAL');
  protected readonly signo = signal<Signo>('ENTRA');
  protected readonly motivo = signal<MotivoAjuste | null>(null);
  protected readonly detalle = signal('');
  protected readonly errores = signal<Partial<Record<Campo, string>>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  private readonly campoNumero = viewChild<ElementRef<HTMLInputElement>>('numero');

  /** Diferencia (con signo) contra el stock actual, en vivo; null si lo escrito no es un número. */
  protected readonly diferencia = computed(() => {
    const leido = leerNumero(this.valorTexto());
    if (!leido) return null;
    return (Math.round(leido.valor * 1000) - Math.round(this.articulo().stockActual * 1000)) / 1000;
  });

  /** Cantidad con signo del modo sumar o restar; null si lo escrito no es un número sin signo. */
  private readonly delta = computed(() => {
    const leido = leerNumero(this.valorTexto());
    if (!leido || leido.valor < 0) return null;
    return this.signo() === 'SALE' ? -leido.valor : leido.valor;
  });

  /** Stock resultante en vivo en el modo sumar o restar. */
  protected readonly queda = computed(() => {
    const d = this.delta();
    if (d === null) return null;
    return (Math.round(this.articulo().stockActual * 1000) + Math.round(d * 1000)) / 1000;
  });

  protected readonly textoQuedara = computed(() => {
    const q = this.queda();
    return q === null ? '—' : this.conUnidad(q);
  });

  protected readonly textoDiferencia = computed(() => {
    const d = this.diferencia();
    if (d === null) return '—';
    if (d === 0) return 'Sin diferencia';
    return `${d > 0 ? '+' : ''}${this.conUnidad(d)}`;
  });

  constructor() {
    afterNextRender(() => this.campoNumero()?.nativeElement.focus());
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

  protected conUnidad(cantidad: number): string {
    return cantidadConUnidad(cantidad, this.articulo().unidad);
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

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key !== 'Escape') return;
    evento.preventDefault();
    evento.stopPropagation();
    this.cancelar.emit();
  }

  protected confirmar(): void {
    if (this.guardando()) return;
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
              stockReal: leerNumero(this.valorTexto())!.valor,
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
          const delServidor = err instanceof HttpErrorResponse ? err.error?.errors : null;
          if (delServidor && typeof delServidor === 'object' && Object.keys(delServidor).length > 0) {
            this.errores.set(delServidor as Partial<Record<Campo, string>>);
          } else {
            this.errorGeneral.set(
              err instanceof HttpErrorResponse && err.error?.message
                ? err.error.message
                : 'No se pudo ajustar el stock.',
            );
          }
        },
      });
  }

  /** Las mismas reglas que AjusteStock.java y MotivoAjuste.java, todas juntas. */
  private validar(): Partial<Record<Campo, string>> {
    const errores: Partial<Record<Campo, string>> = {};
    const texto = this.valorTexto().trim();
    const leido = leerNumero(texto);
    if (this.modo() === 'CANTIDAD') {
      if (texto === '') errores.cantidad = 'Escribí la cantidad que entra o sale.';
      else if (!leido) errores.cantidad = 'Escribí un número, por ejemplo 12 o 2,5.';
      else if (leido.valor < 0) errores.cantidad = 'Escribí la cantidad sin signo y elegí si entra o sale.';
      else if (leido.valor === 0) errores.cantidad = 'La cantidad no puede ser cero.';
      else if (leido.valor > MAXIMO)
        errores.cantidad = 'La cantidad es demasiado grande: el máximo es 999.999.999.999,999.';
      else if (this.articulo().unidad === 'UNIDAD' && leido.decimales > 0)
        errores.cantidad = 'Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.';
      else if (leido.decimales > 3) errores.cantidad = 'La cantidad admite hasta 3 decimales.';
      else if (this.queda()! < 0)
        errores.cantidad = `No podés sacar ${String(leido.valor).replace('.', ',')}: hay ${this.conUnidad(this.articulo().stockActual)}.`;
    } else if (texto === '') errores.stockReal = 'Escribí el stock real que contaste.';
    else if (!leido) errores.stockReal = 'Escribí un número, por ejemplo 12 o 145,5.';
    else if (leido.valor < 0) errores.stockReal = 'El stock real no puede ser negativo.';
    else if (leido.valor > MAXIMO)
      errores.stockReal = 'El stock real es demasiado grande: el máximo es 999.999.999.999,999.';
    else if (this.articulo().unidad === 'UNIDAD' && leido.decimales > 0)
      errores.stockReal = 'Este artículo se cuenta por unidad: el stock real tiene que ser un número entero.';
    else if (leido.decimales > 3) errores.stockReal = 'El stock real admite hasta 3 decimales.';
    else if (this.diferencia() === 0)
      errores.stockReal = 'El stock real es igual al actual: no hay nada que ajustar.';

    if (!this.motivo()) errores.motivo = 'Elegí el motivo del ajuste.';
    else if (this.motivo() === 'OTRO' && this.detalle().trim() === '')
      errores.detalle = 'Contá el motivo del ajuste.';
    return errores;
  }
}
