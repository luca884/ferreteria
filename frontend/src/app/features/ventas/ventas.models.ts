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
  /** Por qué ARCA ya no acepta la fecha de una fuera de fecha sin errores de ARCA (lo arma el servidor), o null. */
  motivoFueraDeFecha?: string | null;
  /** Salió con ticket provisorio y ya está AUTORIZADO: hay que reimprimir la factura. */
  reimprimir?: boolean;
}

/** VENTA o NOTA_CREDITO (CU-08): la nota de crédito y la anulación interna son comprobantes propios. */
export type TipoComprobante = 'VENTA' | 'NOTA_CREDITO';

/** La venta que anula una nota de crédito: solo viene en una nota. */
export interface VentaAnulada {
  id: number;
  numero: number;
}

/**
 * Cómo se anuló una venta (CU-08, C4): con la nota de crédito N.º `numero`, o con la anulación interna (`interna`) y su
 * motivo. `usuario` es quién y `fecha` cuándo (LocalDateTime ISO sin zona, hora de Buenos Aires).
 */
export interface Anulacion {
  notaId: number;
  numero: number;
  interna: boolean;
  motivo: string | null;
  usuario: string | null;
  fecha: string;
}

/**
 * Lo que dice si un comprobante es una nota de crédito y cómo se anuló la venta (CU-08). Todo opcional: un servidor
 * anterior no lo manda.
 */
export interface ComprobanteAnulable {
  /** Sin `tipo` es una venta. */
  tipo?: TipoComprobante;
  ventaAnulada?: VentaAnulada;
  /** Null si la venta no está anulada (siempre null en una nota). */
  anulacion?: Anulacion | null;
}

/** Una fila del listado: lo justo para la grilla, sin renglones. */
export interface VentaResumen extends ComprobanteAnulable {
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
  /** Descuento propio del renglón en %; el global va en la venta (`descuentoPorcentaje`). */
  descuentoPorcentaje: number;
}

/** La venta como al cobrarla, para verla y reimprimirla. */
export interface VentaConsultada extends Omit<VentaResponse, 'renglones' | 'fiscal'>, ComprobanteAnulable {
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

export function esNotaDeCredito(comprobante: ComprobanteAnulable): boolean {
  return comprobante.tipo === 'NOTA_CREDITO';
}

/**
 * El nombre de un comprobante con su número: «Venta N.º 7», «Nota de crédito N.º 14» o, si la nota no tiene registro
 * fiscal (la anulación interna, CU-08 D12), «Anulación N.º 14».
 */
export function tituloDeComprobante(comprobante: ComprobanteAnulable & { numero: number; fiscal?: unknown }): string {
  if (!esNotaDeCredito(comprobante)) return `Venta N.º ${comprobante.numero}`;
  return `${comprobante.fiscal ? 'Nota de crédito' : 'Anulación'} N.º ${comprobante.numero}`;
}

/** «Anulada por la NC N.º 14» o «Anulada: <motivo>», con quién y cuándo: «· Dueño, 29/10/2026 14:05». */
export function textoDeAnulacion(anulacion: Anulacion, fecha: (iso: string) => string): string {
  const como = anulacion.interna ? `Anulada: ${anulacion.motivo ?? ''}` : `Anulada por la NC N.º ${anulacion.numero}`;
  const quien = [anulacion.usuario, fecha(anulacion.fecha)].filter((t) => !!t).join(', ');
  return quien ? `${como} · ${quien}` : como;
}

/** El cliente de una venta tal como se muestra: sin cliente es Consumidor Final. */
export function clienteDeVenta(clienteNombre: string | undefined): string {
  return clienteNombre ?? 'Consumidor Final';
}
