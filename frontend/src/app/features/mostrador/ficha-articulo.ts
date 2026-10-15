import { DecimalPipe } from '@angular/common';
import { MontoPipe } from '../../core/formato/monto';
import { Component, input } from '@angular/core';
import { UNIDAD_LABELS } from '../articulos/articulo.models';
import { ArticuloMostrador, estadoStock } from './mostrador.models';

/** Ficha de un artículo para el mostrador: precio, stock y ubicación de un vistazo. */
@Component({
  selector: 'app-ficha-articulo',
  imports: [DecimalPipe, MontoPipe],
  templateUrl: './ficha-articulo.html',
  styleUrl: './ficha-articulo.css',
})
export class FichaArticulo {
  readonly articulo = input.required<ArticuloMostrador>();

  protected readonly unidadLabels = UNIDAD_LABELS;
  protected readonly estadoStock = estadoStock;
}
