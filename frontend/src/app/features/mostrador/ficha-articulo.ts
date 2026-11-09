import { MontoPipe } from '../../core/formato/monto';
import { Component, input } from '@angular/core';
import { formatearFecha } from '../../core/formato/fecha-hora';
import { cantidadConUnidad, porUnidad } from '../../core/formato/unidad';
import { ArticuloMostrador, estadoStock } from './mostrador.models';

/** Ficha de un artículo para el mostrador: precio, stock y ubicación de un vistazo. */
@Component({
  selector: 'app-ficha-articulo',
  imports: [MontoPipe],
  templateUrl: './ficha-articulo.html',
  styleUrl: './ficha-articulo.css',
})
export class FichaArticulo {
  readonly articulo = input.required<ArticuloMostrador>();

  protected readonly cantidadConUnidad = cantidadConUnidad;
  protected readonly porUnidad = porUnidad;
  protected readonly estadoStock = estadoStock;
  protected readonly formatearFecha = formatearFecha;
}
