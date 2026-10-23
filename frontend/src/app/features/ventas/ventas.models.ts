import { RenglonVentaResponse, VentaResponse } from '../mostrador/venta.models';

/** Contrato de GET /api/ventas y GET /api/ventas/{id} (G60), tipado contra VentaResumenResponse / VentaResponse. */

/** Una fila del listado: lo justo para la grilla, sin renglones. */
export interface VentaResumen {
  ventaId: number;
  numero: number;
  /** LocalDateTime ISO sin zona, hora de Buenos Aires. */
  fecha: string;
  /** Solo si la venta tiene cliente. */
  clienteNombre?: string;
  total: number;
  /** Quién la cobró. */
  usuario?: string;
  /** false si quedó «No facturable» (NF-002). */
  facturable: boolean;
}

/** Filtros del listado; `desde` y `hasta` son días (aaaa-mm-dd), ambos inclusive. `page` empieza en 0. */
export interface FiltroVentas {
  page: number;
  size?: number;
  numero?: number;
  /** Texto dentro del nombre del cliente. */
  cliente?: string;
  desde?: string;
  hasta?: string;
}

export interface RenglonConsultado extends RenglonVentaResponse {
  /** Precio × cantidad, antes de descuentos. */
  bruto: number;
  /** Descuento del renglón en %, el global incluido. */
  descuentoPorcentaje: number;
}

/** La venta como al cobrarla, para verla y reimprimirla. */
export interface VentaConsultada extends Omit<VentaResponse, 'renglones'> {
  renglones: RenglonConsultado[];
  /** Antes de descuentos. subtotal − descuento + recargo = total. */
  subtotal: number;
  /** El descuento global en %. */
  descuentoPorcentaje: number;
  /** Lo descontado en toda la venta. */
  descuento: number;
  /** Quién la cobró. */
  usuario?: string;
}

/** El cliente de una venta tal como se muestra: sin cliente es Consumidor Final. */
export function clienteDeVenta(clienteNombre: string | undefined): string {
  return clienteNombre ?? 'Consumidor Final';
}
