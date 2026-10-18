/**
 * Aritmética de plata en enteros: precios en centavos y cantidades en milésimas. Nunca se suman floats.
 * Replica el cálculo del backend (`RenglonVenta`): total = precio x cantidad, HALF_UP a 2 decimales.
 */

export interface LineaEnteros {
  precioCentavos: number;
  cantidadMilesimas: number;
  /** Descuento propio del renglón en centésimas de punto porcentual (1000 = 10 %). Ausente es 0. */
  descuentoBp?: number;
}

/** 100 % en centésimas de punto porcentual. */
const CIEN_POR_CIENTO = 10000;

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

/** Un porcentaje de texto ("10", "10,5") a centésimas de punto porcentual (1000, 1050), sin parseFloat. */
export function aBasisPoints(porcentaje: string): number {
  return desdeTexto(porcentaje, 2);
}

/** Bruto de un renglón en centavos, antes de descuentos: (precio x cantidad / 1000) con HALF_UP. */
export function brutoRenglon(precioCentavos: number, cantidadMilesimas: number): number {
  const producto = precioCentavos * cantidadMilesimas;
  if (!Number.isInteger(precioCentavos) || !Number.isInteger(cantidadMilesimas) || !Number.isSafeInteger(producto)) {
    throw new Error('El importe del renglón es demasiado grande.');
  }
  return Math.floor((producto + 500) / 1000);
}

/** Un importe en centavos menos un descuento en centésimas de punto porcentual, con HALF_UP. */
export function conDescuento(centavos: number, descuentoBp: number): number {
  return Math.floor((centavos * (CIEN_POR_CIENTO - descuentoBp) + CIEN_POR_CIENTO / 2) / CIEN_POR_CIENTO);
}

/**
 * Total de un renglón en centavos: el bruto con el descuento del renglón y después con el global, cada uno
 * redondeado a centavos. Replica `RenglonVenta` del backend.
 */
export function totalRenglon(
  precioCentavos: number,
  cantidadMilesimas: number,
  descuentoBp = 0,
  descuentoGlobalBp = 0,
): number {
  const bruto = brutoRenglon(precioCentavos, cantidadMilesimas);
  return conDescuento(conDescuento(bruto, descuentoBp), descuentoGlobalBp);
}

/**
 * El total del ticket es la suma de los totales de cada renglón (nunca se recalcula sobre el total). El descuento
 * global se aplica a cada renglón (prorrateo), así el total sigue siendo la suma de los renglones (GDS-37).
 */
export function totalTicket(renglones: readonly LineaEnteros[], descuentoGlobalBp = 0): number {
  return renglones.reduce(
    (acc, r) => acc + totalRenglon(r.precioCentavos, r.cantidadMilesimas, r.descuentoBp ?? 0, descuentoGlobalBp),
    0,
  );
}

/** El subtotal del ticket: la suma de los brutos, antes de cualquier descuento. */
export function subtotalTicket(renglones: readonly LineaEnteros[]): number {
  return renglones.reduce((acc, r) => acc + brutoRenglon(r.precioCentavos, r.cantidadMilesimas), 0);
}

/**
 * Lo que suma una forma de pago a la parte de la venta que cubre (GDS-11): monto x % con HALF_UP, a centavos. El
 * porcentaje va en centésimas de punto porcentual (1000 = 10 %). Replica `RecargoVenta.deCobro` del backend; usa
 * BigInt porque monto x porcentaje puede pasar de 2^53.
 */
export function recargoDeCobro(montoCentavos: number, porcentajeBp: number): number {
  const producto = BigInt(montoCentavos) * BigInt(porcentajeBp);
  return Number((producto + BigInt(CIEN_POR_CIENTO / 2)) / BigInt(CIEN_POR_CIENTO));
}

/**
 * Reparte el recargo de la venta entre los renglones en proporción a su total ya descontado: cada uno se queda con
 * la parte entera en centavos y los centavos que sobran van de a uno a los restos mayores (a igual resto, al
 * primero). Ninguno da negativo y la suma es exacta. Replica `RecargoVenta.repartir` del backend.
 */
export function repartirRecargo(totalesCentavos: readonly number[], recargoCentavos: number): number[] {
  if (recargoCentavos < 0) throw new Error('El recargo no puede ser negativo.');
  if (recargoCentavos === 0) return totalesCentavos.map(() => 0);
  const suma = totalesCentavos.reduce((a, t) => a + BigInt(t), 0n);
  if (suma === 0n) throw new Error('No hay un total sobre el que repartir el recargo.');
  const recargo = BigInt(recargoCentavos);
  const partes = totalesCentavos.map((t) => (recargo * BigInt(t)) / suma);
  const restos = totalesCentavos.map((t) => (recargo * BigInt(t)) % suma);
  const sobrantes = Number(recargo - partes.reduce((a, p) => a + p, 0n));
  const porResto = totalesCentavos
    .map((_, i) => i)
    .sort((a, b) => (restos[a] === restos[b] ? a - b : restos[a] > restos[b] ? -1 : 1));
  for (let k = 0; k < sobrantes; k++) partes[porResto[k]] += 1n;
  return partes.map(Number);
}
