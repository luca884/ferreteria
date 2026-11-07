import { Sesion } from '../../core/auth/auth.models';

/**
 * La lista de Etiquetas en localStorage (`etiquetas:{ferreteriaId}:{usuarioId}`), como el ticket del mostrador: solo el
 * id de cada artículo y sus copias. Los datos del artículo se traen de nuevo al volver a la pantalla, así no quedan
 * viejos. Se borra con «Vaciar» o al cerrar la sesión (`AL_CERRAR_SESION`).
 */

export interface EtiquetaGuardada {
  id: number;
  copias: number;
}

const MAX_COPIAS = 999;

export function claveDeEtiquetas(sesion: Sesion | null): string | null {
  if (!sesion || sesion.ferreteriaId == null || sesion.userId == null) return null;
  return `etiquetas:${sesion.ferreteriaId}:${sesion.userId}`;
}

function esGuardada(x: unknown): x is EtiquetaGuardada {
  if (typeof x !== 'object' || x === null) return false;
  const { id, copias } = x as Record<string, unknown>;
  return (
    Number.isSafeInteger(id) &&
    (id as number) > 0 &&
    Number.isInteger(copias) &&
    (copias as number) >= 1 &&
    (copias as number) <= MAX_COPIAS
  );
}

/** Lo guardado, en el orden en que se sumó; vacío si no hay, está roto o no se puede leer. */
export function leerEtiquetas(clave: string): EtiquetaGuardada[] {
  try {
    const raw = localStorage.getItem(clave);
    if (!raw) return [];
    const t: unknown = JSON.parse(raw);
    if (typeof t !== 'object' || t === null) return [];
    const { v, items } = t as Record<string, unknown>;
    if (v !== 1 || !Array.isArray(items) || !items.every(esGuardada)) return [];
    if (new Set(items.map((i) => i.id)).size !== items.length) return [];
    return items.map(({ id, copias }) => ({ id, copias }));
  } catch {
    return [];
  }
}

/** Una lista vacía borra lo guardado. Si localStorage falla, la pantalla sigue andando sin guardar. */
export function guardarEtiquetas(clave: string, items: readonly EtiquetaGuardada[]): void {
  try {
    if (items.length === 0) {
      localStorage.removeItem(clave);
    } else {
      localStorage.setItem(clave, JSON.stringify({ v: 1, items: items.map(({ id, copias }) => ({ id, copias })) }));
    }
  } catch {
    /* sin persistencia */
  }
}

/** Paso de `AL_CERRAR_SESION`: la lista de ese usuario se va con la sesión. */
export function borrarEtiquetasGuardadas(sesion: Sesion): void {
  const clave = claveDeEtiquetas(sesion);
  if (clave) guardarEtiquetas(clave, []);
}
