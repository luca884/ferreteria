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
  /** CU-07: el descuento máximo que puede dar en el mostrador; null = sin tope. El Dueño nunca tiene. */
  topeDescuento?: number | null;
  /** Opcional; null o ausente: sin teléfono. */
  telefono?: string | null;
}

/** Alta: la contraseña la escribe el Dueño (8 a 72 caracteres) y se la dice en persona. */
export interface UsuarioRequest {
  nombre: string;
  email: string;
  rol: RolDeFerreteria;
  password: string;
  topeDescuento: number | null;
  /** Ausente o null: sin teléfono. La pantalla de usuarios lo manda siempre. */
  telefono?: string | null;
}

/** Edición: nombre, email, rol, tope de descuento y teléfono. La contraseña tiene su propio endpoint. */
export interface EdicionUsuarioRequest {
  nombre: string;
  email: string;
  rol: RolDeFerreteria;
  topeDescuento: number | null;
  /** Ausente o null: lo borra. */
  telefono?: string | null;
}

/** «12,5»: hasta 2 enteros y 2 decimales, con coma o punto. */
const TOPE = /^\d{1,2}([.,]\d{1,2})?$/;

/** El tope escrito en el formulario como número; vacío es sin tope. Undefined si no es válido. */
export function leerTope(texto: string): number | null | undefined {
  const limpio = texto.trim();
  if (limpio === '') return null;
  return TOPE.test(limpio) ? Number(limpio.replace(',', '.')) : undefined;
}

/** El tope para mostrar: «12,5 %», «Sin tope», o «—» para el Dueño (que no tiene). */
export function textoTope(usuario: Usuario): string {
  if (usuario.rol === 'DUENO') return '—';
  if (usuario.topeDescuento == null) return 'Sin tope';
  return `${String(usuario.topeDescuento).replace('.', ',')} %`;
}
