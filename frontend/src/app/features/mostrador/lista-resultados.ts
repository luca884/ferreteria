import { MontoPipe } from '../../core/formato/monto';
import {
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  output,
  viewChildren,
} from '@angular/core';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { cantidadConUnidad } from '../../core/formato/unidad';
import { ArticuloMostrador, estadoStock } from './mostrador.models';

/** Ids que usa el campo de búsqueda para aria-controls y aria-activedescendant. */
export const LISTA_ID = 'resultados';
export const filaId = (indice: number) => `resultado-${indice}`;

/** Resultados de una búsqueda con varias coincidencias. El foco queda siempre en el campo. */
@Component({
  selector: 'app-lista-resultados',
  imports: [MontoPipe],
  templateUrl: './lista-resultados.html',
  styleUrl: './lista-resultados.css',
})
export class ListaResultados {
  readonly items = input.required<ArticuloMostrador[]>();
  readonly seleccionado = input(0);
  readonly hayMas = input(false);
  readonly elegir = output<number>();

  protected readonly listaId = LISTA_ID;
  protected readonly filaId = filaId;
  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly estadoStock = estadoStock;
  protected readonly formatearFecha = formatearFecha;

  private readonly filas = viewChildren<ElementRef<HTMLElement>>('fila');

  constructor() {
    afterRenderEffect(() => {
      this.filas()[this.seleccionado()]?.nativeElement.scrollIntoView({ block: 'nearest' });
    });
  }
}
