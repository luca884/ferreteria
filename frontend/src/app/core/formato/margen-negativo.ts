import { formatearTexto } from './monto';
import { Alicuota, Decimal, PORCENTAJE_IVA, RecargoDeCosto, costoRealExacto, exacto } from './precio-final';

const diez = (n: number) => 10n ** BigInt(n);

/** Cociente entero de dos positivos, la mitad hacia arriba (HALF_UP). */
const mitadArriba = (a: bigint, b: bigint) => (2n * a + b) / (2n * b);

/** Centavos exactos como texto decimal («82645» es «826.45»), para formatear sin pasar por Number. */
const enPesos = (centavos: bigint) =>
  formatearTexto(`${centavos / 100n}.${(centavos % 100n).toString().padStart(2, '0')}`, 'ARS');

/**
 * El aviso de margen negativo de un precio manual (CU-14 punto 2), como MargenNegativo.java: el costo real en pesos
 * (el costo con sus recargos, por la cotización) supera el precio sin IVA. Se comparan los dos importes del texto, el
 * costo en pesos y el precio sin IVA, cada uno a 2 decimales HALF_UP: si se leen iguales no avisa (margen 0 tampoco).
 * Los casos son los de casos-margen-negativo.json (CasosCompartidosMargenNegativoTest). `null` si no avisa o si falta
 * un dato para la cuenta. Sin `cotizacion` vale 1 (artículo en pesos). Quien lo llama decide si el artículo tiene
 * precio manual.
 */
export function avisoMargenNegativo(
  costo: Decimal,
  recargos: readonly RecargoDeCosto[] | null | undefined,
  cotizacion: Decimal,
  alicuota: Alicuota,
  precio: Decimal,
): string | null {
  const c = costoRealExacto(costo, recargos);
  const cot = exacto(cotizacion === undefined ? '1' : cotizacion);
  const iva = exacto(PORCENTAJE_IVA[alicuota]);
  const p = exacto(precio);
  if (!c || !cot || !iva || !p || cot.entero <= 0n || p.entero < 0n) return null;

  // costo en pesos = c × cot, con escala c.escala + cot.escala; 100 + IVA = conIva / 10^iva.escala.
  const costoEnPesos = c.entero * cot.entero;
  const escalaCosto = c.escala + cot.escala;
  const conIva = 100n * diez(iva.escala) + iva.entero;
  // Los dos importes del texto, en centavos HALF_UP: el costo en pesos y el precio sin IVA (precio × 100 / (100 + IVA)).
  const costoCentavos = mitadArriba(costoEnPesos * 100n, diez(escalaCosto));
  const sinIvaCentavos = mitadArriba(p.entero * 100n * 100n * diez(iva.escala), diez(p.escala) * conIva);
  if (costoCentavos <= sinIvaCentavos) return null;
  return (
    `Margen negativo: el costo (${enPesos(costoCentavos)}) supera el precio sin IVA ` +
    `(${enPesos(sinIvaCentavos)}). Revisá el precio manual.`
  );
}
