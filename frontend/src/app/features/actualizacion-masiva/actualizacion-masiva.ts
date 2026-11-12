import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { formatearFechaHora } from '../../core/formato/fecha-hora';
import { MontoPipe, formatearCosto } from '../../core/formato/monto';
import { porUnidad } from '../../core/formato/unidad';
import {
  Component,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { erroresDeCampos, mensajeDe } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { CampoMoneda } from '../../shared/campo-moneda';
import { DialogoDetalle } from '../../shared/dialogo-detalle';
import { ImporteInput } from '../../shared/importe-input';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import { Moneda } from '../articulos/articulo.models';
import { Importacion } from '../importaciones/importacion.models';
import { ImportacionesService } from '../importaciones/importaciones.service';
import {
  CambioMasivo,
  ConteosMasivos,
  ModoMasivo,
  PedidoMasivo,
  ResultadoMasivo,
  VistaPreviaMasiva,
} from './actualizacion-masiva.models';
import { ActualizacionMasivaService } from './actualizacion-masiva.service';

const MENSAJE_GENERICO = 'No se pudo completar la operación. Probá de nuevo.';
const MENSAJE_CONFLICTO =
  'Los artículos cambiaron desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.';

/** Errores con un motivo que el Dueño puede corregir: se muestra el mensaje del servidor. */
const ESTADOS_CON_MENSAJE = [400, 403, 404, 409];

/** Los modos que cambian el precio final y dejan a los artículos con precio manual (CU-14). */
const MODOS_DE_PRECIO: readonly ModoMasivo[] = ['PRECIO_PORCENTAJE', 'PRECIO_MONTO'];
/** Lo que pasa con el precio al cambiar el costo (misma regla que el servidor: el precio manual no se toca). */
const RECALCULA_CON_EL_MARGEN =
  'El precio se recalcula con el margen de cada artículo. Los de precio manual quedan con su precio.';
/** Lo que pasa con el precio al cambiar el rubro (misma regla que el servidor: propio > rubro > ferretería). */
const PISTA_RUBRO_DESTINO =
  'El precio se recalcula con el margen del rubro destino. Los de margen propio o precio manual cambian de rubro, pero no de precio.';

@Component({
  selector: 'app-actualizacion-masiva',
  imports: [ReactiveFormsModule, DecimalPipe, MontoPipe, BarraAcciones, CampoMoneda, ImporteInput, DialogoDetalle],
  templateUrl: './actualizacion-masiva.html',
  styleUrl: './actualizacion-masiva.css',
})
export class ActualizacionMasiva implements OnInit {
  private readonly service = inject(ActualizacionMasivaService);
  private readonly proveedoresService = inject(ProveedoresService);
  private readonly rubrosService = inject(RubrosService);
  private readonly importacionesService = inject(ImportacionesService);
  protected readonly auth = inject(AuthService);

  protected readonly proveedores = signal<Proveedor[]>([]);
  protected readonly rubros = signal<Rubro[]>([]);
  /** El historial de importaciones, completo: se pide una vez y se filtra en el cliente por proveedor. */
  private readonly importaciones = signal<Importacion[]>([]);
  private readonly importacionesFallo = signal(false);
  protected readonly vistaPrevia = signal<VistaPreviaMasiva | null>(null);
  /** El pedido exacto que originó la vista previa: es el que se aplica, aunque el formulario se toque. */
  private readonly pedidoVigente = signal<PedidoMasivo | null>(null);
  protected readonly generando = signal(false);
  protected readonly aplicando = signal(false);
  /**
   * El primer clic abre la confirmación (el cambio no se puede deshacer) y recién el segundo aplica.
   * `todo`: sin filtro, alcanza a todo el catálogo; `filtrado`: alcanza solo a lo filtrado.
   */
  protected readonly confirmando = signal<'todo' | 'filtrado' | null>(null);
  protected readonly resultado = signal<ResultadoMasivo | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);

  /**
   * Filtro, modo y valor. El valor lo escribe la máscara de importes (hasta 2 decimales y signo) y es un número o null.
   * Con «Cambiar el rubro» el valor no se usa y en su lugar va el rubro destino.
   */
  protected readonly form = new FormGroup({
    proveedorId: new FormControl<number | null>(null),
    rubroId: new FormControl<number | null>(null),
    importacionId: new FormControl<number | null>(null),
    q: new FormControl('', { nonNullable: true }),
    incluirInactivos: new FormControl(false, { nonNullable: true }),
    modo: new FormControl<ModoMasivo | null>(null),
    valor: new FormControl<number | null>(null),
    rubroDestinoId: new FormControl<number | null>(null),
  });

  protected readonly pistaRubroDestino = PISTA_RUBRO_DESTINO;

  /**
   * El formulario como signal. Se lee con `getRawValue()` y se actualiza a mano: soltar una selección con
   * `emitEvent: false` no pasa por `valueChanges` y los `computed` quedarían con el valor viejo.
   */
  private readonly formulario = signal(this.form.getRawValue());

  private sincronizarFormulario(): void {
    this.formulario.set(this.form.getRawValue());
  }

  protected readonly etiquetaDelValor = computed(() => {
    const modo = this.formulario().modo;
    if (modo === 'MARGEN') return 'Margen (%)';
    return modo === 'COSTO_MONTO' || modo === 'PRECIO_MONTO' ? 'Monto' : 'Porcentaje (%)';
  });

  /** El signo del valor (arreglos-compras, S6): solo el monto del precio final es siempre en pesos. */
  protected readonly monedaDelValor = computed(() => (this.formulario().modo === 'PRECIO_MONTO' ? 'ARS' : null));

  /** Las importaciones que se ofrecen, de la más reciente a la más vieja y, con proveedor elegido, solo las suyas. */
  protected readonly importacionesOfrecidas = computed(() => {
    const proveedorId = this.formulario().proveedorId ?? null;
    return [...this.importaciones()]
      .filter((i) => proveedorId === null || i.proveedorId === proveedorId)
      .sort((a, b) => b.fecha.localeCompare(a.fecha) || b.id - a.id)
      .map((i) => ({
        id: i.id,
        proveedorId: i.proveedorId,
        texto: `${i.proveedorNombre ?? 'Proveedor'} · ${i.archivo} · ${formatearFechaHora(i.fecha)}`,
      }));
  });

  /** Lo que dice el selector cuando no hay nada que elegir. */
  protected readonly sinImportaciones = computed(() => {
    if (this.importacionesFallo()) return 'No disponible';
    return this.importaciones().length === 0
      ? 'Todavía no importaste ninguna lista'
      : 'Este proveedor no tiene importaciones';
  });

  /** Qué hace la operación elegida, debajo del valor. Las del precio final en % se explican con el aviso de abajo. */
  protected readonly pistaDelValor = computed(() => {
    switch (this.formulario().modo) {
      case 'COSTO_PORCENTAJE':
        return RECALCULA_CON_EL_MARGEN;
      case 'COSTO_MONTO':
        return `Positivo sube el costo, negativo lo baja. Se suma en la moneda de cada artículo. ${RECALCULA_CON_EL_MARGEN}`;
      case 'MARGEN':
        return 'El artículo pasa a tener margen propio y deja de seguir el del rubro.';
      case 'PRECIO_MONTO':
        return 'Positivo sube el precio final, negativo lo baja. Siempre en pesos.';
      default:
        return null;
    }
  });

  /** Con un modo del precio final se avisa, sin bloquear, que los artículos quedan con precio manual. */
  protected readonly cambiaElPrecioFinal = computed(() => {
    const modo = this.formulario().modo;
    return modo !== null && modo !== undefined && MODOS_DE_PRECIO.includes(modo);
  });

  /** «Cambiar el rubro» en el formulario: el campo del valor se cambia por el del rubro destino. */
  protected readonly eligeRubroDestino = computed(() => this.formulario().modo === 'CAMBIAR_RUBRO');

  /** La vista previa a la vista es de un cambio de rubro: suma la columna «Rubro». */
  protected readonly vistaPreviaDeRubro = computed(() => this.pedidoVigente()?.modo === 'CAMBIAR_RUBRO');

  /** El nombre del rubro destino de la vista previa a la vista, para la confirmación; null con los otros modos. */
  protected readonly destinoVigente = computed(() => {
    const destino = this.pedidoVigente()?.rubroDestinoId;
    return destino === undefined ? null : this.nombreDeRubro(destino);
  });

  /**
   * Hace falta el modo y un valor numérico (o, al cambiar el rubro, el destino), y que no haya otra vista previa en
   * camino ni se esté aplicando.
   */
  protected readonly puedeGenerar = computed(() => {
    const f = this.formulario();
    const completo = this.eligeRubroDestino()
      ? (f.rubroDestinoId ?? null) !== null
      : f.valor !== null && this.errorDelValor() === null;
    return (f.modo ?? null) !== null && completo && !this.generando() && !this.aplicando();
  });

  /** Mensaje junto al campo cuando el número escrito está fuera de rango (lo mismo que valida el backend). */
  protected readonly errorDelValor = computed(() => {
    const f = this.formulario();
    const valor = f.valor;
    if (valor === null || f.modo === null || f.modo === undefined) return null;
    if (f.modo === 'CAMBIAR_RUBRO') return null;
    if (f.modo === 'COSTO_MONTO' || f.modo === 'PRECIO_MONTO') return valor === 0 ? 'El monto no puede ser cero.' : null;
    if (f.modo === 'MARGEN') {
      return valor < 0 || valor > 1000 ? 'El margen tiene que estar entre 0 y 1.000.' : null;
    }
    return valor < -99.99 || valor > 1000
      ? 'El porcentaje tiene que estar entre -99,99 y 1.000.'
      : null;
  });

  /** Por qué «Ver vista previa» está apagado, para decirlo al lado del botón; null si se puede pedir. */
  protected readonly motivoSinVistaPrevia = computed(() => {
    if (this.puedeGenerar() || this.generando() || this.aplicando()) return null;
    const f = this.formulario();
    if ((f.modo ?? null) === null) return 'Para ver la vista previa, elegí la operación.';
    if (this.eligeRubroDestino()) return 'Para ver la vista previa, elegí el rubro destino.';
    const nombre =
      f.modo === 'MARGEN'
        ? 'el margen'
        : f.modo === 'COSTO_MONTO' || f.modo === 'PRECIO_MONTO'
          ? 'el monto'
          : 'el porcentaje';
    if (this.errorDelValor() !== null) return `Para ver la vista previa, corregí ${nombre}.`;
    return `Para ver la vista previa, escribí ${nombre}.`;
  });

  private readonly confirmacionEl = viewChild<ElementRef<HTMLElement>>('confirmacionEl');
  private readonly confirmarEl = viewChild<ElementRef<HTMLButtonElement>>('confirmarEl');
  private readonly generarEl = viewChild<ElementRef<HTMLButtonElement>>('generarEl');
  private readonly injector = inject(Injector);

  /** Sube con cada cambio: una respuesta en vuelo de un pedido viejo se descarta. */
  private version = 0;

  constructor() {
    // Cualquier cambio del filtro, el modo o el valor deja la vista previa vieja sin efecto.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.sincronizarFormulario();
      this.invalidar();
    });
    // Al cambiar el proveedor, una importación de otro proveedor deja de valer (sin invalidar dos veces).
    this.form.controls.proveedorId.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((proveedorId) => {
        const elegida = this.importaciones().find(
          (i) => i.id === this.form.controls.importacionId.value,
        );
        if (elegida && proveedorId !== null && elegida.proveedorId !== proveedorId) {
          this.form.controls.importacionId.setValue(null, { emitEvent: false });
          this.sincronizarFormulario();
        }
      });
    // Sin nada que elegir el selector queda apagado; el estado del control no es un cambio del filtro.
    effect(() => {
      const sinOpciones = this.importacionesOfrecidas().length === 0;
      const control = this.form.controls.importacionId;
      untracked(() =>
        sinOpciones ? control.disable({ emitEvent: false }) : control.enable({ emitEvent: false }),
      );
    });
    // La confirmación se anuncia y se contesta con el teclado sin buscarla.
    effect(() => this.confirmacionEl()?.nativeElement.focus());
  }

  /** Ids para aria-describedby del valor: la pista de la operación y, si hay, el error. */
  protected describirValor(): string | null {
    const ids = [
      ...(this.pistaDelValor() ? ['valor-pista'] : []),
      ...(this.errorDelValor() ? ['valor-error'] : []),
    ];
    return ids.length > 0 ? ids.join(' ') : null;
  }

  /** Un costo en la moneda del artículo: `$ 100,00` o `US$ 10,00`. */
  protected costo(valor: number, moneda: Moneda | undefined): string {
    return formatearCosto(valor, moneda);
  }

  /**
   * Lo que va después de cada costo y precio de la fila: « / kg», « / m», « / l»; nada por unidad (#36). Con espacios
   * duros, así el renglón del «Costo real», que puede partirse, nunca deja el «kg» solo.
   */
  protected porUnidadDe(c: CambioMasivo): string {
    return porUnidad(c.unidad ?? 'UNIDAD').replaceAll(' ', ' ');
  }

  /**
   * #97: el costo real de la fila (el costo más los recargos), «$ A → $ B» o uno solo si no cambia; null sin
   * recargos. Con él la cuenta costo × margen × IVA cierra en la vista previa. Cada importe lleva su «/ kg» (#36).
   */
  protected costoRealDe(c: CambioMasivo): string | null {
    if (c.recargosPorcentaje == null || c.costoRealAnterior == null || c.costoRealNuevo == null) return null;
    const nuevo = this.costo(c.costoRealNuevo, c.moneda) + this.porUnidadDe(c);
    if (c.costoRealAnterior === c.costoRealNuevo) return nuevo;
    return `${this.costo(c.costoRealAnterior, c.moneda) + this.porUnidadDe(c)} → ${nuevo}`;
  }

  /** El nombre del rubro para la vista previa: «Sin rubro» si no tiene; «—» si ya no está en la lista. */
  protected nombreDeRubro(id: number | null | undefined): string {
    if (id === null || id === undefined) return 'Sin rubro';
    return this.rubros().find((r) => r.id === id)?.nombre ?? '—';
  }

  /** Enter en el valor pide la vista previa (no hace nada si todavía falta algo). */
  protected alEnter(evento: Event): void {
    evento.preventDefault();
    if (this.puedeGenerar()) this.generarVistaPrevia();
  }

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    this.cargarSelectores();
  }

  /** Con «Cambiar el rubro», «Sin cambio de precio» se parte en los que ya están en el destino y los que solo se mueven. */
  protected tarjetasDe(conteos: ConteosMasivos): { etiqueta: string; valor: number }[] {
    const sinCambio = this.vistaPreviaDeRubro()
      ? [
          { etiqueta: 'Ya en el destino', valor: conteos.yaEnElDestino },
          { etiqueta: 'Solo cambia el rubro', valor: conteos.sinCambioDePrecio - conteos.yaEnElDestino },
        ]
      : [{ etiqueta: 'Sin cambio de precio', valor: conteos.sinCambioDePrecio }];
    return [
      { etiqueta: 'Artículos', valor: conteos.total },
      { etiqueta: 'Suben', valor: conteos.suben },
      { etiqueta: 'Bajan', valor: conteos.bajan },
      ...sinCambio,
      { etiqueta: 'Con error', valor: conteos.conError },
      { etiqueta: 'Con advertencia', valor: conteos.conAdvertencia },
    ];
  }

  /** `uno` si n es 1; `varios` en cualquier otro caso (0 incluido). */
  protected plural(n: number, uno: string, varios: string): string {
    return n === 1 ? uno : varios;
  }

  protected generarVistaPrevia(): void {
    const pedido = this.armarPedido();
    if (pedido === null || this.generando() || this.aplicando()) return;
    // Pedir otra vista previa deja sin efecto la anterior y cualquier respuesta que todavía esté en camino.
    this.invalidar();
    const version = this.version;
    this.generando.set(true);
    this.service.vistaPrevia(pedido).subscribe({
      next: (vistaPrevia) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.pedidoVigente.set(pedido);
        this.vistaPrevia.set(vistaPrevia);
      },
      error: (err: unknown) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.mostrarError(err);
      },
    });
  }

  /**
   * Abre la confirmación del botón «Actualizar N artículos»; con el filtro vacío avisa que alcanza a
   * todo el catálogo. Nunca aplica: dos activaciones seguidas antes de que se redibuje el botón no
   * pueden saltearse la confirmación. Con `modificados` en 0 no hay nada que aplicar.
   */
  protected abrirConfirmacion(): void {
    const vistaPrevia = this.vistaPrevia();
    const pedido = this.pedidoVigente();
    if (this.aplicando() || vistaPrevia === null || pedido === null) return;
    if (vistaPrevia.conteos.modificados === 0) return;
    this.confirmando.set(filtroVacio(pedido) ? 'todo' : 'filtrado');
  }

  /** Aplica lo que muestra la vista previa. Solo se llega desde la confirmación ya abierta. */
  protected confirmar(): void {
    const vistaPrevia = this.vistaPrevia();
    const pedido = this.pedidoVigente();
    if (this.aplicando() || vistaPrevia === null || pedido === null) return;
    if (vistaPrevia.conteos.modificados === 0 || this.confirmando() === null) return;
    this.confirmando.set(null);
    this.limpiarMensajes();
    this.aplicando.set(true);
    this.service.aplicar({ ...pedido, huella: vistaPrevia.huella }).subscribe({
      next: (resultado) => {
        this.aplicando.set(false);
        this.descartarVistaPrevia();
        // Operación, porcentaje y rubro destino se limpian (el filtro queda): no se puede aplicar dos veces por error.
        // El cambio del formulario limpia los mensajes; el resultado se muestra recién después.
        this.form.patchValue({ modo: null, valor: null, rubroDestinoId: null });
        this.resultado.set(resultado);
      },
      error: (err: unknown) => {
        this.aplicando.set(false);
        // Sea cual sea el motivo, el botón vuelve recién con una vista previa nueva.
        this.descartarVistaPrevia();
        this.mostrarError(err);
        // La confirmación y el botón de actualizar se fueron con el foco adentro: lo que sigue es la vista previa.
        afterNextRender(() => this.generarEl()?.nativeElement.focus(), { injector: this.injector });
      },
    });
  }

  /** Cierra el pop-up del resultado (T3): el foco vuelve al botón que genera la vista previa. */
  protected cerrarResultado(): void {
    this.resultado.set(null);
    afterNextRender(() => this.generarEl()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Cierra la confirmación y el foco vuelve al botón que la abrió (reaparece en el próximo render). */
  protected cancelarConfirmacion(): void {
    this.confirmando.set(null);
    afterNextRender(() => this.confirmarEl()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Deja sin efecto la vista previa y lo que dependía de ella: solo se puede confirmar lo que se ve. */
  private invalidar(): void {
    this.version++;
    this.generando.set(false);
    this.descartarVistaPrevia();
    this.limpiarMensajes();
  }

  private descartarVistaPrevia(): void {
    this.vistaPrevia.set(null);
    this.pedidoVigente.set(null);
    this.confirmando.set(null);
  }

  /** Carga (o recarga) los dos selectores; un proveedor o rubro elegido que ya no existe vuelve a "Todos". */
  private cargarSelectores(): void {
    this.proveedoresService.listar().subscribe({
      next: (lista) => {
        this.proveedores.set(lista);
        soltarSiNoExiste(this.form.controls.proveedorId, lista);
        this.sincronizarFormulario();
      },
      error: () => this.avisar('No se pudieron cargar los proveedores.'),
    });
    this.rubrosService.listar().subscribe({
      next: (lista) => {
        this.rubros.set(lista);
        soltarSiNoExiste(this.form.controls.rubroId, lista);
        soltarSiNoExiste(this.form.controls.rubroDestinoId, lista);
        this.sincronizarFormulario();
      },
      error: () => this.avisar('No se pudieron cargar los rubros.'),
    });
    this.importacionesService.listar().subscribe({
      next: (lista) => {
        this.importacionesFallo.set(false);
        this.importaciones.set(lista);
        soltarSiNoExiste(this.form.controls.importacionId, lista);
        this.sincronizarFormulario();
      },
      error: () => {
        this.importacionesFallo.set(true);
        this.avisar('No se pudieron cargar las importaciones.');
      },
    });
  }

  private avisar(mensaje: string): void {
    this.erroresCampos.update((lista) => [...lista, mensaje]);
  }

  private limpiarMensajes(): void {
    this.error.set(null);
    this.erroresCampos.set([]);
    this.resultado.set(null);
  }

  private mostrarError(err: unknown): void {
    this.erroresCampos.set(erroresDeCampos(err));
    this.error.set(mensajeDelError(err));
    // Un proveedor o rubro que no se encuentra suele ser uno que se borró: se refrescan las listas.
    if (err instanceof HttpErrorResponse && err.status === 404) this.cargarSelectores();
  }

  /**
   * El pedido tal como lo escribió el Dueño; null si falta el modo o el valor no es un número. Con «Cambiar el rubro»
   * va el destino y no el valor (aunque haya quedado uno escrito de otro modo); null si falta el destino.
   */
  private armarPedido(): PedidoMasivo | null {
    const f = this.form.getRawValue();
    if (f.modo === null) return null;
    const filtro = {
      proveedorId: f.proveedorId,
      rubroId: f.rubroId,
      importacionId: f.importacionId,
      q: f.q.trim() === '' ? null : f.q.trim(),
      incluirInactivos: f.incluirInactivos,
    };
    if (f.modo === 'CAMBIAR_RUBRO') {
      const rubroDestinoId = f.rubroDestinoId;
      return rubroDestinoId === null ? null : { ...filtro, modo: f.modo, rubroDestinoId };
    }
    const valor = f.valor;
    if (valor === null) return null;
    return { ...filtro, modo: f.modo, valor };
  }
}

/** Sin proveedor, rubro, importación ni texto: el cambio alcanza a todo el catálogo ("dados de baja" no cuenta). */
function filtroVacio(pedido: PedidoMasivo): boolean {
  return (
    pedido.proveedorId === null &&
    pedido.rubroId === null &&
    (pedido.importacionId ?? null) === null &&
    pedido.q === null
  );
}

/** Sin `emitEvent`: soltar la selección no debe descartar la vista previa ni borrar el mensaje de error. */
function soltarSiNoExiste(control: FormControl<number | null>, lista: { id: number }[]): void {
  if (control.value !== null && !lista.some((x) => x.id === control.value)) {
    control.setValue(null, { emitEvent: false });
  }
}

/**
 * El mensaje del servidor si el error es de los que el Dueño puede corregir (400, 403, 404, 409); el 409
 * tiene su texto propio si no vino ninguno. Cualquier otro error (500, sin conexión) lleva el genérico.
 */
function mensajeDelError(err: unknown): string {
  if (!(err instanceof HttpErrorResponse) || !ESTADOS_CON_MENSAJE.includes(err.status)) {
    return MENSAJE_GENERICO;
  }
  return mensajeDe(err, err.status === 409 ? MENSAJE_CONFLICTO : MENSAJE_GENERICO);
}
