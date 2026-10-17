import { Unidad } from '../articulos/articulo.models';

export type TipoResultadoMostrador = 'EXACTO' | 'LISTA' | 'SIN_RESULTADOS';

/** Lo que ve el mostrador de un artículo: sin costo ni margen (el Vendedor no los ve). */
export interface ArticuloMostrador {
  id: number;
  codigoInterno: string;
  codigoBarras: string | null;
  codigoProveedor: string | null;
  descripcion: string;
  precioFinal: number;
  unidad: Unidad;
  stockActual: number;
  stockMinimo: number;
  ubicacion: string | null;
  /** Los dados de baja salen en la búsqueda, marcados, pero no se pueden agregar al ticket. */
  activo: boolean;
}

export interface BusquedaMostrador {
  tipo: TipoResultadoMostrador;
  items: ArticuloMostrador[];
  /** Hay más de 20 coincidencias: el backend devuelve solo las primeras 20. */
  hayMas: boolean;
}

export type EstadoStock = 'sin-stock' | 'bajo-minimo' | 'normal';

/** Única fuente de la regla de colores: <= 0 rojo; estrictamente menor al mínimo amarillo. */
export function estadoStock(
  articulo: Pick<ArticuloMostrador, 'stockActual' | 'stockMinimo'>,
): EstadoStock {
  if (articulo.stockActual <= 0) return 'sin-stock';
  return articulo.stockActual < articulo.stockMinimo ? 'bajo-minimo' : 'normal';
}
