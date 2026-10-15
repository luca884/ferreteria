import { Muestra } from './importacion.models';

/** Cuántas filas del comienzo se revisan buscando el encabezado. */
const FILAS_A_REVISAR = 15;

/** Un número como lo escribe una planilla argentina o inglesa: 1.234,56 · 1234.56 · $ 99 · 10 %. */
const NUMERO = /^[\s$]*-?\d[\d.,]*\s*%?\s*$/;

const esNumero = (celda: string) => NUMERO.test(celda);

/**
 * Número de fila (1-based) del encabezado de la lista, o null si no se encuentra. Es la primera fila
 * con al menos dos celdas llenas, todas de texto, cuya fila siguiente trae algún número (el costo).
 * Una fila de datos casi siempre tiene un número, y un título suelto tiene una sola celda.
 */
export function filaDeEncabezado(muestra: Muestra | null): number | null {
  const filas = muestra?.filas ?? [];
  for (let i = 0; i < Math.min(filas.length - 1, FILAS_A_REVISAR); i++) {
    const llenas = filas[i].celdas.map((c) => c.trim()).filter((c) => c !== '');
    if (llenas.length < 2 || llenas.some(esNumero)) continue;
    if (filas[i + 1].celdas.some((c) => esNumero(c.trim()))) return filas[i].numero;
  }
  return null;
}

/** Primera fila de datos sugerida: la que sigue al encabezado, o la 1 si no hay. */
export function primeraFilaDeDatos(muestra: Muestra | null): number {
  const encabezado = filaDeEncabezado(muestra);
  return encabezado === null ? 1 : encabezado + 1;
}
