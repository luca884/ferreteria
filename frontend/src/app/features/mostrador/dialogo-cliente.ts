import {
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
  Injector,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, map, merge, of, switchMap } from 'rxjs';
import { erroresPorCampo, mensajeDe } from '../../core/api/api-error';
import { PageResponse } from '../../core/api/page.models';
import { formatearMonto } from '../../core/formato/monto';
import { TrampaFoco } from '../../shared/trampa-foco';
import {
  CONDICIONES_IVA,
  CONDICION_IVA_LABELS,
  Cliente,
  ClienteRequest,
  CondicionIva,
  FALTA_CUIT,
  FALTA_DOMICILIO,
  NO_ES_CUIT,
  TIPOS_DOCUMENTO,
  TipoDocumento,
  documentoLegible,
  requiereDatosFiscales,
} from '../clientes/cliente.models';
import { ClientesService } from '../clientes/clientes.service';

const RESULTADOS = 8;
const ESPERA_MS = 250;

/**
 * Cliente de la venta (F9, CL-005): busca por razón social o documento entre los clientes activos, elige con ↑↓ y Enter,
 * y da de alta uno nuevo sin salir del mostrador. Sin cliente la venta es a Consumidor Final: con uno elegido ofrece
 * quitarlo. Habla con el servicio de clientes, porque la búsqueda es parte del diálogo. No descarta escaneos: tipear un
 * DNI o un CUIT rápido es lo normal acá.
 */
@Component({
  selector: 'app-dialogo-cliente',
  imports: [TrampaFoco],
  template: `
    <div class="dialogo-fondo">
      <div
        class="dialogo cliente"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogo-cliente-titulo"
        appTrampaFoco
        (keydown)="alTeclear($event)"
      >
        <h2 id="dialogo-cliente-titulo">Cliente</h2>
        @if (actual(); as nombre) {
          <p class="actual">
            Cliente actual: <strong>{{ nombre }}</strong>
          </p>
        }

        @if (!enAlta()) {
          <input
            #buscador
            type="search"
            role="combobox"
            aria-label="Buscar cliente"
            aria-controls="dialogo-cliente-resultados"
            aria-autocomplete="list"
            autocomplete="off"
            spellcheck="false"
            placeholder="Razón social, CUIT o DNI…"
            [attr.aria-expanded]="resultados().length > 0"
            [attr.aria-activedescendant]="resultados().length > 0 ? 'cliente-' + resultados()[resaltado()].id : null"
            [value]="consulta()"
            (input)="escribir($any($event.target).value)"
          />
          @if (errorBusqueda()) {
            <p class="error" role="alert">No se pudo buscar clientes. Probá de nuevo.</p>
          }
          @if (resultados().length > 0) {
            <ul id="dialogo-cliente-resultados" class="resultados" role="listbox" aria-label="Clientes">
              @for (c of resultados(); track c.id; let i = $index) {
                <li
                  role="option"
                  [id]="'cliente-' + c.id"
                  [attr.aria-selected]="i === resaltado()"
                  [class.resaltado]="i === resaltado()"
                  (click)="elegir.emit(c)"
                >
                  <span class="nombre">{{ c.razonSocial }}</span
                  >{{ ' ' }}<span class="lado"
                    ><span class="documento">{{ documentoLegible(c) }}</span>
                    @if (c.cuentaCorrienteHabilitada) {
                      {{ ' ' }}<span class="cta-cte">Cta. cte.</span>
                    }
                    @if (esDueno() && cifras(c); as texto) {
                      {{ ' ' }}<span class="cifras">{{ texto }}</span>
                    }</span
                  >
                </li>
              }
            </ul>
            @if (total() > resultados().length) {
              <p class="ayuda hay-mas" role="status">
                @if (consultaBuscada() === '') {
                  Se ven {{ resultados().length }} de {{ total() }} clientes. Escribí el nombre, CUIT o DNI para buscar
                  los demás.
                } @else {
                  Se ven {{ resultados().length }} de {{ total() }} resultados. Escribí más para afinar la búsqueda.
                }
              </p>
            }
          } @else if (!cargando() && !errorBusqueda()) {
            <p class="sin-resultados">No hay clientes con esa búsqueda.</p>
          }
          <div class="dialogo-acciones">
            @if (actual()) {
              <button type="button" class="secundario" (click)="quitar.emit()">Quitar cliente</button>
            }
            <button type="button" class="secundario" (click)="abrirAlta()">Nuevo cliente</button>
            <button type="button" class="cancelar" data-tecla="Esc" aria-keyshortcuts="Escape" (click)="cancelar.emit()">
              Cancelar
            </button>
          </div>
        } @else {
          <form class="alta-rapida" (submit)="guardarAlta($event)" novalidate>
            @if (errorAlta(); as mensaje) {
              <p class="error" role="alert">{{ mensaje }}</p>
            } @else if (erroresCampos().length > 0) {
              <div class="error" role="alert">
                <strong>Revisá estos datos:</strong>
                <ul>
                  @for (m of erroresCampos(); track $index) {
                    <li>{{ m }}</li>
                  }
                </ul>
              </div>
            }
            <label class="campo" for="nuevo-razon-social">Razón social</label>
            <input
              #razonSocial
              id="nuevo-razon-social"
              name="razonSocial"
              autocomplete="off"
              spellcheck="false"
              [value]="nuevoNombre()"
              [attr.aria-invalid]="errorNombre() ? 'true' : null"
              [attr.aria-describedby]="errorNombre() ? 'nuevo-razon-social-error' : null"
              (input)="nuevoNombre.set($any($event.target).value); limpiarErrores()"
            />
            @if (errorNombre(); as mensaje) {
              <span id="nuevo-razon-social-error" class="campo-error">{{ mensaje }}</span>
            }
            <label class="campo" for="nuevo-condicion">Condición frente al IVA</label>
            <select
              id="nuevo-condicion"
              name="condicionIva"
              (change)="nuevaCondicion.set($any($event.target).value); limpiarErrores()"
            >
              @for (c of condiciones; track c) {
                <option [value]="c" [selected]="c === nuevaCondicion()">{{ condicionLabels[c] }}</option>
              }
            </select>
            <div class="fila-doc">
              <div>
                <label class="campo" for="nuevo-tipo">Documento</label>
                <select
                  id="nuevo-tipo"
                  name="tipo"
                  [attr.aria-invalid]="errorTipo() ? 'true' : null"
                  [attr.aria-describedby]="errorTipo() ? 'nuevo-tipo-error' : null"
                  (change)="nuevoTipo.set($any($event.target).value); limpiarErrores()"
                >
                  <option value="">Sin documento</option>
                  @for (t of tipos; track t) {
                    <option [value]="t">{{ t }}</option>
                  }
                </select>
                @if (errorTipo(); as mensaje) {
                  <span id="nuevo-tipo-error" class="campo-error">{{ mensaje }}</span>
                }
              </div>
              <div>
                <label class="campo" for="nuevo-numero">Número</label>
                <input
                  id="nuevo-numero"
                  name="numero"
                  inputmode="numeric"
                  autocomplete="off"
                  spellcheck="false"
                  placeholder="30-71234567-1"
                  [attr.aria-invalid]="errorNumero() ? 'true' : null"
                  [attr.aria-describedby]="errorNumero() ? 'nuevo-numero-error' : null"
                  (input)="nuevoNumero.set($any($event.target).value); limpiarErrores()"
                />
                @if (errorNumero(); as mensaje) {
                  <span id="nuevo-numero-error" class="campo-error">{{ mensaje }}</span>
                }
              </div>
            </div>
            @if (pideDatosFiscales()) {
              <label class="campo" for="nuevo-domicilio">Domicilio</label>
              <input
                id="nuevo-domicilio"
                name="domicilio"
                autocomplete="off"
                [value]="nuevoDomicilio()"
                [attr.aria-invalid]="errorDomicilio() ? 'true' : null"
                [attr.aria-describedby]="errorDomicilio() ? 'nuevo-domicilio-error' : null"
                (input)="nuevoDomicilio.set($any($event.target).value); limpiarErrores()"
              />
              @if (errorDomicilio(); as mensaje) {
                <span id="nuevo-domicilio-error" class="campo-error">{{ mensaje }}</span>
              }
            }
            <label class="campo" for="nuevo-telefono">Teléfono <small>(opcional)</small></label>
            <input
              id="nuevo-telefono"
              name="telefono"
              inputmode="tel"
              autocomplete="off"
              [attr.aria-invalid]="errorTelefono() ? 'true' : null"
              [attr.aria-describedby]="errorTelefono() ? 'nuevo-telefono-error' : null"
              (input)="nuevoTelefono.set($any($event.target).value); limpiarErrores()"
            />
            @if (errorTelefono(); as mensaje) {
              <span id="nuevo-telefono-error" class="campo-error">{{ mensaje }}</span>
            }
            <div class="dialogo-acciones">
              <button type="button" class="secundario" (click)="volverABuscar()">Volver a buscar</button>
              <button type="submit" class="aceptar primario" [disabled]="guardando()">Guardar y elegir</button>
            </div>
          </form>
        }
      </div>
    </div>
  `,
  styles: `
    .actual {
      margin: 0;
      color: var(--texto-suave);
    }
    .resultados {
      margin: 0;
      padding: 0;
      list-style: none;
      border: 1px solid var(--borde);
      border-radius: var(--radio-lg);
      overflow: hidden;
    }
    .resultados li {
      display: flex;
      justify-content: space-between;
      gap: var(--e-3);
      padding: 10px 14px;
      cursor: pointer;
    }
    .resultados li + li {
      border-top: 1px solid var(--borde);
    }
    .resultados li.resaltado {
      background: var(--acento-suave);
      color: var(--acento-profundo);
    }
    .nombre {
      font-weight: 600;
    }
    .documento {
      color: var(--texto-suave);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    /* El documento y lo de la cuenta corriente van juntos a la derecha de la fila. */
    .lado {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: flex-end;
      gap: 0 var(--e-2);
      text-align: right;
    }
    .cta-cte {
      padding: 0 6px;
      border: 1px solid var(--acento);
      border-radius: var(--radio);
      color: var(--acento-profundo);
      font-size: var(--texto-xs);
      font-weight: 600;
      white-space: nowrap;
    }
    .cifras {
      flex-basis: 100%;
      color: var(--texto-suave);
      font-size: var(--texto-sm);
      font-variant-numeric: tabular-nums;
    }
    .sin-resultados {
      margin: 0;
      color: var(--texto-suave);
    }
    /* El aviso usa el estilo global .ayuda (color y ancho); acá solo el margen y el tamaño. */
    .hay-mas {
      margin: 0;
      font-size: var(--texto-sm);
    }
    .alta-rapida {
      display: flex;
      flex-direction: column;
      gap: var(--e-2);
    }
    .fila-doc {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
      gap: var(--e-3);
    }
    /* El «(opcional)» va al lado del nombre del campo, no en un renglón aparte. */
    label.campo {
      display: block;
    }
  `,
})
export class DialogoCliente {
  private readonly service = inject(ClientesService);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** La razón social del cliente que ya tiene el ticket; null es Consumidor Final. */
  readonly actual = input<string | null>(null);
  /** Solo el Dueño ve el saldo y el límite de la cuenta corriente (CC-007); el indicador «Cta. cte.» lo ven todos. */
  readonly esDueno = input(false);

  readonly elegir = output<Cliente>();
  readonly quitar = output<void>();
  readonly cancelar = output<void>();

  private readonly buscador = viewChild<ElementRef<HTMLInputElement>>('buscador');
  private readonly razonSocial = viewChild<ElementRef<HTMLInputElement>>('razonSocial');

  protected readonly tipos = TIPOS_DOCUMENTO;
  protected readonly condiciones = CONDICIONES_IVA;
  protected readonly condicionLabels = CONDICION_IVA_LABELS;
  protected readonly documentoLegible = documentoLegible;

  /**
   * «Saldo $ X · Límite $ Y» para el Dueño (CC-019). Con la cuenta deshabilitada y sin deuda no hay nada que decir; con la
   * cuenta deshabilitada pero con saldo (se la sacaron a un cliente que debe) solo el saldo.
   */
  protected cifras(c: Cliente): string | null {
    if (c.saldo === undefined) return null;
    if (c.cuentaCorrienteHabilitada && c.limiteCuentaCorriente !== undefined) {
      return `Saldo ${formatearMonto(c.saldo)} · Límite ${formatearMonto(c.limiteCuentaCorriente)}`;
    }
    return c.saldo !== 0 ? `Saldo ${formatearMonto(c.saldo)}` : null;
  }

  protected readonly consulta = signal('');
  /** La última búsqueda que respondió, con el texto que la trajo; null si todavía no hubo o falló. */
  private readonly respuesta = signal<{ q: string; pagina: PageResponse<Cliente> } | null>(null);
  protected readonly resultados = computed(() => this.respuesta()?.pagina.items ?? []);
  /** Cuántos clientes cumplen la búsqueda, aunque solo se vean los primeros: si son más, se avisa. */
  protected readonly total = computed(() => this.respuesta()?.pagina.totalElements ?? 0);
  /** El texto de la búsqueda que trajo los resultados (no lo que se está tipeando todavía). */
  protected readonly consultaBuscada = computed(() => this.respuesta()?.q.trim() ?? '');
  protected readonly resaltado = signal(0);
  protected readonly cargando = signal(true);
  protected readonly errorBusqueda = signal(false);

  protected readonly enAlta = signal(false);
  protected readonly nuevoNombre = signal('');
  protected readonly nuevoTipo = signal<TipoDocumento | ''>('');
  protected readonly nuevoNumero = signal('');
  protected readonly nuevoTelefono = signal('');
  /** QA 05/11 (A-12): con la condición, el alta rápida sirve para hacerle una A a un cliente nuevo sin salir de la venta. */
  protected readonly nuevaCondicion = signal<CondicionIva>('CONSUMIDOR_FINAL');
  protected readonly nuevoDomicilio = signal('');
  /** CU-11 (D4): RI, Monotributista y Exento llevan CUIT y domicilio, igual que en el alta de Clientes. */
  protected readonly pideDatosFiscales = computed(() => requiereDatosFiscales(this.nuevaCondicion()));
  protected readonly guardando = signal(false);
  protected readonly errorNombre = signal<string | null>(null);
  protected readonly errorTipo = signal<string | null>(null);
  protected readonly errorNumero = signal<string | null>(null);
  protected readonly errorTelefono = signal<string | null>(null);
  protected readonly errorDomicilio = signal<string | null>(null);
  protected readonly errorAlta = signal<string | null>(null);
  protected readonly erroresCampos = signal<string[]>([]);

  private readonly escrito = new Subject<string>();

  constructor() {
    // La primera búsqueda es inmediata (los primeros clientes ya a la vista); al escribir se espera a que termine de tipear.
    merge(of(''), this.escrito.pipe(debounceTime(ESPERA_MS)))
      .pipe(
        switchMap((q) => {
          this.cargando.set(true);
          return this.service.buscar({ q, soloActivos: true, page: 0, size: RESULTADOS }).pipe(
            map((pagina) => ({ q, pagina })),
            catchError(() => of(null)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((respuesta) => {
        this.cargando.set(false);
        this.errorBusqueda.set(respuesta === null);
        this.respuesta.set(respuesta);
        this.resaltado.set(0);
      });
    afterNextRender(() => this.buscador()?.nativeElement.focus());
  }

  protected escribir(texto: string): void {
    this.consulta.set(texto);
    this.escrito.next(texto);
  }

  protected alTeclear(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') {
      evento.preventDefault();
      evento.stopPropagation();
      this.cancelar.emit();
      return;
    }
    if (this.enAlta()) return;
    const n = this.resultados().length;
    if (evento.key === 'ArrowDown' || evento.key === 'ArrowUp') {
      evento.preventDefault();
      evento.stopPropagation();
      if (n > 0) this.resaltado.update((i) => Math.min(Math.max(i + (evento.key === 'ArrowDown' ? 1 : -1), 0), n - 1));
      return;
    }
    // Enter elige solo desde el campo: sobre un botón hace lo que dice el botón.
    if (evento.key === 'Enter' && evento.target === this.buscador()?.nativeElement) {
      evento.preventDefault();
      evento.stopPropagation();
      const elegido = this.resultados()[this.resaltado()];
      if (elegido) this.elegir.emit(elegido);
    }
  }

  protected abrirAlta(): void {
    this.nuevoNombre.set(this.consulta().trim());
    this.limpiarErrores();
    this.enAlta.set(true);
    afterNextRender(
      () => {
        const campo = this.razonSocial()?.nativeElement;
        campo?.focus();
        campo?.select();
      },
      { injector: this.injector },
    );
  }

  protected volverABuscar(): void {
    this.limpiarErrores();
    this.enAlta.set(false);
    afterNextRender(() => this.buscador()?.nativeElement.focus(), { injector: this.injector });
  }

  protected limpiarErrores(): void {
    this.errorNombre.set(null);
    this.errorTipo.set(null);
    this.errorNumero.set(null);
    this.errorTelefono.set(null);
    this.errorDomicilio.set(null);
    this.errorAlta.set(null);
    this.erroresCampos.set([]);
  }

  protected guardarAlta(evento: Event): void {
    evento.preventDefault();
    if (this.guardando()) return;
    this.limpiarErrores();
    const nombre = this.nuevoNombre().trim();
    const tipo = this.nuevoTipo();
    const numero = this.nuevoNumero().trim();
    if (!nombre) this.errorNombre.set('La razón social es obligatoria.');
    if (numero && !tipo) this.errorTipo.set('Elegí el tipo de documento.');
    if (tipo && !numero) this.errorNumero.set('Falta el número de documento.');
    const condicionIva = this.nuevaCondicion();
    const fiscal = requiereDatosFiscales(condicionIva);
    const domicilio = fiscal ? this.nuevoDomicilio().trim() : '';
    if (fiscal) {
      // Lo mismo que el alta de Clientes y el servidor (CU-11, D4), para avisar en cada campo antes de mandar.
      if (!tipo && !numero) this.errorNumero.set(FALTA_CUIT);
      else if (tipo && tipo !== 'CUIT') this.errorTipo.set(NO_ES_CUIT);
      if (!domicilio) this.errorDomicilio.set(FALTA_DOMICILIO);
    }
    if (this.errorNombre() || this.errorTipo() || this.errorNumero() || this.errorDomicilio()) return;

    const telefono = this.nuevoTelefono().trim();
    const request: ClienteRequest = {
      razonSocial: nombre,
      ...(tipo && numero ? { tipoDocumento: tipo, numeroDocumento: numero } : {}),
      condicionIva,
      ...(domicilio ? { domicilio } : {}),
      ...(telefono ? { telefono } : {}),
    };
    this.guardando.set(true);
    this.service.crear(request).subscribe({
      next: (creado) => {
        this.guardando.set(false);
        this.elegir.emit(creado);
      },
      error: (err: unknown) => {
        this.guardando.set(false);
        // Los errores de un campo del formulario van debajo de ese campo; el resto, en la lista de arriba.
        const { razonSocial, tipoDocumento, numeroDocumento, telefono, domicilio, ...otros } = erroresPorCampo(err);
        this.errorNombre.set(razonSocial ?? null);
        this.errorTipo.set(tipoDocumento ?? null);
        this.errorNumero.set(numeroDocumento ?? null);
        this.errorTelefono.set(telefono ?? null);
        this.errorDomicilio.set(domicilio ?? null);
        this.erroresCampos.set(Object.values(otros));
        const enUnCampo = razonSocial || tipoDocumento || numeroDocumento || telefono || domicilio;
        if (!enUnCampo && Object.keys(otros).length === 0)
          this.errorAlta.set(mensajeDe(err, 'No se pudo guardar el cliente.'));
        // El botón se deshabilitó mientras guardaba y el foco se perdió: va al primer campo con error o, si no hay, al botón.
        afterNextRender(
          () => {
            const raiz: HTMLElement = this.host.nativeElement;
            const destino =
              raiz.querySelector<HTMLElement>('form.alta-rapida [aria-invalid="true"]') ??
              raiz.querySelector<HTMLElement>('form.alta-rapida button[type="submit"]');
            destino?.focus();
          },
          { injector: this.injector },
        );
      },
    });
  }
}
