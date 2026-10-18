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
  /** Descuento propio del renglón en % (GDS-12). Se omite si no hay. */
  descuentoPorcentaje?: number;
}

/** Una parte de un pago mixto (GDS-07): la forma de pago y la parte del total, sin recargo, que cubre; en centavos. */
export interface PagoCobro {
  formaPagoId: number;
  centavos: number;
}

/** Una parte del pago como viaja al servidor: el monto es la parte del total SIN recargo (GDS-11). */
export interface PagoRequest {
  formaPagoId: number;
  monto: number;
}

export interface ConfirmarVentaRequest {
  /** UUID generado al agregar el primer renglón: la misma clave en cada reintento. */
  clave: string;
  renglones: RenglonVentaRequest[];
  /** El total con el recargo de la forma de pago (GDS-11). */
  totalMostrado: number;
  /** La forma de pago única, por todo el total. O, en vez de esta, `pagos`: el servidor exige una de las dos. */
  formaPagoId?: number;
  /** Pago mixto (GDS-07): un pago por forma; las partes suman el total sin recargo. */
  pagos?: PagoRequest[];
  /** Descuento global en % (GDS-12), aplicado a cada renglón. Se omite si no hay. */
  descuentoPorcentaje?: number;
  /** El presupuesto del que sale la venta (GDS-13): la venta lo deja FACTURADO. Se omite si no sale de uno. */
  presupuestoId?: number;
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
  /** La parte del recargo de la forma de pago que le tocó al renglón (ya está en el total). */
  recargo?: number;
}

export interface PagoResponse {
  formaPagoId: number;
  /** El nombre de la forma como era al cobrar. */
  formaPago: string;
  medio: MedioPago;
  recargoPorcentaje: number;
  recargo: number;
  /** Lo que se cobró con esa forma: la parte del total más su recargo. */
  monto: number;
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
  /** Solo si se pagó con una única forma. */
  formaPagoId?: number;
  medioPago?: MedioPago;
  /** Un pago por forma (GDS-07). */
  pagos?: PagoResponse[];
  /** El recargo de las formas de pago (GDS-11), ya sumado al total. */
  recargo?: number;
  renglones: RenglonVentaResponse[];
  sinStock: SinStockResponse[];
  /** Solo si la venta salió de un presupuesto. */
  presupuestoId?: number;
}

/** 201 si la venta es nueva, 200 si la clave ya estaba registrada con el mismo contenido. */
export interface VentaCobrada {
  venta: VentaResponse;
  repetida: boolean;
}

export type MotivoRechazo =
  | 'ARTICULO_INEXISTENTE'
  | 'ARTICULO_DADO_DE_BAJA'
  | 'ARTICULO_SIN_PRECIO'
  | 'PRECIOS_CAMBIADOS'
  | 'CLAVE_CON_OTRO_CONTENIDO'
  | 'CAJA_CERRADA'
  | 'CAJA_DE_OTRO_DIA';

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

/** Los 400 de validación: `errors` por campo (renglones[0].cantidad, formaPagoId, pagos[1].formaPagoId...). */
export interface VentaInvalida {
  status: number;
  message: string;
  errors?: Record<string, string>;
}
