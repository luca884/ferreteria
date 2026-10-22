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
