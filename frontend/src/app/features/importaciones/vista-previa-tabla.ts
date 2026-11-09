import { DecimalPipe } from '@angular/common';
import { Component, computed, input, output, signal } from '@angular/core';
import { formatearCosto } from '../../core/formato/monto';
import { porUnidad } from '../../core/formato/unidad';
import { detalleDeFila, etiquetaDeTipo } from './detalle-fila';
import {
  FilaDeVista,
  FiltroDeFilas,
  Seleccion,
  TIPOS_ELEGIBLES,
  TipoFila,
  esElegible,
  filtrarFilas,
  marcarFila,
  marcarFiltradas,
  marcarNinguna,
  marcarTodas,
} from './seleccion-filas';

/** Cuántas filas se dibujan por página: el detalle completo viene del servidor y se pagina en el navegador. */
export const FILAS_POR_PAGINA = 100;

const TIPOS: readonly TipoFila[] = [...TIPOS_ELEGIBLES, 'conError'];

const PLURALES: Record<TipoFila, string> = {
  suben: 'Suben',
  bajan: 'Bajan',
  cambianMoneda: 'Cambian de moneda',
  corrigen: 'Corrigen IVA o ajustes',
  nuevos: 'Nuevos',
  sinCambio: 'Sin cambio',
  conError: 'Con error',
};

const SIN_DATO = '—';

/**
 * Las filas de la vista previa para revisarlas y elegirlas (presentacional): filtros por hoja y por tipo, páginas de
 * 100 y una casilla por fila. Las filas con error no tienen casilla. No guarda la selección: recibe la actual y emite
 * la nueva cada vez que cambia.
 */
@Component({
  selector: 'app-vista-previa-tabla',
  imports: [DecimalPipe],
  templateUrl: './vista-previa-tabla.html',
  styleUrl: './vista-previa-tabla.css',
})
export class VistaPreviaTabla {
  readonly filas = input.required<FilaDeVista[]>();
  /** Los nombres de las hojas del archivo, por su índice. */
  readonly hojas = input.required<string[]>();
  readonly seleccion = input.required<Seleccion>();
  readonly seleccionCambia = output<Seleccion>();

  protected readonly hojaFiltrada = signal<number | null>(null);
  protected readonly tipoFiltrado = signal<TipoFila | null>(null);
  private readonly paginaPedida = signal(0);

  protected readonly tipos = TIPOS;
  protected readonly variasHojas = computed(() => this.hojas().length > 1);

  protected readonly filtro = computed<FiltroDeFilas>(() => {
    const hoja = this.hojaFiltrada();
    const tipo = this.tipoFiltrado();
    return {
      ...(hoja === null ? {} : { hoja }),
      ...(tipo === null ? {} : { tipo }),
    };
  });
  protected readonly hayFiltro = computed(() => this.hojaFiltrada() !== null || this.tipoFiltrado() !== null);
  protected readonly filtradas = computed(() => filtrarFilas(this.filas(), this.filtro()));

  protected readonly totalPaginas = computed(() =>
    Math.max(1, Math.ceil(this.filtradas().length / FILAS_POR_PAGINA)),
  );
  /** La página pedida, sin pasarse si el filtro o las filas cambiaron y hay menos páginas. */
  protected readonly pagina = computed(() => Math.min(this.paginaPedida(), this.totalPaginas() - 1));
  protected readonly visibles = computed(() =>
    this.filtradas().slice(this.pagina() * FILAS_POR_PAGINA, (this.pagina() + 1) * FILAS_POR_PAGINA),
  );
  protected readonly desde = computed(() => this.pagina() * FILAS_POR_PAGINA + 1);
  protected readonly hasta = computed(() => this.pagina() * FILAS_POR_PAGINA + this.visibles().length);

  /** Cuántas filas de cada tipo tiene el archivo entero (no la página, ni el filtro). */
  protected readonly cantidadPorTipo = computed(() => {
    const cantidades = Object.fromEntries(TIPOS.map((t) => [t, 0])) as Record<TipoFila, number>;
    for (const fila of this.filas()) cantidades[fila.tipo]++;
    return cantidades;
  });
  protected readonly elegibles = computed(() => this.filas().filter(esElegible).length);
  protected readonly elegidas = computed(() => {
    const seleccion = this.seleccion();
    return this.filas().filter((f) => esElegible(f) && seleccion.has(f.clave)).length;
  });
  protected readonly cantidadDelFiltro = computed(() => this.filtradas().filter(esElegible).length);

  protected nombreDeTipo(tipo: TipoFila): string {
    return PLURALES[tipo];
  }

  protected etiqueta(tipo: TipoFila): string {
    return etiquetaDeTipo(tipo);
  }

  protected nombreDeHoja(hoja: number): string {
    return this.hojas()[hoja] ?? `Hoja ${hoja + 1}`;
  }

  protected esElegible(fila: FilaDeVista): boolean {
    return esElegible(fila);
  }

  protected estaMarcada(fila: FilaDeVista): boolean {
    return this.seleccion().has(fila.clave);
  }

  protected etiquetaDeCasilla(fila: FilaDeVista): string {
    return this.variasHojas()
      ? `Elegir la fila ${fila.fila} de ${this.nombreDeHoja(fila.hoja)}`
      : `Elegir la fila ${fila.fila}`;
  }

  protected detalle(fila: FilaDeVista): string[] {
    return detalleDeFila(fila);
  }

  protected costoAnterior(fila: FilaDeVista): string {
    const c = fila.cambio;
    return c ? formatearCosto(c.costoAnterior, c.monedaAnterior) + porUnidad(c.unidad) : SIN_DATO;
  }

  protected costoNuevo(fila: FilaDeVista): string {
    if (fila.cambio) return formatearCosto(fila.cambio.costoNuevo, fila.cambio.monedaNueva) + porUnidad(fila.cambio.unidad);
    if (fila.alta) return formatearCosto(fila.alta.costo, fila.alta.moneda) + porUnidad(fila.alta.unidad);
    return SIN_DATO;
  }

  protected precioAnterior(fila: FilaDeVista): string {
    return fila.cambio ? formatearCosto(fila.cambio.precioAnterior, 'ARS') + porUnidad(fila.cambio.unidad) : SIN_DATO;
  }

  protected precioNuevo(fila: FilaDeVista): string {
    if (fila.cambio) return formatearCosto(fila.cambio.precioNuevo, 'ARS') + porUnidad(fila.cambio.unidad);
    if (fila.alta?.precioNuevo !== null && fila.alta?.precioNuevo !== undefined) {
      return formatearCosto(fila.alta.precioNuevo, 'ARS') + porUnidad(fila.alta.unidad);
    }
    return SIN_DATO;
  }

  protected filtrarPorHoja(valor: string): void {
    this.hojaFiltrada.set(valor === '' ? null : Number(valor));
    this.paginaPedida.set(0);
  }

  protected filtrarPorTipo(valor: string): void {
    this.tipoFiltrado.set(valor === '' ? null : (valor as TipoFila));
    this.paginaPedida.set(0);
  }

  protected anterior(): void {
    this.paginaPedida.set(Math.max(0, this.pagina() - 1));
  }

  protected siguiente(): void {
    this.paginaPedida.set(Math.min(this.totalPaginas() - 1, this.pagina() + 1));
  }

  protected alternar(fila: FilaDeVista, marcada: boolean): void {
    this.seleccionCambia.emit(marcarFila(this.seleccion(), fila, marcada));
  }

  protected marcarTodas(): void {
    this.seleccionCambia.emit(marcarTodas(this.filas()));
  }

  protected desmarcarTodas(): void {
    this.seleccionCambia.emit(marcarNinguna());
  }

  protected marcarElFiltro(marcada: boolean): void {
    this.seleccionCambia.emit(marcarFiltradas(this.seleccion(), this.filas(), this.filtro(), marcada));
  }
}
