import { UnidadDeMedida } from '../../core/formato/unidad';

/** Orden del listado, el del diálogo de GDS (sin marca: el artículo no tiene). */
export type OrdenBajoMinimo = 'DESCRIPCION' | 'CODIGO' | 'RUBRO' | 'PROVEEDOR';

export const ORDENES: readonly { valor: OrdenBajoMinimo; texto: string }[] = [
  { valor: 'DESCRIPCION', texto: 'Descripción' },
  { valor: 'CODIGO', texto: 'Código' },
  { valor: 'RUBRO', texto: 'Rubro' },
  { valor: 'PROVEEDOR', texto: 'Proveedor' },
];

/** Un artículo con stock menor al mínimo. A reponer es mínimo menos stock. Sin rubro o proveedor, null. */
export interface ArticuloBajoMinimo {
  articuloId: number;
  codigoInterno: string;
  descripcion: string;
  rubro: string | null;
  proveedor: string | null;
  costo: number;
  moneda: 'ARS' | 'USD';
  unidad: UnidadDeMedida;
  stockActual: number;
  stockMinimo: number;
  aReponer: number;
}
