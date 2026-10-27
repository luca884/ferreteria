export type Rol = 'SUPER_ADMIN' | 'DUENO' | 'VENDEDOR';

/** Cómo se nombra cada rol en pantalla. */
export const ROL_LABELS: Record<Rol, string> = {
  SUPER_ADMIN: 'Administrador',
  DUENO: 'Dueño',
  VENDEDOR: 'Vendedor',
};

export interface Sesion {
  userId: number;
  nombre: string;
  email: string;
  rol: Rol;
  ferreteriaId: number | null;
  ferreteriaNombre: string | null;
  /** CU-07: el descuento máximo del usuario en el mostrador; null = sin tope. Ausente en una sesión guardada antes. */
  topeDescuento?: number | null;
  /** Su contraseña la eligió otra persona: hasta que la cambie, toda pantalla lleva a «Mi contraseña». Ausente en una sesión guardada antes. */
  debeCambiarContrasena?: boolean;
}

export interface LoginResponse {
  token: string;
  sesion: Sesion;
}
