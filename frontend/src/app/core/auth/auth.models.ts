export type Rol = 'SUPER_ADMIN' | 'DUENO' | 'VENDEDOR';

export interface Sesion {
  userId: number;
  nombre: string;
  email: string;
  rol: Rol;
  ferreteriaId: number | null;
  ferreteriaNombre: string | null;
}

export interface LoginResponse {
  token: string;
  sesion: Sesion;
}
