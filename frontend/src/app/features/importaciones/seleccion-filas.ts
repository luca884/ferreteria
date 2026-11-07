import {
  AltaPlaneada,
  CambioPlaneado,
  Conteos,
  FilaElegida,
  VistaPrevia,
} from './importacion.models';

/**
 * La selección de filas de la vista previa, en funciones puras (R-123, R-125, C-118). Una fila se identifica por su
 * hoja y su número de fila, como lo hace el backend; la selección es el conjunto de claves `"hoja:fila"` de las filas
 * marcadas (por inclusión: una fila que apareciera después nunca se aplica sin que nadie la vea).
 */

/** Los tipos de fila que se pueden elegir; las de error no (no hay nada que aplicar). */
export const TIPOS_ELEGIBLES = [
  'suben',
  'bajan',
  'cambianMoneda',
  'corrigen',
  'nuevos',
  'sinCambio',
] as const;
export type TipoElegible = (typeof TIPOS_ELEGIBLES)[number];
export type TipoFila = TipoElegible | 'conError';

/** Una fila de la vista previa, con el tipo en que quedó clasificada y el detalle según el tipo. */
export interface FilaDeVista {
  /** `"hoja:fila"` */
  clave: string;
  hoja: number;
  fila: number;
  tipo: TipoFila;
  codigo: string;
  descripcion: string;
  /** suben, bajan, cambian de moneda y corrigen. */
  cambio: CambioPlaneado | null;
  /** nuevos. */
  alta: AltaPlaneada | null;
  /** con error: el motivo. */
  mensaje: string | null;
}

/** Los marcados. Nunca se modifica: cada acción devuelve una selección nueva. */
export type Seleccion = ReadonlySet<string>;

export type FiltroDeFilas = { hoja?: number; tipo?: TipoFila };

export function claveDe(hoja: number, fila: number): string {
  return `${hoja}:${fila}`;
}

/** Une las listas de la respuesta en filas con su tipo, ordenadas por hoja y fila. */
export function unirFilas(vistaPrevia: VistaPrevia): FilaDeVista[] {
  const filas: FilaDeVista[] = [];
  const deCambio = (cambio: CambioPlaneado, tipo: TipoFila) =>
    filas.push({
      clave: claveDe(cambio.hoja, cambio.fila),
      hoja: cambio.hoja,
      fila: cambio.fila,
      tipo,
      codigo: cambio.codigoProveedor,
      descripcion: cambio.descripcion,
      cambio,
      alta: null,
      mensaje: null,
    });
  for (const c of vistaPrevia.cambios) deCambio(c, c.costoNuevo > c.costoAnterior ? 'suben' : 'bajan');
  for (const c of vistaPrevia.cambiosDeMoneda) deCambio(c, 'cambianMoneda');
  for (const c of vistaPrevia.correcciones) deCambio(c, 'corrigen');
  for (const alta of vistaPrevia.nuevos) {
    filas.push({
      clave: claveDe(alta.hoja, alta.fila),
      hoja: alta.hoja,
      fila: alta.fila,
      tipo: 'nuevos',
      codigo: alta.codigo,
      descripcion: alta.descripcion,
      cambio: null,
      alta,
      mensaje: null,
    });
  }
  for (const igual of vistaPrevia.sinCambio) {
    filas.push({
      clave: claveDe(igual.hoja, igual.fila),
      hoja: igual.hoja,
      fila: igual.fila,
      tipo: 'sinCambio',
      codigo: igual.codigo,
      descripcion: igual.descripcion,
      cambio: null,
      alta: null,
      mensaje: null,
    });
  }
  for (const error of vistaPrevia.errores) {
    const hoja = error.hoja ?? 0;
    const fila = error.fila ?? 0;
    filas.push({
      clave: claveDe(hoja, fila),
      hoja,
      fila,
      tipo: 'conError',
      codigo: error.codigo ?? '',
      descripcion: error.descripcion ?? '',
      cambio: null,
      alta: null,
      mensaje: error.mensaje,
    });
  }
  return filas.sort((a, b) => a.hoja - b.hoja || a.fila - b.fila);
}

export function esElegible(fila: FilaDeVista): boolean {
  return fila.tipo !== 'conError';
}

/** Las que cumplen el filtro (hoja y/o tipo); sin filtro, todas. */
export function filtrarFilas(filas: readonly FilaDeVista[], filtro: FiltroDeFilas): FilaDeVista[] {
  return filas.filter(
    (f) =>
      (filtro.hoja === undefined || f.hoja === filtro.hoja) &&
      (filtro.tipo === undefined || f.tipo === filtro.tipo),
  );
}

/** Al abrir la vista previa están marcadas todas las filas elegibles. */
export function seleccionInicial(filas: readonly FilaDeVista[]): Seleccion {
  return marcarTodas(filas);
}

export function marcarTodas(filas: readonly FilaDeVista[]): Seleccion {
  return new Set(filas.filter(esElegible).map((f) => f.clave));
}

export function marcarNinguna(): Seleccion {
  return new Set<string>();
}

/** Marca o desmarca una fila de a una. Una fila con error no se puede marcar: queda como estaba, sin fallar. */
export function marcarFila(seleccion: Seleccion, fila: FilaDeVista, marcada: boolean): Seleccion {
  if (!esElegible(fila)) return seleccion;
  const nueva = new Set(seleccion);
  if (marcada) nueva.add(fila.clave);
  else nueva.delete(fila.clave);
  return nueva;
}

/** Marca o desmarca las filas elegibles que cumplen el filtro; el resto de la selección no cambia. */
export function marcarFiltradas(
  seleccion: Seleccion,
  filas: readonly FilaDeVista[],
  filtro: FiltroDeFilas,
  marcada: boolean,
): Seleccion {
  const nueva = new Set(seleccion);
  for (const fila of filtrarFilas(filas, filtro)) {
    if (!esElegible(fila)) continue;
    if (marcada) nueva.add(fila.clave);
    else nueva.delete(fila.clave);
  }
  return nueva;
}

/** Marca o desmarca una hoja entera. */
export function marcarHoja(
  seleccion: Seleccion,
  filas: readonly FilaDeVista[],
  hoja: number,
  marcada: boolean,
): Seleccion {
  return marcarFiltradas(seleccion, filas, { hoja }, marcada);
}

/** Marca o desmarca un tipo entero, en todas las hojas. */
export function marcarTipo(
  seleccion: Seleccion,
  filas: readonly FilaDeVista[],
  tipo: TipoFila,
  marcada: boolean,
): Seleccion {
  return marcarFiltradas(seleccion, filas, { tipo }, marcada);
}

/** Cuánto de un grupo de filas está marcado; un grupo sin filas elegibles figura como «ninguna». */
export function estadoDeSeleccion(
  seleccion: Seleccion,
  grupo: readonly FilaDeVista[],
): 'todas' | 'algunas' | 'ninguna' {
  let elegibles = 0;
  let marcadas = 0;
  for (const fila of grupo) {
    if (!esElegible(fila)) continue;
    elegibles++;
    if (seleccion.has(fila.clave)) marcadas++;
  }
  if (marcadas === 0) return 'ninguna';
  return marcadas === elegibles ? 'todas' : 'algunas';
}

/** Lo que viaja en aplicar: las filas marcadas por su hoja y su fila, en el orden del archivo. */
export function filasElegidas(seleccion: Seleccion, filas: readonly FilaDeVista[]): FilaElegida[] {
  return filas
    .filter((f) => esElegible(f) && seleccion.has(f.clave))
    .map((f) => ({ hoja: f.hoja, fila: f.fila }));
}

/**
 * Cuántas de las filas elegidas se aplican de verdad. Es la regla del backend (`SeleccionDeFilas.cantidadDeAplicadas`):
 * con «Crear los artículos nuevos» apagado, las filas nuevas no cuentan. Si da 0, el backend responde 400.
 */
export function cantidadDeAplicadas(
  seleccion: Seleccion,
  filas: readonly FilaDeVista[],
  crearNuevos: boolean,
): number {
  return filasElegidas(seleccion, filas).length - (crearNuevos ? 0 : elegidasDelTipo(seleccion, filas, 'nuevos'));
}

/** Cuántas filas nuevas están elegidas: con «Crear los artículos nuevos» apagado, son las que no se crean. */
export function nuevasElegidas(seleccion: Seleccion, filas: readonly FilaDeVista[]): number {
  return elegidasDelTipo(seleccion, filas, 'nuevos');
}

function elegidasDelTipo(seleccion: Seleccion, filas: readonly FilaDeVista[], tipo: TipoFila): number {
  return filas.filter((f) => f.tipo === tipo && esElegible(f) && seleccion.has(f.clave)).length;
}

/**
 * La selección para una vista previa nueva (después de un 409) a partir de la que había: lo que estaba marcado o
 * desmarcado y sigue estando conserva su estado, y lo que aparece recién entra marcado, como en la primera vista
 * previa. `perdidas` cuenta las filas que estaban elegidas y ya no se pueden elegir (no están o pasaron a error).
 */
export function conservarSeleccion(
  anterior: Seleccion,
  filasAnteriores: readonly FilaDeVista[],
  filasNuevas: readonly FilaDeVista[],
): { seleccion: Seleccion; perdidas: number } {
  const conocidas = new Set(filasAnteriores.filter(esElegible).map((f) => f.clave));
  const elegiblesNuevas = new Set(filasNuevas.filter(esElegible).map((f) => f.clave));
  const seleccion = new Set<string>();
  for (const clave of elegiblesNuevas) {
    if (!conocidas.has(clave) || anterior.has(clave)) seleccion.add(clave);
  }
  let perdidas = 0;
  for (const clave of anterior) {
    if (!elegiblesNuevas.has(clave)) perdidas++;
  }
  return { seleccion, perdidas };
}

/**
 * Los contadores que el cliente confirma al aplicar (C-109): los de lo elegido —suben, bajan, cambian de moneda,
 * corrigen, nuevos y sin cambio— y los del archivo —no están, con error y salteadas—, que no dependen de la selección.
 */
export function conteosConSeleccion(
  archivo: Conteos,
  filas: readonly FilaDeVista[],
  seleccion: Seleccion,
): Conteos {
  const elegidos: Record<TipoElegible, number> = {
    suben: 0,
    bajan: 0,
    cambianMoneda: 0,
    corrigen: 0,
    nuevos: 0,
    sinCambio: 0,
  };
  for (const fila of filas) {
    if (esElegible(fila) && seleccion.has(fila.clave)) elegidos[fila.tipo as TipoElegible]++;
  }
  return {
    ...elegidos,
    noEstan: archivo.noEstan,
    conError: archivo.conError,
    salteadas: archivo.salteadas,
  };
}
