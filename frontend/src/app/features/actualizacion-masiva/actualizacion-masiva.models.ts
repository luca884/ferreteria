import { Moneda } from '../articulos/articulo.models';

/**
 * Qué hace la operación: `COSTO_PORCENTAJE` sube o baja el costo en %; `COSTO_MONTO` le suma o resta un monto
 * (en la moneda de cada artículo); `MARGEN` fija el margen propio; `PRECIO_PORCENTAJE` y `PRECIO_MONTO` cambian
 * el precio final en % o en pesos y dejan a los artículos con precio manual (CU-14).
 */
export type ModoMasivo =
  | 'COSTO_PORCENTAJE'
  | 'COSTO_MONTO'
  | 'MARGEN'
  | 'PRECIO_PORCENTAJE'
  | 'PRECIO_MONTO';

/**
 * Filtro (todo opcional: vacío alcanza a todo el catálogo activo), modo y valor. El texto en blanco
 * viaja como null. El valor es un número (en pantalla se escribe con coma o con punto).
 */
export interface PedidoMasivo {
  proveedorId: number | null;
  rubroId: number | null;
  /** Solo los artículos que dejó esa importación (`ultima_importacion_id`); null o ausente no filtra. */
  importacionId?: number | null;
  q: string | null;
  incluirInactivos: boolean;
  modo: ModoMasivo;
  valor: number;
}

/** Lo mismo que la vista previa más la huella que ésta devolvió: si algo cambió, el backend responde 409. */
export interface AplicarPedidoMasivo extends PedidoMasivo {
  huella: string;
}

/** Contadores exactos de todo el alcance: `total = suben + bajan + sinCambioDePrecio + conError`. */
export interface ConteosMasivos {
  total: number;
  suben: number;
  bajan: number;
  sinCambioDePrecio: number;
  conError: number;
  /** Artículos que hoy heredan el margen y pasan a tener margen propio (solo en `MARGEN`). */
  pasanAMargenPropio: number;
  conAdvertencia: number;
  /** Artículos que cambian el costo, el precio o el margen propio. */
  modificados: number;
}

/**
 * Los márgenes son los efectivos (propio, del rubro o de la ferretería). `advertencia` es null si no hay; puede
 * traer la variación fuerte, el margen negativo («Margen negativo: …», CU-14) o los dos, en ese orden.
 */
export interface CambioMasivo {
  articuloId: number;
  codigo: string;
  descripcion: string;
  costoAnterior: number;
  costoNuevo: number;
  margenAnterior: number;
  margenNuevo: number;
  precioAnterior: number;
  precioNuevo: number;
  /** true si el artículo no tiene margen propio y usa el del rubro o el de la ferretería. */
  margenHeredado: boolean;
  advertencia: string | null;
  /** La moneda del artículo: los costos van en ella y los precios, siempre en pesos. Pesos si el servidor no la manda. */
  moneda?: Moneda;
}

/** Un artículo del alcance que no se puede actualizar. */
export interface ErrorMasivo {
  articuloId: number;
  codigo: string;
  descripcion: string;
  motivo: string;
}

/** `cambios` y `errores` traen como mucho 1.000 filas cada uno; los contadores siempre son exactos. */
export interface VistaPreviaMasiva {
  conteos: ConteosMasivos;
  cambios: CambioMasivo[];
  errores: ErrorMasivo[];
  detalleRecortado: boolean;
  huella: string;
  /** Cuántos artículos de TODO el alcance están en pesos y cuántos en dólares (con o sin error). */
  enPesos?: number;
  enDolares?: number;
  /** Solo con `COSTO_MONTO` y las dos monedas en el alcance: el monto se suma tal cual a cada costo. No bloquea. */
  advertenciaMezcla?: string | null;
}

export interface ResultadoMasivo {
  conteos: ConteosMasivos;
  entradasDeHistorial: number;
}
