/** Los roles que el Dueño puede darle a un usuario de su ferretería. */
export type RolDeFerreteria = 'DUENO' | 'VENDEDOR';

export const ROLES_DE_FERRETERIA: readonly RolDeFerreteria[] = ['DUENO', 'VENDEDOR'];

export const ROL_DE_FERRETERIA_LABELS: Record<RolDeFerreteria, string> = {
  DUENO: 'Dueño',
  VENDEDOR: 'Vendedor',
};

export interface Usuario {
  id: number;
  nombre: string;
  email: string;
  rol: RolDeFerreteria;
  activo: boolean;
}

/** Alta: la contraseña la escribe el Dueño (8 a 72 caracteres) y se la dice en persona. */
export interface UsuarioRequest {
  nombre: string;
  email: string;
  rol: RolDeFerreteria;
  password: string;
}

/** Edición: nombre, email y rol. La contraseña tiene su propio endpoint. */
export interface EdicionUsuarioRequest {
  nombre: string;
  email: string;
  rol: RolDeFerreteria;
}
