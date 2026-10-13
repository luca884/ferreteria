/** Mapeo de columnas por índice (base 0); `filaDesde` es la fila 1-based del archivo, como la grilla. */
export interface MapeoRequest {
  hoja: number;
  filaDesde: number;
  columnaCodigo: number;
  columnaDescripcion: number | null;
  columnaCosto: number;
}

/** Mapeo guardado en el proveedor: lo mismo más los textos de encabezado leídos del archivo. */
export interface MapeoLista extends MapeoRequest {
  encabezados?: { codigo: string | null; descripcion: string | null; costo: string | null } | null;
}

export interface Conteos {
  suben: number;
  bajan: number;
  nuevos: number;
  sinCambio: number;
  noEstan: number;
  conError: number;
}

/** TODAS las filas crudas de una hoja (hasta 200 columnas); `numero` es la fila en el archivo. */
export interface Muestra {
  hojas: string[];
  hoja: number;
  columnas: number;
  filas: { numero: number; celdas: string[] }[];
}

/** Las bonificaciones vacías van como null (cuentan como 0). */
export interface VistaPreviaRequest {
  proveedorId: number;
  mapeo: MapeoRequest;
  listaIncluyeIva: boolean;
  bonificacion1: number | null;
  bonificacion2: number | null;
  bonificacion3: number | null;
}

export interface AplicarRequest extends VistaPreviaRequest {
  crearNuevos: boolean;
  /** Rubro de todos los artículos nuevos; null deja el margen por defecto. */
  rubroId: number | null;
  conteosEsperados: Conteos;
}

export interface CambioPlaneado {
  fila: number;
  articuloId: number;
  codigoProveedor: string;
  descripcion: string;
  precioLista: number;
  costoAnterior: number;
  costoNuevo: number;
  precioAnterior: number;
  precioNuevo: number;
  activo: boolean;
  variacionFuerte: boolean;
}

export interface AltaPlaneada {
  fila: number;
  codigo: string;
  descripcion: string;
  precioLista: number;
  costo: number;
}

/** `fila` y `codigo` son null en las advertencias generales (p. ej. cambio de encabezado). */
export interface AvisoDeFila {
  fila: number | null;
  codigo: string | null;
  mensaje: string;
}

export interface ArticuloAusente {
  id: number;
  codigoInterno: string;
  codigoProveedor: string | null;
  descripcion: string;
}

export interface VistaPrevia {
  conteos: Conteos;
  cambios: CambioPlaneado[];
  nuevos: AltaPlaneada[];
  errores: AvisoDeFila[];
  advertencias: AvisoDeFila[];
  noEstan: ArticuloAusente[];
  detalleRecortado: boolean;
}

export interface Importacion {
  id: number;
  proveedorId: number;
  archivo: string;
  fecha: string;
  conteos: Conteos;
  estado: 'APLICADA';
}
