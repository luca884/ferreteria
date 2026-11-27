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
  /** La cuenta demo tiene las credenciales protegidas. Ausente en una sesión guardada antes. */
  cuentaDemo?: boolean;
}

/** El token y la sesión con los que se entra: el login de siempre y el `/auth/totp/verificar` del super admin. */
export interface Ingreso {
  token: string;
  sesion: Sesion;
}

/** Lo que le falta al super admin después de la contraseña: activar el código de la app (con el QR) o escribirlo. */
export type SegundoPaso = 'ALTA' | 'CODIGO';

/** El super admin con la contraseña bien: sin token ni sesión hasta que pase el código (totp-super-admin, S1). */
export interface SegundoPasoPendiente {
  token: null;
  sesion: null;
  segundoPaso: SegundoPaso;
  /** Autoriza solo `/auth/totp/alta` y `/auth/totp/verificar`, vence a los 5 minutos y nunca se guarda. */
  tokenTotp: string;
}

/** Dueño y Vendedor entran directo (`segundoPaso` y `tokenTotp` en null o ausentes); el super admin, en dos pasos. */
export type LoginResponse = (Ingreso & { segundoPaso?: null; tokenTotp?: null }) | SegundoPasoPendiente;

/** El alta del código de la app: la clave en Base32, el `otpauth://` y su QR en filas de '0' y '1'. */
export interface AltaTotp {
  clave: string;
  uri: string;
  qr: string[];
}
