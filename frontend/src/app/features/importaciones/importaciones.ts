import { ScrollingModule } from '@angular/cdk/scrolling';
import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  OnInit,
  afterRenderEffect,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { map } from 'rxjs';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { BarraAcciones } from '../../shared/barra-acciones';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import { AvisoDeFila, Conteos, Importacion, Muestra, PropuestaDeMapeo, VistaPrevia, VistaPreviaRequest } from './importacion.models';
import { ImportacionesService } from './importaciones.service';
import { HojasLista } from './hojas-lista';
import { MapeoColumnas, OpcionColumna } from './mapeo-columnas';
import {
  CAMPOS_DE_COLUMNA,
  TITULO_DE_COLUMNA,
  armarMapeo,
  camposReconocidos,
  crearFormMapeo,
  mapeoVacio,
  precargarMapeo,
} from './mapeo-lista';
import { SelectorArchivo } from './selector-archivo';
import {
  FilaDeVista,
  Seleccion,
  cantidadDeAplicadas,
  conservarSeleccion,
  conteosConSeleccion,
  filasElegidas,
  marcarNinguna,
  nuevasElegidas,
  seleccionInicial,
  unirFilas,
} from './seleccion-filas';
import { FILAS_POR_PAGINA, VistaPreviaTabla } from './vista-previa-tabla';

/** El backend rechaza más de 25 MB; se controla antes de subir para no gastar el pedido. */
const MAX_BYTES = 25 * 1024 * 1024;
const EXTENSIONES = ['.xlsx', '.xls', '.csv', '.pdf'];
/** Las advertencias se muestran de a tandas; «Ver más» suma otra. */
const ADVERTENCIAS_POR_TANDA = 100;

/**
 * Medidas del visor al 100 %. Son múltiplos de 10 / 3 para que, con el zoom en pasos de 10 %, la altura
 * de fila (el `itemSize` del scroll virtual) sea siempre un entero de píxeles y no se acumule deriva.
 */
const ALTO_FILA_BASE = 30;
const ANCHO_COLUMNA_BASE = 120;
const ANCHO_NUMERO_BASE = 60;
const FUENTE_BASE = 14;
/** El marco muestra ~15 filas al 100 %; no cambia con el zoom (como en Excel, el zoom cambia cuántas se ven). */
const FILAS_VISIBLES = 15;
/** Espacio extra del encabezado para absorber la barra de scroll vertical del marco. */
const HOLGURA_ENCABEZADO = 24;

const ZOOM_MINIMO = 50;
const ZOOM_MAXIMO = 200;
const ZOOM_PASO = 10;
const ZOOM_INICIAL = 100;

/** Los campos con validación, en el orden de la pantalla: al fallar se enfoca el primero con error. */
const CAMPOS_EN_ORDEN = [
  ['mapeo', 'filaDesde'],
  ['mapeo', 'columnaCodigo'],
  ['mapeo', 'columnaCosto'],
  ['lista', 'bonificacion1'],
  ['lista', 'bonificacion2'],
  ['lista', 'bonificacion3'],
] as const;

const TARJETAS: { etiqueta: string; clave: keyof Conteos }[] = [
  { etiqueta: 'Suben', clave: 'suben' },
  { etiqueta: 'Bajan', clave: 'bajan' },
  { etiqueta: 'Cambian de moneda', clave: 'cambianMoneda' },
  { etiqueta: 'Corrigen IVA o ajustes', clave: 'corrigen' },
  { etiqueta: 'Nuevos', clave: 'nuevos' },
  { etiqueta: 'Sin cambio', clave: 'sinCambio' },
  { etiqueta: 'No están', clave: 'noEstan' },
  { etiqueta: 'Con error', clave: 'conError' },
  { etiqueta: 'Salteadas', clave: 'salteadas' },
];

const rango = (cantidad: number) => Array.from({ length: cantidad }, (_, i) => i);

@Component({
  selector: 'app-importaciones',
  imports: [
    ReactiveFormsModule,
    DecimalPipe,
    ScrollingModule,
    SelectorArchivo,
    BarraAcciones,
    MapeoColumnas,
    HojasLista,
    VistaPreviaTabla,
  ],
  templateUrl: './importaciones.html',
  styleUrl: './importaciones.css',
})
export class Importaciones implements OnInit {
  private readonly service = inject(ImportacionesService);
  private readonly proveedoresService = inject(ProveedoresService);
  private readonly rubrosService = inject(RubrosService);
  protected readonly auth = inject(AuthService);

  protected readonly proveedores = signal<Proveedor[]>([]);
  protected readonly proveedorId = signal<number | null>(null);
  protected readonly archivo = signal<File | null>(null);
  /** El archivo entero: todas las hojas con todas sus filas, y la propuesta de mapeo. Se pide una sola vez. */
  protected readonly muestra = signal<Muestra | null>(null);
  /** La hoja que se ve en la grilla. Cambiarla no toca ningún dato ni vuelve a llamar al servicio. */
  protected readonly hojaVista = signal(0);
  protected readonly cargandoMuestra = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);

  /** Hoja de la que salen el encabezado y la primera fila de datos (el mapeo es uno solo para todas). */
  private readonly hojaDeReferencia = signal(0);
  /** Las hojas que se importan. */
  protected readonly hojasElegidas = signal<number[]>([0]);
  /** La propuesta del servidor, mientras el mapeo que se muestra viene de ella (y no del guardado del proveedor). */
  private readonly propuestaVigente = signal<PropuestaDeMapeo | null>(null);

  protected readonly vistaPrevia = signal<VistaPrevia | null>(null);
  protected readonly seleccion = signal<Seleccion>(marcarNinguna());
  protected readonly generando = signal(false);
  protected readonly aplicando = signal(false);
  protected readonly resumen = signal<Importacion | null>(null);
  /** El backend detectó que la lista cambió desde la vista previa (409) y no se pudo pedir la nueva. */
  protected readonly conflicto = signal(false);
  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly crearNuevos = signal(false);
  /** Cuántas filas elegidas antes de un 409 ya no están en la vista previa nueva; 0 si no hay nada que avisar. */
  protected readonly filasPerdidas = signal(0);
  protected readonly advertenciasMostradas = signal(ADVERTENCIAS_POR_TANDA);
  protected readonly noEstanAbierto = signal(false);
  private readonly paginaNoEstanPedida = signal(0);

  /** La selección y las filas que había cuando el backend respondió 409: la vista previa nueva parte de ahí. */
  private porConservar: { seleccion: Seleccion; filas: FilaDeVista[] } | null = null;

  /** Sube con cada cambio de datos: una vista previa en vuelo de datos viejos se descarta. */
  private version = 0;

  private readonly proveedor = computed(
    () => this.proveedores().find((p) => p.id === this.proveedorId()) ?? null,
  );

  /** Mapeo de columnas: la primera fila de datos, qué columna es cada dato y la moneda fija. */
  protected readonly mapeoForm = crearFormMapeo();

  /** Lista del proveedor: IVA, bonificaciones y el rubro de los artículos nuevos (define su precio en la vista previa). */
  protected readonly listaForm = new FormGroup({
    listaIncluyeIva: new FormControl(false, { nonNullable: true }),
    bonificacion1: new FormControl<number | null>(null, [Validators.min(0), Validators.max(100)]),
    bonificacion2: new FormControl<number | null>(null, [Validators.min(0), Validators.max(100)]),
    bonificacion3: new FormControl<number | null>(null, [Validators.min(0), Validators.max(100)]),
    rubroId: new FormControl<number | null>(null),
  });

  /** El mapeo que se está armando, para resaltar en la grilla las columnas elegidas y atenuar el encabezado. */
  private readonly valoresMapeo = toSignal(
    this.mapeoForm.valueChanges.pipe(map(() => this.mapeoForm.getRawValue())),
    { initialValue: this.mapeoForm.getRawValue() },
  );

  protected readonly reconocidos = computed(() =>
    camposReconocidos(this.propuestaVigente(), this.valoresMapeo()),
  );

  protected readonly hojaActual = computed(() => this.muestra()?.hojas[this.hojaVista()] ?? null);
  protected readonly nombresDeHojas = computed(() => this.muestra()?.hojas.map((h) => h.nombre) ?? []);
  protected readonly hojasParaLista = computed(
    () => this.muestra()?.hojas.map((h) => ({ indice: h.indice, nombre: h.nombre })) ?? [],
  );

  /** La hoja de referencia que se va a mandar: la guardada si está entre las elegidas; si no, la primera elegida. */
  private readonly referenciaEfectiva = computed(() => {
    const elegidas = [...this.hojasElegidas()].sort((a, b) => a - b);
    return elegidas.includes(this.hojaDeReferencia()) ? this.hojaDeReferencia() : (elegidas[0] ?? this.hojaDeReferencia());
  });
  protected readonly nombreDeReferencia = computed(
    () => this.muestra()?.hojas[this.referenciaEfectiva()]?.nombre ?? '',
  );

  /** Una letra por columna de la grilla (A, B, C…), que es como las ve el Dueño en su planilla. */
  protected readonly columnas = computed(() => rango(this.hojaActual()?.columnas ?? 0));

  /** Textos de la fila de encabezado que reconoció el servidor en la hoja de referencia, para rotular las columnas. */
  private readonly textosDeEncabezado = computed(() => {
    const hoja = this.muestra()?.hojas[this.referenciaEfectiva()];
    if (!hoja || hoja.filaEncabezado === null) return [];
    return hoja.filas.find((f) => f.numero === hoja.filaEncabezado)?.celdas ?? [];
  });

  /** Las columnas que se pueden elegir: las de la hoja más ancha, rotuladas con su encabezado. */
  protected readonly opciones = computed<OpcionColumna[]>(() => {
    const ancho = Math.max(0, ...(this.muestra()?.hojas.map((h) => h.columnas) ?? []));
    return rango(ancho).map((indice) => ({ indice, etiqueta: this.etiquetaColumna(indice) }));
  });

  protected readonly filasDeVista = computed(() => {
    const vp = this.vistaPrevia();
    return vp ? unirFilas(vp) : [];
  });
  /** Los contadores de lo elegido y los del archivo (C-109): alimentan las tarjetas y lo que se confirma al aplicar. */
  protected readonly conteosElegidos = computed(() => {
    const vp = this.vistaPrevia();
    return vp ? conteosConSeleccion(vp.conteos, this.filasDeVista(), this.seleccion()) : null;
  });
  /** Como el backend: con «Crear los artículos nuevos» apagado, las filas nuevas elegidas no se aplican. */
  protected readonly sinFilasElegidas = computed(
    () => cantidadDeAplicadas(this.seleccion(), this.filasDeVista(), this.crearNuevos()) === 0,
  );
  /** Las nuevas elegidas que no se van a crear porque la casilla está apagada. */
  protected readonly nuevosSinCrear = computed(() =>
    this.crearNuevos() ? 0 : nuevasElegidas(this.seleccion(), this.filasDeVista()),
  );
  /** Las nuevas que quedaron sin crear en la importación aplicada: el resultado lo avisa. */
  protected readonly nuevosNoCreados = signal(0);
  protected readonly advertenciasVisibles = computed(
    () => this.vistaPrevia()?.advertencias.slice(0, this.advertenciasMostradas()) ?? [],
  );
  protected readonly totalPaginasNoEstan = computed(() =>
    Math.max(1, Math.ceil((this.vistaPrevia()?.noEstan.length ?? 0) / FILAS_POR_PAGINA)),
  );
  protected readonly paginaNoEstan = computed(() =>
    Math.min(this.paginaNoEstanPedida(), this.totalPaginasNoEstan() - 1),
  );
  /** Solo se arman las filas de la página y solo con el bloque abierto. */
  protected readonly noEstanVisibles = computed(() => {
    if (!this.noEstanAbierto()) return [];
    const desde = this.paginaNoEstan() * FILAS_POR_PAGINA;
    return this.vistaPrevia()?.noEstan.slice(desde, desde + FILAS_POR_PAGINA) ?? [];
  });
  protected readonly desdeNoEstan = computed(() => this.paginaNoEstan() * FILAS_POR_PAGINA + 1);
  protected readonly hastaNoEstan = computed(
    () => this.paginaNoEstan() * FILAS_POR_PAGINA + this.noEstanVisibles().length,
  );
  protected readonly avisoDePerdidas = computed(() => {
    const n = this.filasPerdidas();
    if (n === 0) return null;
    return n === 1
      ? '1 fila que habías elegido ya no está en la lista nueva.'
      : `${n} filas que habías elegido ya no están en la lista nueva.`;
  });
  protected readonly tarjetasDeLaVistaPrevia = computed(() => {
    const vp = this.vistaPrevia();
    const elegidos = this.conteosElegidos();
    return vp && elegidos ? this.tarjetasDe(elegidos, vp.conteos) : [];
  });

  /** Zoom del visor en porcentaje (50–200, de a 10). Escala la fuente, el alto de fila y el ancho de columna. */
  protected readonly zoom = signal(ZOOM_INICIAL);
  protected readonly zoomEnMinimo = computed(() => this.zoom() <= ZOOM_MINIMO);
  protected readonly zoomEnMaximo = computed(() => this.zoom() >= ZOOM_MAXIMO);
  /** Alto de fila × zoom; es el `itemSize` del scroll virtual, así que lo que se mide y lo que se dibuja coinciden. */
  protected readonly altoFila = computed(() => (ALTO_FILA_BASE * this.zoom()) / 100);
  protected readonly anchoColumna = computed(() => (ANCHO_COLUMNA_BASE * this.zoom()) / 100);
  protected readonly anchoNumero = computed(() => (ANCHO_NUMERO_BASE * this.zoom()) / 100);
  protected readonly fuente = computed(() => (FUENTE_BASE * this.zoom()) / 100);
  /** Fijo: el marco muestra ~15 filas al 100 % y, al acercar, se ven menos (como en una planilla). */
  protected readonly altoMarco = FILAS_VISIBLES * ALTO_FILA_BASE;
  protected readonly anchoTotal = computed(
    () => this.anchoNumero() + this.columnas().length * this.anchoColumna() + HOLGURA_ENCABEZADO,
  );

  private readonly encabezado = viewChild<ElementRef<HTMLElement>>('encabezadoVisor');
  private readonly resumenEl = viewChild<ElementRef<HTMLElement>>('resumenEl');
  private readonly cuerpoVisor = viewChild('cuerpoVisor', { read: ElementRef<HTMLElement> });
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    // El scroll virtual mete un div propio entre el rowgroup y las filas: se lo saca del árbol accesible.
    afterRenderEffect(() => {
      this.cuerpoVisor()
        ?.nativeElement.querySelector('.cdk-virtual-scroll-content-wrapper')
        ?.setAttribute('role', 'presentation');
    });
    // Cualquier cambio del mapeo, el IVA, las bonificaciones o el rubro deja la vista previa vieja sin efecto.
    this.mapeoForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.invalidar());
    this.listaForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.invalidar());
    // El resultado aparece donde está el Dueño: se lo lleva a la vista y se le da el foco.
    effect(() => {
      const el = this.resumenEl()?.nativeElement;
      if (!el) return;
      el.focus({ preventScroll: true });
      el.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
    });
  }

  ngOnInit(): void {
    if (!this.auth.esDueno()) return;
    this.proveedoresService.listar().subscribe({
      next: (lista) => this.proveedores.set(lista),
      error: () => this.error.set('No se pudieron cargar los proveedores.'),
    });
    this.rubrosService.listar().subscribe({
      next: (lista) => this.rubros.set(lista),
      error: () => this.error.set('No se pudieron cargar los rubros.'),
    });
  }

  /** Las tarjetas de contadores. Con `totales`, lo elegido aclara «de N» cuando es menos que lo que trae el archivo. */
  protected tarjetasDe(valores: Conteos, totales?: Conteos): { etiqueta: string; valor: number; total: number | null }[] {
    return TARJETAS.map(({ etiqueta, clave }) => ({
      etiqueta,
      valor: valores[clave],
      total: totales && totales[clave] !== valores[clave] ? totales[clave] : null,
    }));
  }

  /** «Hoja «Lista», fila 3 (A-2): mensaje»; las advertencias generales llevan solo la hoja, o nada. */
  protected prefijoDeAviso(aviso: AvisoDeFila): string | null {
    const partes: string[] = [];
    if (aviso.hoja !== null && aviso.hoja !== undefined) {
      partes.push(`Hoja «${this.muestra()?.hojas[aviso.hoja]?.nombre ?? aviso.hoja + 1}»`);
    }
    if (aviso.fila !== null) {
      const fila = `fila ${aviso.fila}${aviso.codigo ? ` (${aviso.codigo})` : ''}`;
      partes.push(partes.length === 0 ? fila.charAt(0).toUpperCase() + fila.slice(1) : fila);
    }
    return partes.length > 0 ? partes.join(', ') : null;
  }

  /** «A · Código»: la letra de la planilla y el texto del encabezado de esa columna, si lo hay. */
  protected etiquetaColumna(indice: number): string {
    const texto = (this.textosDeEncabezado()[indice] ?? '').trim();
    if (texto === '') return this.letra(indice);
    return `${this.letra(indice)} · ${texto.length > 28 ? texto.slice(0, 27) + '…' : texto}`;
  }

  protected letra(indice: number): string {
    let resto = indice;
    let texto = '';
    do {
      texto = String.fromCharCode(65 + (resto % 26)) + texto;
      resto = Math.floor(resto / 26) - 1;
    } while (resto >= 0);
    return texto;
  }

  protected acercar(): void {
    this.zoom.update((z) => Math.min(ZOOM_MAXIMO, z + ZOOM_PASO));
  }

  protected alejar(): void {
    this.zoom.update((z) => Math.max(ZOOM_MINIMO, z - ZOOM_PASO));
  }

  protected restablecerZoom(): void {
    this.zoom.set(ZOOM_INICIAL);
  }

  /** El encabezado de letras está fuera del scroll vertical: acompaña al horizontal del marco. */
  protected sincronizarEncabezado(evento: Event): void {
    const encabezado = this.encabezado();
    if (encabezado) encabezado.nativeElement.scrollLeft = (evento.target as HTMLElement).scrollLeft;
  }

  /** El campo del mapeo que eligió esta columna, para resaltarla; el código, la descripción y el costo tienen su color. */
  private campoDeColumna(indice: number) {
    const mapeo = this.valoresMapeo();
    return CAMPOS_DE_COLUMNA.find((campo) => mapeo[campo] === indice) ?? null;
  }

  /** Clase de resaltado de una columna según el rol que le dio el Dueño en el mapeo. */
  protected claseDeColumna(indice: number): string {
    switch (this.campoDeColumna(indice)) {
      case null:
        return '';
      case 'columnaCodigo':
        return 'col-codigo';
      case 'columnaDescripcion':
        return 'col-descripcion';
      case 'columnaCosto':
        return 'col-costo';
      default:
        return 'col-otra';
    }
  }

  protected tituloDeColumna(indice: number): string | null {
    const campo = this.campoDeColumna(indice);
    return campo === null ? null : TITULO_DE_COLUMNA[campo];
  }

  protected porNumero(_indice: number, fila: { numero: number }): number {
    return fila.numero;
  }

  /**
   * Las filas anteriores a la primera fila de datos (títulos, encabezados) se ven atenuadas. En la hoja de referencia
   * vale la primera fila de datos del formulario; en las demás, el encabezado que reconoció el servidor.
   */
  protected esPrevia(numeroDeFila: number): boolean {
    const hoja = this.hojaActual();
    if (hoja === null) return false;
    if (hoja.indice === this.referenciaEfectiva()) {
      const desde = this.valoresMapeo().filaDesde;
      return desde !== null && desde !== undefined && numeroDeFila < desde;
    }
    return hoja.filaEncabezado !== null && numeroDeFila <= hoja.filaEncabezado;
  }

  protected elegirProveedor(valor: string): void {
    this.invalidar();
    this.proveedorId.set(valor === '' ? null : Number(valor));
    this.precargarLista();
    this.precargarMapeo();
  }

  protected elegirArchivo(archivo: File | null): void {
    this.invalidar();
    this.muestra.set(null);
    if (archivo === null) {
      this.archivo.set(null);
      return;
    }
    if (!EXTENSIONES.some((extension) => archivo.name.toLowerCase().endsWith(extension))) {
      this.archivo.set(null);
      this.error.set('El archivo tiene que ser una planilla de Excel (.xlsx o .xls), un CSV o un PDF.');
      return;
    }
    if (archivo.size > MAX_BYTES) {
      this.archivo.set(null);
      this.error.set('El archivo supera el máximo de 25 MB.');
      return;
    }
    this.archivo.set(archivo);
    this.cargarMuestra();
  }

  /** Vuelve al paso 1 sin nada cargado, para importar la lista de otro proveedor. */
  protected importarOtra(): void {
    this.invalidar();
    this.archivo.set(null);
    this.muestra.set(null);
    this.hojaVista.set(0);
    this.hojaDeReferencia.set(0);
    this.hojasElegidas.set([0]);
    this.propuestaVigente.set(null);
    this.proveedorId.set(null);
    this.crearNuevos.set(false);
    this.mapeoForm.reset(mapeoVacio());
    this.listaForm.reset({ listaIncluyeIva: false });
  }

  /** Mueve la grilla a la hoja anterior (−1) o a la siguiente (+1); en los extremos no hace nada. */
  protected moverHoja(paso: -1 | 1): void {
    const destino = this.hojaVista() + paso;
    const cantidad = this.muestra()?.hojas.length ?? 0;
    if (destino < 0 || destino >= cantidad) return;
    this.hojaVista.set(destino);
  }

  protected elegirHojaVista(valor: string): void {
    this.hojaVista.set(Number(valor));
  }

  protected elegirHojas(hojas: number[]): void {
    this.invalidar();
    this.hojasElegidas.set(hojas);
  }

  protected cambiarCrearNuevos(crear: boolean): void {
    this.crearNuevos.set(crear);
  }

  protected cambiarSeleccion(seleccion: Seleccion): void {
    this.seleccion.set(seleccion);
  }

  protected verMasAdvertencias(): void {
    this.advertenciasMostradas.update((n) => n + ADVERTENCIAS_POR_TANDA);
  }

  protected alternarNoEstan(abierto: boolean): void {
    this.noEstanAbierto.set(abierto);
  }

  protected anteriorNoEstan(): void {
    this.paginaNoEstanPedida.set(Math.max(0, this.paginaNoEstan() - 1));
  }

  protected siguienteNoEstan(): void {
    this.paginaNoEstanPedida.set(Math.min(this.totalPaginasNoEstan() - 1, this.paginaNoEstan() + 1));
  }

  protected generarVistaPrevia(): void {
    this.pedirVistaPrevia(false);
  }

  protected confirmar(): void {
    const archivo = this.archivo();
    const vistaPrevia = this.vistaPrevia();
    const conteosEsperados = this.conteosElegidos();
    const datos = this.armarDatos();
    if (archivo === null || vistaPrevia === null || conteosEsperados === null || datos === null) return;
    if (this.sinFilasElegidas()) return;
    const filas = filasElegidas(this.seleccion(), this.filasDeVista());
    const antes = { seleccion: this.seleccion(), filas: this.filasDeVista() };
    const nuevosSinCrear = this.nuevosSinCrear();
    this.limpiarMensajes();
    this.aplicando.set(true);
    this.service
      .aplicar(archivo, {
        ...datos,
        crearNuevos: this.crearNuevos(),
        conteosEsperados,
        filasElegidas: filas,
      })
      .subscribe({
        next: (importacion) => {
          this.aplicando.set(false);
          this.vistaPrevia.set(null);
          this.nuevosNoCreados.set(nuevosSinCrear);
          this.resumen.set(importacion);
          // El proveedor guardó mapeo, IVA y bonificaciones: se recargan para precargar lo nuevo.
          this.proveedoresService.listar().subscribe({ next: (lista) => this.proveedores.set(lista) });
        },
        error: (err: unknown) => {
          this.aplicando.set(false);
          if (err instanceof HttpErrorResponse && err.status === 409) {
            // La lista cambió: lo que se estaba viendo ya no vale. Se muestra el aviso y se pide la vista previa nueva.
            this.vistaPrevia.set(null);
            this.conflicto.set(true);
            this.porConservar = antes;
            this.mostrarError(err, 'No se pudo aplicar la importación.');
            this.pedirVistaPrevia(true);
            return;
          }
          this.mostrarError(err, 'No se pudo aplicar la importación.');
        },
      });
  }

  /** Pide la vista previa. Con `conservarMensaje` deja a la vista el aviso que motivó el pedido (el 409). */
  private pedirVistaPrevia(conservarMensaje: boolean): void {
    const archivo = this.archivo();
    const datos = this.armarDatos();
    if (archivo === null || datos === null) return;
    if (!conservarMensaje) this.limpiarMensajes();
    const version = this.version;
    this.generando.set(true);
    this.service.vistaPrevia(archivo, datos).subscribe({
      next: (vistaPrevia) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.conflicto.set(false);
        this.vistaPrevia.set(vistaPrevia);
        const filas = unirFilas(vistaPrevia);
        const previa = this.porConservar;
        this.porConservar = null;
        if (previa === null) {
          // Al abrir están elegidas todas las filas que se pueden aplicar.
          this.seleccion.set(seleccionInicial(filas));
          this.filasPerdidas.set(0);
        } else {
          // Después de un 409: lo que el Dueño había desmarcado sigue desmarcado y lo que aparece recién entra marcado.
          const conservada = conservarSeleccion(previa.seleccion, previa.filas, filas);
          this.seleccion.set(conservada.seleccion);
          this.filasPerdidas.set(conservada.perdidas);
        }
        this.advertenciasMostradas.set(ADVERTENCIAS_POR_TANDA);
        this.noEstanAbierto.set(false);
        this.paginaNoEstanPedida.set(0);
      },
      error: (err: unknown) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.mostrarError(err, 'No se pudo generar la vista previa.');
      },
    });
  }

  /** Deja sin efecto la vista previa y lo que dependía de ella. */
  private invalidar(): void {
    this.version++;
    this.generando.set(false);
    this.vistaPrevia.set(null);
    this.seleccion.set(marcarNinguna());
    this.porConservar = null;
    this.filasPerdidas.set(0);
    this.resumen.set(null);
    this.conflicto.set(false);
    this.limpiarMensajes();
  }

  private limpiarMensajes(): void {
    this.error.set(null);
    this.erroresCampos.set([]);
    this.conflicto.set(false);
    this.resumen.set(null);
  }

  private mostrarError(err: unknown, porDefecto: string): void {
    this.erroresCampos.set(erroresDeCampos(err));
    this.error.set(mensajeDe(err, porDefecto));
  }

  /** Lleva el foco al primer campo con error, en el orden de la pantalla. */
  private enfocarPrimerInvalido(): void {
    const primero = CAMPOS_EN_ORDEN.find(([formulario, campo]) =>
      formulario === 'mapeo' ? this.mapeoForm.controls[campo].invalid : this.listaForm.controls[campo].invalid,
    );
    if (primero) this.enfocar(primero[1]);
  }

  private enfocar(id: string): void {
    this.host.querySelector<HTMLElement>(`#${id}`)?.focus();
  }

  /** El pedido de la vista previa; null (con el aviso en pantalla) si falta o está mal algo. */
  private armarDatos(): VistaPreviaRequest | null {
    const proveedorId = this.proveedorId();
    if (this.archivo() === null || this.muestra() === null) {
      this.error.set('Elegí el archivo de la lista.');
      return null;
    }
    if (proveedorId === null) {
      this.error.set('Elegí el proveedor.');
      return null;
    }
    const f = this.mapeoForm.controls;
    if (f.columnaCodigo.invalid || f.columnaCosto.invalid) {
      this.mapeoForm.markAllAsTouched();
      this.error.set('Elegí las columnas del código y del costo.');
      this.enfocarPrimerInvalido();
      return null;
    }
    if (this.mapeoForm.hasError('ivaEnPar')) {
      this.mapeoForm.markAllAsTouched();
      this.error.set(
        'Elegí las dos columnas del IVA: el precio sin IVA y el precio con IVA, o ninguna de las dos.',
      );
      this.enfocar(f.columnaSinIva.value === null ? 'columnaSinIva' : 'columnaConIva');
      return null;
    }
    if (this.hojasElegidas().length === 0) {
      this.error.set('Elegí al menos una hoja.');
      return null;
    }
    if (this.mapeoForm.invalid || this.listaForm.invalid) {
      this.mapeoForm.markAllAsTouched();
      this.listaForm.markAllAsTouched();
      this.error.set('Revisá los datos marcados en rojo.');
      this.enfocarPrimerInvalido();
      return null;
    }
    const v = this.listaForm.getRawValue();
    return {
      proveedorId,
      mapeo: armarMapeo(this.mapeoForm.getRawValue(), this.referenciaEfectiva(), this.hojasElegidas()),
      listaIncluyeIva: v.listaIncluyeIva,
      bonificacion1: v.bonificacion1,
      bonificacion2: v.bonificacion2,
      bonificacion3: v.bonificacion3,
      rubroId: v.rubroId,
    };
  }

  /** IVA y bonificaciones guardados en el proveedor elegido (el rubro de los nuevos no es del proveedor: no se toca). */
  private precargarLista(): void {
    const proveedor = this.proveedor();
    this.listaForm.patchValue({
      listaIncluyeIva: proveedor?.listaIncluyeIva ?? false,
      bonificacion1: proveedor?.bonificacion1 ?? null,
      bonificacion2: proveedor?.bonificacion2 ?? null,
      bonificacion3: proveedor?.bonificacion3 ?? null,
    });
  }

  /** El mapeo guardado del proveedor si sirve para este archivo; si no, lo que reconoció el servidor; si no, vacío. */
  private precargarMapeo(): void {
    const muestra = this.muestra();
    if (muestra === null) return;
    const precarga = precargarMapeo(this.proveedor()?.mapeoLista, muestra);
    this.mapeoForm.reset(precarga.valores);
    this.hojaDeReferencia.set(precarga.hoja);
    this.hojasElegidas.set(precarga.hojas);
    this.hojaVista.set(Math.min(precarga.hoja, Math.max(0, muestra.hojas.length - 1)));
    this.propuestaVigente.set(precarga.desdePropuesta ? muestra.propuesta : null);
  }

  private cargarMuestra(): void {
    const archivo = this.archivo();
    if (archivo === null) return;
    this.limpiarMensajes();
    this.cargandoMuestra.set(true);
    this.service.muestra(archivo).subscribe({
      next: (muestra) => {
        if (this.archivo() !== archivo) return;
        this.muestra.set(muestra);
        this.precargarMapeo();
        this.cargandoMuestra.set(false);
      },
      error: (err: unknown) => {
        if (this.archivo() !== archivo) return;
        this.cargandoMuestra.set(false);
        this.error.set(mensajeDe(err, 'No se pudo leer la lista.'));
      },
    });
  }
}

function mensajeDe(err: unknown, porDefecto: string): string {
  return err instanceof HttpErrorResponse && err.error?.message ? err.error.message : porDefecto;
}
