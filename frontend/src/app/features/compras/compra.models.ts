/** Tipo del comprobante del proveedor (CU-13). */
export type TipoCompra = 'FA' | 'FB' | 'FC' | 'REM';

export const TIPOS_COMPRA: TipoCompra[] = ['FA', 'FB', 'FC', 'REM'];

export const ETIQUETA_TIPO_COMPRA: Record<TipoCompra, string> = {
  FA: 'Factura A',
  FB: 'Factura B',
  FC: 'Factura C',
  REM: 'Remito',
};

export interface RenglonCompraRequest {
  articuloId: number;
  cantidad: number;
  /** Sin IVA, en la moneda del artículo. */
  costoUnitario: number;
}

/** Lo que se manda para la vista previa y para registrar. La clave solo la exige registrar. */
export interface CompraRequest {
  clave?: string;
  proveedorId: number;
  tipo: TipoCompra;
  puntoVenta: number;
  numero: number;
  /** aaaa-mm-dd */
  fecha: string;
  renglones: RenglonCompraRequest[];
}

export interface RenglonPlaneado {
  articuloId: number;
  codigoInterno: string;
  descripcion: string;
  unidad: string;
  cantidad: number;
  costoAnterior: number;
  costoNuevo: number;
  precioAnterior: number;
  precioNuevo: number;
  subtotal: number;
  /** Variación fuerte (más de ±50 %) o null. */
  advertencia: string | null;
}

/** La vista previa: qué haría la compra con el costo y el precio de cada artículo. No guarda nada. */
export interface PlanCompra {
  proveedorNombre: string;
  moneda: string;
  neto: number;
  suben: number;
  bajan: number;
  iguales: number;
  renglones: RenglonPlaneado[];
}

export interface RenglonCompraResponse {
  renglon: number;
  articuloId: number;
  codigoInterno: string;
  descripcion: string;
  unidad: string;
  cantidad: number;
  costoUnitario: number;
  subtotal: number;
  costoAnterior: number;
  precioAnterior: number;
  precioNuevo: number;
}

export interface CompraResponse {
  id: number;
  tipo: TipoCompra;
  puntoVenta: number;
  numero: number;
  /** «Factura A 00003-00001234» */
  comprobante: string;
  fecha: string;
  proveedorId: number;
  proveedorNombre: string;
  moneda: string;
  neto: number;
  registrada: string;
  renglones: RenglonCompraResponse[];
}

/** 201 es nueva; 200 es la misma clave ya registrada con el mismo contenido. */
export interface CompraRegistrada {
  compra: CompraResponse;
  repetida: boolean;
}

export interface CompraResumen {
  id: number;
  fecha: string;
  tipo: TipoCompra;
  comprobante: string;
  proveedorId: number;
  proveedorNombre: string;
  moneda: string;
  neto: number;
  renglones: number;
}

export interface FiltroCompras {
  page: number;
  size?: number;
  proveedorId?: number;
  desde?: string;
  hasta?: string;
}
