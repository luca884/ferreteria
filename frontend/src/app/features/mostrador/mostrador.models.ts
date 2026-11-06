import { Articulo, Unidad } from '../articulos/articulo.models';

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
  /** GDS-05: desde cuándo está al día el costo (AAAA-MM-DD). Es una fecha, no el costo. */
  costoAl?: string | null;
  /** Ofertas (S3): hoy se vende al precio de oferta; `precioFinal` ya es ese precio, el que se cobra. */
  enOferta?: boolean;
  /** Ofertas (S3): el precio sin la oferta, para mostrarlo tachado; null si no está en oferta. */
  precioNormal?: number | null;
}

/**
 * Un artículo de la ficha (`ArticulosService.obtener`) como lo ve el mostrador: con el precio que se cobra hoy, el de
 * la oferta vigente si la hay, igual que `ArticuloMostradorResponse` del backend. La ficha trae `precioFinal` normal y
 * la oferta aparte, ya calculada para hoy por el servidor (`vigente` y `precioOferta`).
 */
export function comoArticuloMostrador(articulo: Articulo): ArticuloMostrador {
  const precioOferta = articulo.oferta?.vigente ? articulo.oferta.precioOferta : null;
  const enOferta = precioOferta != null;
  return {
    id: articulo.id,
    codigoInterno: articulo.codigoInterno,
    codigoBarras: articulo.codigoBarras,
    codigoProveedor: articulo.codigoProveedor,
    descripcion: articulo.descripcion,
    precioFinal: enOferta ? precioOferta : articulo.precioFinal,
    unidad: articulo.unidad,
    stockActual: articulo.stockActual,
    stockMinimo: articulo.stockMinimo,
    ubicacion: articulo.ubicacion,
    activo: articulo.activo,
    costoAl: articulo.costoAl,
    enOferta,
    precioNormal: enOferta ? articulo.precioFinal : null,
  };
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
