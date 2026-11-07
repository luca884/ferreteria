/** Tipo del comprobante del proveedor (CU-13). */
export type TipoCompra = 'FA' | 'FB' | 'FC' | 'REM';

export const TIPOS_COMPRA: TipoCompra[] = ['FA', 'FB', 'FC', 'REM'];

export const ETIQUETA_TIPO_COMPRA: Record<TipoCompra, string> = {
  FA: 'Factura A',
  FB: 'Factura B',
  FC: 'Factura C',
  REM: 'Remito',
};

/** Un recargo con nombre sobre el costo (por ejemplo «Embalaje y carga» 4,44 %); negativo es descuento. */
export interface RecargoCompra {
  nombre: string;
  porcentaje: number;
}

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
  /** Los recargos del costo (hasta 5). Sin la clave, la compra los borra de sus artículos. */
  recargos?: RecargoCompra[];
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
  /** El costo con los recargos de antes y de la compra; solo difieren del costo cuando la compra trae recargos. */
  costoRealAnterior: number;
  costoRealNuevo: number;
  /** El stock de hoy y el que queda después de la compra (anterior + cantidad). */
  stockAnterior: number;
  stockNuevo: number;
  /** Porcentaje de IVA del artículo (21, 10,5…). */
  alicuotaIva: number;
}

/** Una línea de IVA de la vista previa: la base de esa alícuota (con los recargos) y su importe. */
export interface IvaPorAlicuota {
  porcentaje: number;
  base: number;
  importe: number;
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
  recargos: RecargoCompra[];
  /** El neto con los recargos de la compra; los recargos son la diferencia con el neto. */
  subtotalSinIva: number;
  recargosImporte: number;
  /** Por alícuota; vacío en Factura C y Remito. */
  iva: IvaPorAlicuota[];
  /** Subtotal más IVA; en Factura C y Remito es el subtotal. */
  total: number;
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
  recargos: RecargoCompra[];
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

/** Lo que el backend leyó de la factura en PDF (CU-13): el borrador de la compra, sin guardar nada. */
export interface BorradorDeFactura {
  /** Null si ningún CUIT leído es de un proveedor activo. */
  proveedorId: number | null;
  proveedorNombre: string | null;
  cuits: string[];
  tipo: 'FA' | 'FB' | 'FC' | null;
  puntoVenta: number | null;
  numero: number | null;
  /** aaaa-mm-dd */
  fecha: string | null;
  recargos: (RecargoCompra & { importe: number })[];
  renglones: RenglonDeFactura[];
  sumaNetos: number;
  /** CUADRA: la suma de la factura coincide con la leída. */
  control: 'CUADRA' | 'SIN_CONTROL';
  cantidadNoEntera: boolean;
}

export interface RenglonDeFactura {
  codigo: string;
  descripcion: string;
  cantidad: number;
  costoUnitario: number;
  neto: number;
  /** Null si el código del proveedor no tiene artículo (como los dos que siguen). */
  articuloId: number | null;
  articuloDescripcion: string | null;
  articuloCodigoInterno: string | null;
  /** La moneda del costo del artículo. */
  articuloMoneda: string | null;
  cantidadNoEntera: boolean;
}
