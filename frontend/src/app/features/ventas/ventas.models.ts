import { FiscalVenta, ResumenFiscal } from '../../shared/factura/factura.models';
import { RenglonVentaResponse, VentaResponse } from '../mostrador/venta.models';

/** Contrato de GET /api/ventas y GET /api/ventas/{id} (G60), tipado contra VentaResumenResponse / VentaResponse. */

/**
 * La bandeja de la factura (C1, CU-16 punto 7): PENDIENTES son las que todavía no tienen CAE (también las que están en
 * revisión) y RECHAZADAS, las que ARCA no autorizó o quedaron fuera de fecha.
 */
export type FiltroFiscal = 'PENDIENTES' | 'RECHAZADAS';
export const FILTROS_FISCALES: readonly FiltroFiscal[] = ['PENDIENTES', 'RECHAZADAS'];

/** Un error de ARCA tal cual (C6), con la explicación de los códigos conocidos. */
export interface ErrorArca {
  codigo: number;
  mensaje: string;
  explicacion: string | null;
}

/** Una observación de ARCA sobre una factura autorizada, tal cual. */
export interface ObservacionArca {
  codigo: number;
  mensaje: string;
}

/** Lo que el Dueño puede hacer hoy con la factura de la venta (C2, C3). */
export type AccionFiscal = 'REINTENTAR' | 'DEJAR_SIN_FACTURA';

/**
 * El `fiscal` del detalle con lo que suma la bandeja (C1). Todo opcional: un servidor anterior no lo manda y el detalle
 * se ve como antes, sin acciones.
 */
export interface FiscalConsultado extends FiscalVenta {
  errores?: ErrorArca[];
  observaciones?: ObservacionArca[];
  motivoSinFactura?: string | null;
  /** Quién la dejó sin factura (su nombre) y cuándo (fecha y hora de Buenos Aires, sin zona). */
  sinFacturaPor?: string | null;
  sinFacturaEn?: string | null;
  /** Las que valen hoy: si no viene una, su botón no aparece. */
  acciones?: AccionFiscal[];
  /** La fecha de la venta ya no entra en ARCA: reintentar pide confirmar la fecha de hoy. */
  fechaNuevaRequerida?: boolean;
  /** Salió con ticket provisorio y ya está AUTORIZADO: hay que reimprimir la factura. */
  reimprimir?: boolean;
}

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
  /** Su factura, resumida (C2): null (o ausente) si la venta no tiene registro fiscal. */
  fiscal?: ResumenFiscal | null;
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
  /** El número de la factura como se escribió («12», «1-12» o «00001-00000012»): lo valida el servidor (S15). */
  numeroFiscal?: string;
  /** La bandeja de pendientes o de rechazadas (C1). */
  fiscal?: FiltroFiscal;
}

export interface RenglonConsultado extends RenglonVentaResponse {
  /** Precio × cantidad, antes de descuentos. */
  bruto: number;
  /** Descuento del renglón en %, el global incluido. */
  descuentoPorcentaje: number;
}

/** La venta como al cobrarla, para verla y reimprimirla. */
export interface VentaConsultada extends Omit<VentaResponse, 'renglones' | 'fiscal'> {
  renglones: RenglonConsultado[];
  /** Solo si la venta tiene registro fiscal: el de la venta más lo de la bandeja (C1). */
  fiscal?: FiscalConsultado;
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
