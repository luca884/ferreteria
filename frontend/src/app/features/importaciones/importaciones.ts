import { ScrollingModule } from '@angular/cdk/scrolling';
import { DecimalPipe } from '@angular/common';
import { MontoPipe } from '../../core/formato/monto';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { erroresDeCampos } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { Proveedor } from '../proveedores/proveedor.models';
import { ProveedoresService } from '../proveedores/proveedores.service';
import { Rubro } from '../rubros/rubro.models';
import { RubrosService } from '../rubros/rubros.service';
import {
  CambioPlaneado,
  Conteos,
  Importacion,
  Muestra,
  VistaPrevia,
  VistaPreviaRequest,
} from './importacion.models';
import { ImportacionesService } from './importaciones.service';
import { filaDeEncabezado, primeraFilaDeDatos } from './encabezado-lista';
import { SelectorArchivo } from './selector-archivo';

/** El backend rechaza más de 10 MB; se controla antes de subir para no gastar el pedido. */
const MAX_BYTES = 10 * 1024 * 1024;

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

@Component({
  selector: 'app-importaciones',
  imports: [ReactiveFormsModule, DecimalPipe, MontoPipe, ScrollingModule, SelectorArchivo],
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
  protected readonly muestra = signal<Muestra | null>(null);
  protected readonly hoja = signal(0);
  protected readonly cargandoMuestra = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Mensajes por campo de un 400 de validación del backend; si hay, se muestran en vez de `error`. */
  protected readonly erroresCampos = signal<string[]>([]);

  protected readonly vistaPrevia = signal<VistaPrevia | null>(null);
  protected readonly generando = signal(false);
  protected readonly aplicando = signal(false);
  protected readonly resumen = signal<Importacion | null>(null);
  /** El backend detectó que la lista cambió desde la vista previa (409): hay que volver a generarla. */
  protected readonly conflicto = signal(false);
  protected readonly rubros = signal<Rubro[]>([]);
  protected readonly crearNuevos = signal(false);
  protected readonly rubroId = signal<number | null>(null);

  /** Sube con cada cambio de datos: una vista previa en vuelo de datos viejos se descarta. */
  private version = 0;

  private readonly proveedor = computed(
    () => this.proveedores().find((p) => p.id === this.proveedorId()) ?? null,
  );

  /** Una letra por columna de la grilla (A, B, C…), que es como las ve el Dueño en su planilla. */
  protected readonly columnas = computed(() =>
    Array.from({ length: this.muestra()?.columnas ?? 0 }, (_, i) => i),
  );

  /** Textos de la fila de encabezado detectada (vacío si la lista no tiene), para rotular las columnas. */
  private readonly textosDeEncabezado = computed(() => {
    const m = this.muestra();
    const numero = filaDeEncabezado(m);
    return m?.filas.find((f) => f.numero === numero)?.celdas ?? [];
  });

  /** Mapeo, IVA y bonificaciones: arrancan con lo guardado en el proveedor. */
  protected readonly form = new FormGroup({
    filaDesde: new FormControl<number | null>(1, [Validators.required, Validators.min(1)]),
    columnaCodigo: new FormControl<number | null>(null, [Validators.required]),
    columnaDescripcion: new FormControl<number | null>(null),
    columnaCosto: new FormControl<number | null>(null, [Validators.required]),
    listaIncluyeIva: new FormControl(false, { nonNullable: true }),
    bonificacion1: new FormControl<number | null>(null, [Validators.min(0), Validators.max(100)]),
    bonificacion2: new FormControl<number | null>(null, [Validators.min(0), Validators.max(100)]),
    bonificacion3: new FormControl<number | null>(null, [Validators.min(0), Validators.max(100)]),
  });

  /** El mapeo que se está armando, para resaltar en el visor las columnas elegidas y atenuar el encabezado. */
  private readonly mapeo = toSignal(this.form.valueChanges, {
    initialValue: this.form.getRawValue(),
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

  constructor() {
    // Cualquier cambio del mapeo, el IVA o las bonificaciones deja la vista previa vieja sin efecto.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.invalidar());
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

  protected tarjetasDe(conteos: Conteos): { etiqueta: string; valor: number }[] {
    return [
      { etiqueta: 'Suben', valor: conteos.suben },
      { etiqueta: 'Bajan', valor: conteos.bajan },
      { etiqueta: 'Nuevos', valor: conteos.nuevos },
      { etiqueta: 'Sin cambio', valor: conteos.sinCambio },
      { etiqueta: 'No están', valor: conteos.noEstan },
      { etiqueta: 'Con error', valor: conteos.conError },
    ];
  }

  /** Un cambio está en la lista porque el costo se movió: sube si el nuevo es mayor, baja si es menor. */
  protected tipoDeCambio(cambio: CambioPlaneado): 'Sube' | 'Baja' {
    return cambio.costoNuevo > cambio.costoAnterior ? 'Sube' : 'Baja';
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

  /** Clase de resaltado de una columna según el rol que le dio el Dueño en el mapeo. */
  protected claseDeColumna(indice: number): string {
    const mapeo = this.mapeo();
    if (mapeo.columnaCodigo === indice) return 'col-codigo';
    if (mapeo.columnaDescripcion === indice) return 'col-descripcion';
    if (mapeo.columnaCosto === indice) return 'col-costo';
    return '';
  }

  protected tituloDeColumna(indice: number): string | null {
    switch (this.claseDeColumna(indice)) {
      case 'col-codigo':
        return 'Columna del código';
      case 'col-descripcion':
        return 'Columna de la descripción';
      case 'col-costo':
        return 'Columna del costo';
      default:
        return null;
    }
  }

  protected porNumero(_indice: number, fila: { numero: number }): number {
    return fila.numero;
  }

  /** Las filas anteriores a la primera fila de datos (títulos, encabezados) se ven atenuadas. */
  protected esPrevia(numeroDeFila: number): boolean {
    const desde = this.mapeo().filaDesde;
    return desde !== null && desde !== undefined && numeroDeFila < desde;
  }

  protected elegirProveedor(valor: string): void {
    this.invalidar();
    this.proveedorId.set(valor === '' ? null : Number(valor));
    this.precargar();
    if (this.archivo() && this.hojaGuardada() !== this.hoja()) {
      this.cargarMuestra(this.hojaGuardada());
    }
  }

  protected elegirArchivo(archivo: File | null): void {
    this.invalidar();
    this.muestra.set(null);
    if (archivo === null) {
      this.archivo.set(null);
      return;
    }
    if (archivo.size > MAX_BYTES) {
      this.archivo.set(null);
      this.error.set('El archivo supera el máximo de 10 MB.');
      return;
    }
    this.archivo.set(archivo);
    this.cargarMuestra(this.hojaGuardada());
  }

  /** Vuelve al paso 1 sin nada cargado, para importar la lista de otro proveedor. */
  protected importarOtra(): void {
    this.invalidar();
    this.archivo.set(null);
    this.muestra.set(null);
    this.hoja.set(0);
    this.proveedorId.set(null);
    this.crearNuevos.set(false);
    this.rubroId.set(null);
    this.precargar();
  }

  /** Mueve la grilla a la hoja anterior (−1) o a la siguiente (+1); en los extremos no hace nada. */
  protected moverHoja(paso: -1 | 1): void {
    const destino = this.hoja() + paso;
    const cantidad = this.muestra()?.hojas.length ?? 0;
    if (this.cargandoMuestra() || destino < 0 || destino >= cantidad) return;
    this.invalidar();
    this.cargarMuestra(destino);
  }

  protected cambiarCrearNuevos(crear: boolean): void {
    this.crearNuevos.set(crear);
    if (!crear) this.rubroId.set(null);
  }

  protected elegirRubro(valor: string): void {
    this.rubroId.set(valor === '' ? null : Number(valor));
  }

  protected generarVistaPrevia(): void {
    const archivo = this.archivo();
    const datos = this.armarDatos();
    if (archivo === null || datos === null) return;
    this.limpiarMensajes();
    const version = this.version;
    this.generando.set(true);
    this.service.vistaPrevia(archivo, datos).subscribe({
      next: (vistaPrevia) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.vistaPrevia.set(vistaPrevia);
      },
      error: (err: unknown) => {
        if (version !== this.version) return;
        this.generando.set(false);
        this.mostrarError(err, 'No se pudo generar la vista previa.');
      },
    });
  }

  protected confirmar(): void {
    const archivo = this.archivo();
    const vistaPrevia = this.vistaPrevia();
    const datos = this.armarDatos();
    if (archivo === null || vistaPrevia === null || datos === null) return;
    const crearNuevos = this.crearNuevos();
    this.limpiarMensajes();
    this.aplicando.set(true);
    this.service
      .aplicar(archivo, {
        ...datos,
        crearNuevos,
        rubroId: crearNuevos ? this.rubroId() : null,
        conteosEsperados: vistaPrevia.conteos,
      })
      .subscribe({
        next: (importacion) => {
          this.aplicando.set(false);
          this.vistaPrevia.set(null);
          this.resumen.set(importacion);
          // El proveedor guardó mapeo, IVA y bonificaciones: se recargan para precargar lo nuevo.
          this.proveedoresService.listar().subscribe({ next: (lista) => this.proveedores.set(lista) });
        },
        error: (err: unknown) => {
          this.aplicando.set(false);
          if (err instanceof HttpErrorResponse && err.status === 409) {
            this.vistaPrevia.set(null);
            this.conflicto.set(true);
          }
          this.mostrarError(err, 'No se pudo aplicar la importación.');
        },
      });
  }

  /** Deja sin efecto la vista previa y lo que dependía de ella. */
  private invalidar(): void {
    this.version++;
    this.generando.set(false);
    this.vistaPrevia.set(null);
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
    const f = this.form.controls;
    if (f.columnaCodigo.invalid || f.columnaCosto.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Elegí las columnas del código y del costo.');
      return null;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Revisá los datos marcados en rojo.');
      return null;
    }
    const v = this.form.getRawValue();
    return {
      proveedorId,
      mapeo: {
        hoja: this.hoja(),
        filaDesde: v.filaDesde!,
        columnaCodigo: v.columnaCodigo!,
        columnaDescripcion: v.columnaDescripcion,
        columnaCosto: v.columnaCosto!,
      },
      listaIncluyeIva: v.listaIncluyeIva,
      bonificacion1: v.bonificacion1,
      bonificacion2: v.bonificacion2,
      bonificacion3: v.bonificacion3,
    };
  }

  /** Hoja que usa el mapeo guardado del proveedor elegido; la primera si no tiene. */
  private hojaGuardada(): number {
    return this.proveedor()?.mapeoLista?.hoja ?? 0;
  }

  private precargar(): void {
    const proveedor = this.proveedor();
    const mapeo = proveedor?.mapeoLista;
    this.form.reset({
      filaDesde: mapeo?.filaDesde ?? primeraFilaDeDatos(this.muestra()),
      columnaCodigo: mapeo?.columnaCodigo ?? null,
      columnaDescripcion: mapeo?.columnaDescripcion ?? null,
      columnaCosto: mapeo?.columnaCosto ?? null,
      listaIncluyeIva: proveedor?.listaIncluyeIva ?? false,
      bonificacion1: proveedor?.bonificacion1 ?? null,
      bonificacion2: proveedor?.bonificacion2 ?? null,
      bonificacion3: proveedor?.bonificacion3 ?? null,
    });
  }

  private cargarMuestra(hoja: number): void {
    const archivo = this.archivo();
    if (archivo === null) return;
    this.limpiarMensajes();
    this.cargandoMuestra.set(true);
    this.service.muestra(archivo, hoja).subscribe({
      next: (muestra) => {
        if (this.archivo() !== archivo) return;
        this.muestra.set(muestra);
        this.hoja.set(muestra.hoja);
        // Sin mapeo guardado se propone la fila siguiente al encabezado; con mapeo guardado se respeta.
        if (!this.proveedor()?.mapeoLista) {
          this.form.controls.filaDesde.setValue(primeraFilaDeDatos(muestra));
        }
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
