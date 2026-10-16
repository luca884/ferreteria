export const ALICUOTAS_IVA = ['IVA_0', 'IVA_2_5', 'IVA_5', 'IVA_10_5', 'IVA_21', 'IVA_27'] as const;
export type AlicuotaIva = (typeof ALICUOTAS_IVA)[number];

export const ALICUOTA_IVA_LABELS: Record<AlicuotaIva, string> = {
  IVA_0: '0 %',
  IVA_2_5: '2,5 %',
  IVA_5: '5 %',
  IVA_10_5: '10,5 %',
  IVA_21: '21 %',
  IVA_27: '27 %',
};

export const UNIDADES = ['UNIDAD', 'METRO', 'KILO', 'LITRO'] as const;
export type Unidad = (typeof UNIDADES)[number];

export const UNIDAD_LABELS: Record<Unidad, string> = {
  UNIDAD: 'Unidad',
  METRO: 'Metro',
  KILO: 'Kilo',
  LITRO: 'Litro',
};

/** La moneda en que se carga el costo de un artículo. El precio final siempre está en pesos. */
export const MONEDAS = ['ARS', 'USD'] as const;
export type Moneda = (typeof MONEDAS)[number];

export const MONEDA_LABELS: Record<Moneda, string> = {
  ARS: 'Pesos (ARS)',
  USD: 'Dólares (USD)',
};

/** La moneda del artículo; ARS si el backend no la manda (artículos de antes de la cotización). */
export function monedaDe(articulo: Pick<Articulo, 'moneda'> | null | undefined): Moneda {
  return articulo?.moneda ?? 'ARS';
}

/** Lo que devuelve el backend, igual para Dueño y Vendedor (ambos ven costo y margen). */
export interface Articulo {
  id: number;
  codigoInterno: string;
  codigoBarras: string | null;
  codigoProveedor: string | null;
  descripcion: string;
  rubroId: number | null;
  proveedorId: number | null;
  /** Sin IVA, en la moneda del artículo. */
  costo: number;
  /** En qué moneda está el costo. Sin dato, ARS (ver `monedaDe`). */
  moneda?: Moneda;
  /** Porcentaje propio; null hereda el del rubro o el de la ferretería. */
  margenPorcentaje: number | null;
  alicuotaIva: AlicuotaIva;
  /** Lo calcula el backend, siempre en pesos; el cliente no lo manda. */
  precioFinal: number;
  unidad: Unidad;
  stockActual: number;
  stockMinimo: number;
  ubicacion: string | null;
  activo: boolean;
}

export const MOTIVOS_AJUSTE = ['INGRESO_MERCADERIA', 'ROTURA', 'CONTEO', 'OTRO'] as const;
export type MotivoAjuste = (typeof MOTIVOS_AJUSTE)[number];

export const MOTIVO_AJUSTE_LABELS: Record<MotivoAjuste, string> = {
  INGRESO_MERCADERIA: 'Ingreso de mercadería',
  ROTURA: 'Rotura',
  CONTEO: 'Conteo',
  OTRO: 'Otro',
};

export type ModoAjuste = 'STOCK_REAL' | 'CANTIDAD';

/**
 * Ajuste manual de stock (CU-09). STOCK_REAL manda el total contado; CANTIDAD manda lo que entra (+) o sale (-),
 * que el backend suma al stock vigente al confirmar.
 */
export type AjusteStockRequest = { motivo: MotivoAjuste; detalle: string | null } & (
  | { modo?: 'STOCK_REAL'; stockReal: number }
  | { modo: 'CANTIDAD'; cantidad: number }
);

export interface AjusteStockResponse {
  articuloId: number;
  stockAnterior: number;
  diferencia: number;
  stockActual: number;
}

export interface ConfiguracionPrecios {
  margenDefectoPorcentaje: number;
  redondeoMultiplo: number;
}

/** Alta y edición. No lleva stock actual ni precio final: los resuelve el backend. */
export interface ArticuloRequest {
  codigoInterno: string;
  codigoBarras: string | null;
  codigoProveedor: string | null;
  descripcion: string;
  rubroId: number | null;
  proveedorId: number | null;
  costo: number;
  /** Sin dato el backend toma ARS. Cambiarla no convierte el costo: se carga de nuevo en la moneda nueva. */
  moneda?: Moneda;
  margenPorcentaje: number | null;
  alicuotaIva: AlicuotaIva;
  unidad: Unidad;
  stockMinimo: number;
  ubicacion: string | null;
  activo: boolean;
}
