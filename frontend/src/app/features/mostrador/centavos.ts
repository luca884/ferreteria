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
