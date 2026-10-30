/**
 * Lo que la hoja no fiscal del remito y del presupuesto (cu-07-remito-presupuesto-imprimible) necesita saber del
 * documento. Cada pantalla arma el suyo con `documentoDeRemito` / `documentoDePresupuesto`, así la hoja no conoce ni el
 * remito ni el presupuesto.
 */

/**
 * La leyenda de la hoja. PROVISORIA (S4 de cu-07-remito-presupuesto-imprimible): el texto y si un remito sin CAI sirve para
 * acompañar la mercadería los confirma el contador antes de cerrar la rama; hasta entonces, este es el único lugar donde vive.
 */
export const LEYENDA_DOCUMENTO_NO_FISCAL = 'DOCUMENTO NO VALIDO COMO FACTURA';

/** La letra de la hoja: siempre X, nunca A, B ni C. */
export const LETRA_DOCUMENTO_NO_FISCAL = 'X';

export interface RenglonDocumentoNoFiscal {
  renglon: number;
  descripcion: string;
  cantidad: number;
  precioUnitario: number;
  descuentoPorcentaje: number;
  total: number;
}

/** La venta que facturó un remito (`RemitoResponse.facturadoPor`). `factura` solo si esa venta tiene una factura válida. */
export interface FacturadoPor {
  ventaId: number;
  numero: number;
  /** «Factura B 00001-00000012». */
  factura?: string;
}

export interface DocumentoNoFiscal {
  tipo: 'Remito' | 'Presupuesto';
  numero: number;
  /** LocalDateTime ISO sin zona. */
  fecha: string;
  /** Solo el presupuesto: el último día de validez (aaaa-mm-dd). */
  validoHasta?: string;
  clienteNombre?: string;
  observaciones?: string;
  renglones: RenglonDocumentoNoFiscal[];
  subtotal: number;
  descuentoPorcentaje: number;
  descuento: number;
  total: number;
  /** Solo el remito facturado. */
  facturadoPor?: FacturadoPor;
}
