import { MapeoLista } from '../importaciones/importacion.models';

export interface Proveedor {
  id: number;
  nombre: string;
  cuit: string | null;
  activo: boolean;
  /** Configuración de la lista de precios (CU-04): la fija la importación, de solo lectura acá. */
  mapeoLista?: MapeoLista | null;
  listaIncluyeIva?: boolean;
  bonificacion1?: number | null;
  bonificacion2?: number | null;
  bonificacion3?: number | null;
}

export interface ProveedorRequest {
  nombre: string;
  cuit: string | null;
  activo: boolean;
}
