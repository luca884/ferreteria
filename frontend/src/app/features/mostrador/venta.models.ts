import { Unidad } from '../articulos/articulo.models';

/** Contrato de POST /api/ventas, tipado contra ConfirmarVentaRequest / VentaResponse / VentaRechazadaResponse. */

export const MEDIOS_PAGO = ['EFECTIVO', 'DEBITO', 'CREDITO', 'TRANSFERENCIA'] as const;
export type MedioPago = (typeof MEDIOS_PAGO)[number];

export const MEDIO_PAGO_LABELS: Record<MedioPago, string> = {
  EFECTIVO: 'Efectivo',
  DEBITO: 'Débito',
  CREDITO: 'Crédito',
  TRANSFERENCIA: 'Transferencia',
};

export interface RenglonVentaRequest {
  articuloId: number;
  cantidad: number;
  precioUnitario: number;
}

export interface ConfirmarVentaRequest {
  /** UUID generado al agregar el primer renglón: la misma clave en cada reintento. */
  clave: string;
  renglones: RenglonVentaRequest[];
  totalMostrado: number;
  medioPago: MedioPago;
}

export interface RenglonVentaResponse {
  renglon: number;
  articuloId: number;
  descripcion: string;
  unidad: Unidad;
  cantidad: number;
  precioUnitario: number;
  alicuotaIva: string;
  total: number;
  neto: number;
  iva: number;
}

export interface SinStockResponse {
  articuloId: number;
  descripcion: string;
  stockActual: number;
}

export interface VentaResponse {
  ventaId: number;
  numero: number;
  total: number;
  /** LocalDateTime ISO sin zona. */
  fecha: string;
  medioPago: MedioPago;
  renglones: RenglonVentaResponse[];
  sinStock: SinStockResponse[];
}

/** 201 si la venta es nueva, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface VentaCobrada {
  venta: VentaResponse;
  repetida: boolean;
}

export type MotivoRechazo =
  | 'ARTICULO_INEXISTENTE'
  | 'ARTICULO_DADO_DE_BAJA'
  | 'PRECIOS_CAMBIADOS'
  | 'CLAVE_CON_OTRO_CONTENIDO';

/** Los campos null no se serializan: cada motivo trae solo los que le aplican. */
export interface ArticuloRechazado {
  articuloId: number;
  descripcion?: string;
  precioMostrado?: number;
  precioActual?: number;
  totalRenglon?: number;
}

/** Cuerpo de los 404, 409 y 422 de una venta. `total` viene solo en PRECIOS_CAMBIADOS. */
export interface VentaRechazada {
  status: number;
  message: string;
  timestamp: string;
  motivo: MotivoRechazo;
  articulos: ArticuloRechazado[];
  total?: number;
}

/** Los 400 de validación: `errors` por campo (renglones[0].cantidad, medioPago...). */
export interface VentaInvalida {
  status: number;
  message: string;
  errors?: Record<string, string>;
}
