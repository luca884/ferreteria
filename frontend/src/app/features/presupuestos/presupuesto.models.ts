import { DocumentoNoFiscal } from '../../shared/documento-no-fiscal/documento-no-fiscal.models';
import { Unidad } from '../articulos/articulo.models';
import { RenglonVentaRequest } from '../mostrador/venta.models';

/** Contrato de /api/presupuestos (GDS-13), tipado contra EmitirPresupuestoRequest / PresupuestoResponse. */

export const ESTADOS_PRESUPUESTO = ['PENDIENTE', 'ACEPTADO', 'RECHAZADO', 'FACTURADO'] as const;
export type EstadoPresupuesto = (typeof ESTADOS_PRESUPUESTO)[number];

export const ETIQUETA_ESTADO: Record<EstadoPresupuesto, string> = {
  PENDIENTE: 'Pendiente',
  ACEPTADO: 'Aceptado',
  RECHAZADO: 'Rechazado',
  FACTURADO: 'Facturado',
};

/** «dd/MM/aaaa» de un día «aaaa-mm-dd», leído del texto: no pasa por la zona del navegador. */
export function fechaDia(dia: string | null | undefined): string {
  if (!dia) return '';
  const [anio, mes, d] = dia.slice(0, 10).split('-');
  return `${d}/${mes}/${anio}`;
}

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
  /** El cliente elegido con F9 (CL-005): si va, el servidor usa su razón social y se ignora `clienteNombre`. */
  clienteId?: number;
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
  /** Solo si se eligió un cliente de la tabla (CL-004); `clienteNombre` es la copia de su razón social o el texto libre. */
  clienteId?: number;
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

/** Lo que la hoja imprimible necesita de un presupuesto: suma «Válido hasta», el último día de validez. */
export function documentoDePresupuesto(p: PresupuestoResponse): DocumentoNoFiscal {
  return {
    tipo: 'Presupuesto',
    numero: p.numero,
    fecha: p.fecha,
    validoHasta: p.vence,
    clienteNombre: p.clienteNombre,
    observaciones: p.observaciones,
    renglones: p.renglones,
    subtotal: p.subtotal,
    descuentoPorcentaje: p.descuentoPorcentaje,
    descuento: p.descuento,
    total: p.total,
  };
}

/** Una fila del listado: lo justo para la grilla, sin renglones. */
export interface PresupuestoResumen {
  presupuestoId: number;
  numero: number;
  estado: EstadoPresupuesto;
  fecha: string;
  vence: string;
  vencido: boolean;
  clienteNombre?: string;
  total: number;
}

/** Los estados que se eligen a mano: FACTURADO solo lo pone la venta hecha desde el presupuesto. */
export type EstadoManual = Exclude<EstadoPresupuesto, 'FACTURADO'>;

/** Filtros del listado; `desde` y `hasta` son días (aaaa-mm-dd), ambos inclusive. `page` empieza en 0. */
export interface FiltroPresupuestos {
  page: number;
  size?: number;
  estado?: EstadoPresupuesto;
  numero?: number;
  desde?: string;
  hasta?: string;
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
