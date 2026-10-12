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

/** Lo que devuelve el backend, igual para Dueño y Vendedor (ambos ven costo y margen). */
export interface Articulo {
  id: number;
  codigoInterno: string;
  codigoBarras: string | null;
  codigoProveedor: string | null;
  descripcion: string;
  rubroId: number | null;
  proveedorId: number | null;
  /** Sin IVA. */
  costo: number;
  /** Porcentaje propio; null hereda el del rubro o el de la ferretería. */
  margenPorcentaje: number | null;
  alicuotaIva: AlicuotaIva;
  /** Lo calcula el backend; el cliente no lo manda. */
  precioFinal: number;
  unidad: Unidad;
  stockActual: number;
  stockMinimo: number;
  ubicacion: string | null;
  activo: boolean;
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
  margenPorcentaje: number | null;
  alicuotaIva: AlicuotaIva;
  unidad: Unidad;
  stockMinimo: number;
  ubicacion: string | null;
  activo: boolean;
}
