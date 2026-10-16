import { AlicuotaIva, Moneda, Unidad } from '../articulos/articulo.models';

/**
 * Mapeo de columnas por índice (base 0); `filaDesde` es la fila 1-based del archivo, como la grilla.
 * Los campos desde `hojas` son opcionales: un mapeo guardado antes de listas-y-moneda no los trae. No existen
 * campos para el stock ni la marca: la lista nunca los cambia. Negativo es descuento y positivo es recargo.
 */
export interface MapeoRequest {
  /** Hoja de referencia: de ahí salen el encabezado y la primera fila de datos. */
  hoja: number;
  filaDesde: number;
  columnaCodigo: number;
  columnaDescripcion: number | null;
  columnaCosto: number;
  /** Hojas que se leen (base 0); sin esto se lee solo `hoja`. */
  hojas?: number[] | null;
  columnaMoneda?: number | null;
  /** Moneda de todas las filas, para las listas sin columna de moneda. Excluyente con `columnaMoneda`. */
  monedaFija?: Moneda | null;
  /** Precio sin IVA: solo sirve para calcular la alícuota, junto con el precio con IVA. */
  columnaSinIva?: number | null;
  columnaConIva?: number | null;
  columnaIva?: number | null;
  columnaAjuste1?: number | null;
  columnaAjuste2?: number | null;
  columnaAjuste3?: number | null;
  columnaCodigoBarras?: number | null;
  columnaUnidad?: number | null;
  columnaRubro?: number | null;
}

/** Textos de encabezado que el backend leyó al guardar el mapeo (no se editan desde la pantalla). */
export interface EncabezadosGuardados {
  codigo: string | null;
  descripcion: string | null;
  costo: string | null;
  moneda?: string | null;
  sinIva?: string | null;
  conIva?: string | null;
  iva?: string | null;
  ajuste1?: string | null;
  ajuste2?: string | null;
  ajuste3?: string | null;
  codigoBarras?: string | null;
  unidad?: string | null;
  rubro?: string | null;
}

/** Mapeo guardado en el proveedor: lo mismo más los textos de encabezado leídos del archivo. */
export interface MapeoLista extends MapeoRequest {
  encabezados?: EncabezadosGuardados | null;
}

export interface Conteos {
  suben: number;
  bajan: number;
  nuevos: number;
  sinCambio: number;
  noEstan: number;
  conError: number;
  cambianMoneda: number;
  corrigen: number;
  salteadas: number;
}

export interface FilaDeMuestra {
  numero: number;
  celdas: string[];
}

/** Una hoja del archivo con TODAS sus filas crudas; `filaEncabezado` es la última del encabezado (null si no se reconoce). */
export interface HojaDeMuestra {
  indice: number;
  nombre: string;
  columnas: number;
  filaEncabezado: number | null;
  filas: FilaDeMuestra[];
}

/** Lo que el servidor reconoce solo del archivo. Es una sugerencia: todo puede venir null. */
export interface PropuestaDeMapeo {
  hoja: number | null;
  filaDesde: number | null;
  columnaCodigo: number | null;
  columnaDescripcion: number | null;
  columnaCosto: number | null;
  columnaMoneda: number | null;
  columnaSinIva: number | null;
  columnaConIva: number | null;
  columnaIva: number | null;
  columnaAjuste1: number | null;
  columnaAjuste2: number | null;
  columnaAjuste3: number | null;
  columnaCodigoBarras: number | null;
  columnaUnidad: number | null;
  columnaRubro: number | null;
  /** Las hojas con el encabezado reconocido. */
  hojas: number[];
}

/** El archivo entero (todas las hojas) y la propuesta de mapeo. */
export interface Muestra {
  hojas: HojaDeMuestra[];
  propuesta: PropuestaDeMapeo | null;
}

/** Las bonificaciones vacías van como null (cuentan como 0). `rubroId` es el de los artículos nuevos (null = sin rubro). */
export interface VistaPreviaRequest {
  proveedorId: number;
  mapeo: MapeoRequest;
  listaIncluyeIva: boolean;
  bonificacion1: number | null;
  bonificacion2: number | null;
  bonificacion3: number | null;
  rubroId: number | null;
}

/** Una fila del archivo por su lugar: la hoja (base 0) y la fila (1-based, como la grilla). */
export interface FilaElegida {
  hoja: number;
  fila: number;
}

export interface AplicarRequest extends VistaPreviaRequest {
  crearNuevos: boolean;
  conteosEsperados: Conteos;
  /** Sin esto se aplican todas las filas elegibles; una lista vacía da 400. */
  filasElegidas?: FilaElegida[];
}

/** Descuentos o recargos por artículo, en %: negativo es descuento y positivo es recargo. */
export interface Ajustes {
  a1: number | null;
  a2: number | null;
  a3: number | null;
}

/** Un artículo existente que la lista toca: sube, baja, cambia de moneda o se corrige (IVA o ajustes). */
export interface CambioPlaneado {
  fila: number;
  hoja: number;
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
  monedaAnterior: Moneda | null;
  monedaNueva: Moneda | null;
  alicuotaAnterior: AlicuotaIva | null;
  alicuotaNueva: AlicuotaIva | null;
  ajustesAnteriores: Ajustes;
  ajustesNuevos: Ajustes;
}

export interface AltaPlaneada {
  fila: number;
  hoja: number;
  codigo: string;
  descripcion: string;
  precioLista: number;
  costo: number;
  moneda: Moneda;
  alicuotaIva: AlicuotaIva;
  ajustes: Ajustes;
  unidad: Unidad;
  codigoBarras: string | null;
  rubroId: number | null;
  /** Precio final en pesos, con el margen del rubro elegido. */
  precioNuevo: number | null;
}

/** Una fila que no cambia nada: solo hace falta saber cuál es para poder elegirla. */
export interface FilaSinCambio {
  id: number;
  hoja: number;
  fila: number;
  codigo: string;
  descripcion: string;
}

/** `fila` y `codigo` son null en las advertencias generales (p. ej. cambio de encabezado); `hoja` también si no corresponde. */
export interface AvisoDeFila {
  hoja: number | null;
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

/** Lo que pasaría al aplicar: los contadores son los del archivo entero y cada lista trae TODAS sus filas. */
export interface VistaPrevia {
  conteos: Conteos;
  /** Los que suben y los que bajan. */
  cambios: CambioPlaneado[];
  nuevos: AltaPlaneada[];
  errores: AvisoDeFila[];
  advertencias: AvisoDeFila[];
  noEstan: ArticuloAusente[];
  cambiosDeMoneda: CambioPlaneado[];
  correcciones: CambioPlaneado[];
  sinCambio: FilaSinCambio[];
}

export interface Importacion {
  id: number;
  proveedorId: number;
  proveedorNombre: string | null;
  usuarioId: number | null;
  archivo: string;
  fecha: string;
  conteos: Conteos;
  estado: 'APLICADA';
}
