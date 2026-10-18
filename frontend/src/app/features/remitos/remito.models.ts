import { Unidad } from '../articulos/articulo.models';
import { RenglonVentaRequest } from '../mostrador/venta.models';

/** Contrato de /api/remitos (G55), tipado contra EmitirRemitoRequest / RemitoResponse. */

export const ESTADOS_REMITO = ['EMITIDO', 'FACTURADO', 'ANULADO'] as const;
export type EstadoRemito = (typeof ESTADOS_REMITO)[number];

export const ETIQUETA_ESTADO_REMITO: Record<EstadoRemito, string> = {
  EMITIDO: 'Emitido',
  FACTURADO: 'Facturado',
  ANULADO: 'Anulado',
};

export const CLIENTE_REMITO_MAXIMO = 80;
export const OBSERVACIONES_REMITO_MAXIMO = 200;

export interface EmitirRemitoRequest {
  /** UUID del ticket: la misma clave en cada reintento. */
  clave: string;
  renglones: RenglonVentaRequest[];
  /** El total del ticket, ya con los descuentos. El remito no lleva recargo. */
  totalMostrado: number;
  /** Descuento global en %, aplicado a cada renglón. Se omite si no hay. */
  descuentoPorcentaje?: number;
  clienteNombre?: string;
  observaciones?: string;
}

export interface RenglonRemitoResponse {
  renglon: number;
  articuloId: number;
  descripcion: string;
  unidad: Unidad;
  cantidad: number;
  precioUnitario: number;
  alicuotaIva: string;
  bruto: number;
  descuentoPorcentaje: number;
  total: number;
  neto: number;
  iva: number;
}

/** Un artículo que quedó con stock negativo al emitir el remito. */
export interface RemitoSinStock {
  articuloId: number;
  descripcion: string;
  stockActual: number;
}

export interface RemitoResponse {
  remitoId: number;
  numero: number;
  estado: EstadoRemito;
  /** LocalDateTime ISO sin zona. */
  fecha: string;
  clienteNombre?: string;
  observaciones?: string;
  subtotal: number;
  descuentoPorcentaje: number;
  descuento: number;
  neto: number;
  iva: number;
  total: number;
  renglones: RenglonRemitoResponse[];
  /** Solo viene al emitir; en un remito repetido viene vacío. */
  sinStock?: RemitoSinStock[];
}

/** Una fila del listado: lo justo para la grilla, sin renglones. */
export interface RemitoResumen {
  remitoId: number;
  numero: number;
  estado: EstadoRemito;
  fecha: string;
  clienteNombre?: string;
  total: number;
}

/** Filtros del listado; `desde` y `hasta` son días (aaaa-mm-dd), ambos inclusive. `page` empieza en 0. */
export interface FiltroRemitos {
  page: number;
  size?: number;
  estado?: EstadoRemito;
  numero?: number;
  desde?: string;
  hasta?: string;
}

/** Lo que se completa en el diálogo del remito: los dos datos son opcionales. */
export interface DatosRemito {
  clienteNombre?: string;
  observaciones?: string;
}

/** 201 si el remito es nuevo, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface RemitoEmitido {
  remito: RemitoResponse;
  repetido: boolean;
}
