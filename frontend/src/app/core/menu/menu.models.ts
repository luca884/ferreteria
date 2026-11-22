import type { Disposicion } from '../../layout/menu';

/** Menú propio resuelto por el servidor: ajuste de usuario, base del rol o el menú de fábrica. */
export interface MenuResuelto {
  readonly origen: 'USUARIO' | 'ROL' | 'ORIGINAL';
  readonly disposicion: Disposicion | null;
}
