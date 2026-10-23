import { AlicuotaIva } from '../articulos/articulo.models';

/** Los controles de datos del catálogo, como los de GDS (G81) más los de GDS-18. */
export type ControlDeDatos = 'COSTO_CERO' | 'SIN_RUBRO' | 'MARGEN_NEGATIVO' | 'IVA_CERO';

export const CONTROLES: readonly { valor: ControlDeDatos; texto: string; explicacion: string }[] = [
  { valor: 'COSTO_CERO', texto: 'Costo en cero', explicacion: 'artículos activos con el costo en $ 0.' },
  { valor: 'SIN_RUBRO', texto: 'Sin rubro', explicacion: 'artículos activos que no tienen rubro.' },
  {
    valor: 'MARGEN_NEGATIVO',
    texto: 'Margen negativo',
    explicacion: 'el costo supera el precio sin IVA, se vende a pérdida.',
  },
  { valor: 'IVA_CERO', texto: 'IVA en 0 %', explicacion: 'artículos activos con la alícuota de IVA en 0 %.' },
];

/** Un artículo que cae en un control. Sin rubro o proveedor, null. */
export interface ArticuloEnControl {
  articuloId: number;
  codigoInterno: string;
  descripcion: string;
  rubro: string | null;
  proveedor: string | null;
  costo: number;
  moneda: 'ARS' | 'USD';
  alicuotaIva: AlicuotaIva;
  precioFinal: number;
  precioManual: boolean;
}
