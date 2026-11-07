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
 * (el costo con sus recargos, por la cotización) supera el precio sin IVA. Se compara en forma exacta, costo en pesos
 * × (100 + IVA) contra precio × 100, así que margen 0 no avisa. En el texto, el costo en pesos y el precio sin IVA van
 * a 2 decimales HALF_UP. Los casos son los de casos-margen-negativo.json (CasosCompartidosMargenNegativoTest).
 * `null` si no avisa o si falta un dato para la cuenta. Sin `cotizacion` vale 1 (artículo en pesos). Quien lo llama
 * decide si el artículo tiene precio manual.
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
  // costo en pesos × (100 + IVA) contra precio × 100, todo llevado a enteros.
  const izquierda = costoEnPesos * conIva * diez(p.escala);
  const derecha = p.entero * 100n * diez(escalaCosto + iva.escala);
  if (izquierda <= derecha) return null;

  const costoCentavos = mitadArriba(costoEnPesos * 100n, diez(escalaCosto));
  // precio sin IVA = precio × 100 / (100 + IVA), en centavos.
  const sinIvaCentavos = mitadArriba(p.entero * 100n * 100n * diez(iva.escala), diez(p.escala) * conIva);
  return (
    `Margen negativo: el costo (${enPesos(costoCentavos)}) supera el precio sin IVA ` +
    `(${enPesos(sinIvaCentavos)}). Revisá el precio manual.`
  );
}
