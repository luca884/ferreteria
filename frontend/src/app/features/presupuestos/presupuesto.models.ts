import { Unidad } from '../articulos/articulo.models';
import { RenglonVentaRequest } from '../mostrador/venta.models';

/** Contrato de /api/presupuestos (GDS-13), tipado contra EmitirPresupuestoRequest / PresupuestoResponse. */

export const ESTADOS_PRESUPUESTO = ['PENDIENTE', 'ACEPTADO', 'RECHAZADO', 'FACTURADO'] as const;
export type EstadoPresupuesto = (typeof ESTADOS_PRESUPUESTO)[number];

/** Los días de validez que acepta el servidor: de 1 a 90, y 15 si no se manda. */
export const DIAS_VALIDEZ_POR_DEFECTO = 15;
export const DIAS_VALIDEZ_MAXIMOS = 90;
export const CLIENTE_MAXIMO = 80;
export const OBSERVACIONES_MAXIMO = 200;

export interface EmitirPresupuestoRequest {
  /** UUID del ticket: la misma clave en cada reintento. */
  clave: string;
  renglones: RenglonVentaRequest[];
  /** El total del ticket, ya con los descuentos. El presupuesto no lleva recargo. */
  totalMostrado: number;
  /** Descuento global en %, aplicado a cada renglón. Se omite si no hay. */
  descuentoPorcentaje?: number;
  diasValidez?: number;
  clienteNombre?: string;
  observaciones?: string;
}

export interface RenglonPresupuestoResponse {
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

export interface PresupuestoResponse {
  presupuestoId: number;
  numero: number;
  estado: EstadoPresupuesto;
  /** LocalDateTime ISO sin zona. */
  fecha: string;
  /** Último día de validez (aaaa-mm-dd). */
  vence: string;
  /** Pendiente o aceptado cuyo vencimiento pasó: se calcula al responder. */
  vencido: boolean;
  clienteNombre?: string;
  observaciones?: string;
  subtotal: number;
  descuentoPorcentaje: number;
  descuento: number;
  neto: number;
  iva: number;
  total: number;
  renglones: RenglonPresupuestoResponse[];
}

/** Lo que se completa en el diálogo de F8: el cliente y las observaciones son opcionales. */
export interface DatosPresupuesto {
  diasValidez: number;
  clienteNombre?: string;
  observaciones?: string;
}

/** 201 si el presupuesto es nuevo, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface PresupuestoEmitido {
  presupuesto: PresupuestoResponse;
  repetido: boolean;
}
