export interface Rubro {
  id: number;
  nombre: string;
  margenPorcentaje: number;
  /** Artículos que conservaron su precio al cambiar el margen: sus ajustes lo dejarían en 0 o menos. */
  avisos?: string[];
}

export interface RubroRequest {
  nombre: string;
  margenPorcentaje: number;
}

/** Un artículo que cambia de precio con el margen nuevo del rubro: el precio de hoy y el nuevo. */
export interface ArticuloQueCambia {
  id: number;
  codigo: string;
  descripcion: string;
  precioActual: number;
  precioNuevo: number;
}

/**
 * Antes de cambiar el margen del rubro: cuántos artículos cambian de precio (`cantidad`) y, por descripción, hasta
 * 1.000 de ellos. `recortado` avisa que la lista no los trae a todos.
 */
export interface VistaPreviaMargen {
  cantidad: number;
  articulos: ArticuloQueCambia[];
  recortado: boolean;
}
