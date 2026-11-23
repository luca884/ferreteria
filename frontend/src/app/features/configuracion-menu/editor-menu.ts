import type { RolDeFerreteria } from '../usuarios/usuario.models';
import {
  armarMenu, CATALOGO, CLAVE_PERSONALIZAR_MENU, DISPOSICION_ORIGINAL, Disposicion, GrupoDisposicion, Tono,
} from '../../layout/menu';

export const MAX_GRUPOS = 10;
export const ERROR_LIMITE = 'Podés tener hasta 10 grupos.';
export const ERROR_NOMBRE_LARGO = 'El nombre del grupo puede tener hasta 30 caracteres.';

export const TONOS = [
  { valor: 'ventas', rotulo: 'Turquesa' },
  { valor: 'articulos', rotulo: 'Naranja' },
  { valor: 'compras', rotulo: 'Violeta' },
  { valor: 'precios', rotulo: 'Verde' },
  { valor: 'configuracion', rotulo: 'Rosa' },
] as const satisfies readonly { valor: Exclude<Tono, 'mostrador'>; rotulo: string }[];

const originales = DISPOSICION_ORIGINAL.grupos;
export const esOriginal = (id: string) => originales.some((g) => g.id === id);
export const sePuedeOcultar = (clave: string) => clave !== CLAVE_PERSONALIZAR_MENU;
export const opcionesPara = (rol: RolDeFerreteria) => CATALOGO.filter((o) => rol === 'DUENO' || !o.soloDueno);

/** Las mismas opciones que arma el menú real, pero el editor conserva los grupos vacíos para poder reutilizarlos. */
export function prepararEditor(disposicion: Disposicion | null, rol: RolDeFerreteria): Disposicion {
  const base = disposicion ?? DISPOSICION_ORIGINAL;
  const permitidas = new Set(opcionesPara(rol).map((o) => o.clave));
  const ocultas = [...new Set(base.ocultas)].filter((clave) => permitidas.has(clave) && sePuedeOcultar(clave));
  const visibles = new Map(armarMenu({ grupos: base.grupos, ocultas }, rol === 'DUENO').map((g) => [g.id, g]));
  const grupos: GrupoDisposicion[] = base.grupos.map((grupo) => {
    const claves = visibles.get(grupo.id)?.opciones.map((o) => o.clave) ?? [];
    visibles.delete(grupo.id);
    return { ...grupo, claves };
  });
  for (const { id, tono, rotulo, opciones } of visibles.values()) {
    grupos.push({ id, tono, rotulo, claves: opciones.map((o) => o.clave) });
  }
  return { grupos, ocultas };
}

/** `null` identifica la lista de ocultas y no puede confundirse con un id de grupo. */
export function moverOpcion(
  d: Disposicion, clave: string, desde: string | null, hasta: string | null, indice: number,
): Disposicion {
  const lista = (id: string | null) => id === null ? d.ocultas : d.grupos.find((g) => g.id === id)?.claves;
  const destino = lista(hasta);
  if (!lista(desde)?.includes(clave) || !destino || (hasta === null && !sePuedeOcultar(clave))) return d;

  const sinOpcion = (claves: readonly string[]) => claves.filter((c) => c !== clave);
  const conOpcion = sinOpcion(destino);
  conOpcion.splice(indice, 0, clave);
  return {
    grupos: d.grupos.map((g) => g.id === hasta ? { ...g, claves: conOpcion }
      : g.id === desde ? { ...g, claves: sinOpcion(g.claves) } : g),
    ocultas: hasta === null ? conOpcion : desde === null ? sinOpcion(d.ocultas) : d.ocultas,
  };
}

export function reordenarGrupos(d: Disposicion, desde: number, hasta: number): Disposicion {
  if (desde === hasta || !d.grupos[desde] || !d.grupos[hasta]) return d;
  const grupos = [...d.grupos];
  grupos.splice(hasta, 0, ...grupos.splice(desde, 1));
  return { ...d, grupos };
}

export function agregarGrupo(d: Disposicion): Disposicion {
  if (d.grupos.length >= MAX_GRUPOS) return d;
  let id: string;
  do id = `g-${crypto.randomUUID().slice(0, 8)}`;
  while (d.grupos.some((g) => g.id === id));
  return { ...d, grupos: [...d.grupos, { id, rotulo: '', tono: 'configuracion', claves: [] }] };
}

export function editarGrupo(
  d: Disposicion, id: string, cambios: Partial<Pick<GrupoDisposicion, 'rotulo' | 'tono'>>,
): Disposicion {
  return { ...d, grupos: d.grupos.map((g) => g.id === id ? { ...g, ...cambios } : g) };
}

export function borrarGrupo(d: Disposicion, id: string, rol: RolDeFerreteria): Disposicion {
  const borrado = d.grupos.find((g) => g.id === id);
  if (!borrado || esOriginal(id)) return d;
  const grupos = d.grupos.filter((g) => g.id !== id);
  // Si una disposición anterior no tenía el grupo de origen, reincorporalo para que la opción vuelva ahí.
  for (const original of originales) {
    if (original.claves.some((c) => borrado.claves.includes(c)) && !grupos.some((g) => g.id === original.id)) {
      grupos.push({ ...original, claves: [] });
    }
  }
  return prepararEditor({ ...d, grupos }, rol);
}

export function validarGrupos(d: Disposicion): string | null {
  if (d.grupos.length > MAX_GRUPOS) return ERROR_LIMITE;
  const nombres = new Set<string>();
  for (const grupo of d.grupos) {
    const nombre = grupo.rotulo.trim();
    if (!nombre) return 'Poné un nombre para el grupo.';
    if (nombre.length > 30) return ERROR_NOMBRE_LARGO;
    const insensible = nombre.toLowerCase();
    if (nombres.has(insensible)) return 'Ya hay un grupo con ese nombre.';
    nombres.add(insensible);
  }
  return null;
}

/** El borrador ya tiene cada clave en un solo lugar. Solo falta recortar los nombres para guardar. */
export function prepararGuardado(d: Disposicion): Disposicion {
  return { grupos: d.grupos.map((g) => ({ ...g, rotulo: g.rotulo.trim() })), ocultas: d.ocultas };
}
