/**
 * Aritmética de plata en enteros: precios en centavos y cantidades en milésimas. Nunca se suman floats.
 * Replica el cálculo del backend (`RenglonVenta`): total = precio x cantidad, HALF_UP a 2 decimales.
 */

export interface LineaEnteros {
  precioCentavos: number;
  cantidadMilesimas: number;
}

export function aCentavos(n: number): number {
  return Math.round(n * 100);
}

export function aMilesimas(n: number): number {
  return Math.round(n * 1000);
}

/**
 * Convierte un texto decimal ("170.00", "2,5") al entero escalado por 10^decimales, sin parseFloat.
 * Falla si el texto no es un número o si tiene más decimales significativos de los que entran.
 */
export function desdeTexto(texto: string, decimales: number): number {
  const m = /^([+-]?)(\d+)(?:[.,](\d+))?$/.exec(texto.trim());
  if (!m) throw new Error(`Número inválido: "${texto}"`);
  const [, signo, entera, fraccion = ''] = m;
  const sobrante = fraccion.slice(decimales);
  if (/[1-9]/.test(sobrante)) throw new Error(`Demasiados decimales: "${texto}"`);
  const digitos = entera + fraccion.slice(0, decimales).padEnd(decimales, '0');
  const valor = Number(digitos);
  if (!Number.isSafeInteger(valor)) throw new Error(`Número fuera de rango: "${texto}"`);
  return signo === '-' ? -valor : valor;
}

/** Total de un renglón en centavos: (precio x cantidad / 1000) con HALF_UP. */
export function totalRenglon(precioCentavos: number, cantidadMilesimas: number): number {
  const producto = precioCentavos * cantidadMilesimas;
  if (!Number.isInteger(precioCentavos) || !Number.isInteger(cantidadMilesimas) || !Number.isSafeInteger(producto)) {
    throw new Error('El importe del renglón es demasiado grande.');
  }
  return Math.floor((producto + 500) / 1000);
}

/** El total del ticket es la suma de los totales de cada renglón (nunca se recalcula sobre el total). */
export function totalTicket(renglones: readonly LineaEnteros[]): number {
  return renglones.reduce((acc, r) => acc + totalRenglon(r.precioCentavos, r.cantidadMilesimas), 0);
}
