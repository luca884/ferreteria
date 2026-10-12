export interface Proveedor {
  id: number;
  nombre: string;
  cuit: string | null;
  activo: boolean;
}

export interface ProveedorRequest {
  nombre: string;
  cuit: string | null;
  activo: boolean;
}
