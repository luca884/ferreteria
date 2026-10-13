/** Qué hace la operación: `COSTO_PORCENTAJE` sube o baja el costo en %; `MARGEN` fija el margen propio. */
export type ModoMasivo = 'COSTO_PORCENTAJE' | 'MARGEN';

/**
 * Filtro (todo opcional: vacío alcanza a todo el catálogo activo), modo y valor. El texto en blanco
 * viaja como null. El valor es un número (en pantalla se escribe con coma o con punto).
 */
export interface PedidoMasivo {
  proveedorId: number | null;
  rubroId: number | null;
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

/** Los márgenes son los efectivos (propio, del rubro o de la ferretería). `advertencia` es null si no hay. */
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
  advertencia: string | null;
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
}

export interface ResultadoMasivo {
  conteos: ConteosMasivos;
  entradasDeHistorial: number;
}
