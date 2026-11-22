import type { Disposicion } from '../../layout/menu';
import type { Rol } from '../auth/auth.models';

export type DestinoMenu =
  | { readonly ambito: 'roles'; readonly rol: Exclude<Rol, 'SUPER_ADMIN'> }
  | { readonly ambito: 'usuarios'; readonly id: number };

/** Menú propio resuelto por el servidor: ajuste de usuario, base del rol o el menú de fábrica. */
export interface MenuResuelto {
  readonly origen: 'USUARIO' | 'ROL' | 'ORIGINAL';
  readonly disposicion: Disposicion | null;
}
