/** Alícuotas de IVA (las mismas que el backend). */
export type Alicuota = 'IVA_0' | 'IVA_2_5' | 'IVA_5' | 'IVA_10_5' | 'IVA_21' | 'IVA_27';
export type Decimal = number | string | null | undefined;

export const PORCENTAJE_IVA: Record<Alicuota, string> = {
  IVA_0: '0',
  IVA_2_5: '2.5',
  IVA_5: '5',
  IVA_10_5: '10.5',
  IVA_21: '21',
  IVA_27: '27',
};

/** Un recargo con nombre sobre el costo (CU-13): solo importa el porcentaje para la cuenta. */
export interface RecargoDeCosto {
  nombre?: string;
  porcentaje: Decimal;
}

/** Un decimal exacto: valor = entero / 10^escala. */
export interface Exacto {
  entero: bigint;
  escala: number;
}

export function exacto(valor: Decimal): Exacto | null {
  if (valor === null || valor === undefined || valor === '') return null;
  let texto = typeof valor === 'number' ? String(valor) : valor.trim();
  if (typeof valor === 'number') {
    if (!Number.isFinite(valor)) return null;
    if (/e/i.test(texto)) texto = valor.toFixed(10);
  }
  const partes = /^(-?)(\d*)(?:\.(\d*))?$/.exec(texto);
  if (!partes || (partes[2] === '' && !partes[3])) return null;
  const decimales = partes[3] ?? '';
  return { entero: BigInt(`${partes[1]}${partes[2] || '0'}${decimales}`), escala: decimales.length };
}

const diez = (n: number) => 10n ** BigInt(n);

/** Cociente entero de dos positivos, la mitad hacia arriba (HALF_UP). */
const mitadArriba = (a: bigint, b: bigint) => (2n * a + b) / (2n * b);

/**
 * El costo real como lo calcula RecargosDeCosto.java: costo × (1 + Σ % / 100), a 2 decimales HALF_UP. Sin recargos
 * devuelve el costo tal cual. `null` si el costo o algún porcentaje no se pueden leer, o si el costo es negativo.
 */
export function costoRealExacto(costo: Decimal, recargos: readonly RecargoDeCosto[] | null | undefined): Exacto | null {
  const c = exacto(costo);
  if (!c || c.entero < 0n) return null;
  if (!recargos || recargos.length === 0) return c;
  const porcentajes = recargos.map((r) => exacto(r.porcentaje));
  if (porcentajes.some((p) => p === null)) return null;
  const escala = Math.max(...porcentajes.map((p) => p!.escala));
  const suma = porcentajes.reduce((acc, p) => acc + p!.entero * diez(escala - p!.escala), 0n);
  // costo real en centavos = costo × (100 + Σ%) / 100 × 100, con todo exacto hasta el único redondeo.
  const centavos = mitadArriba(c.entero * (100n * diez(escala) + suma) * 100n, diez(c.escala + escala) * 100n);
  return { entero: centavos, escala: 2 };
}

/** Cociente entero hacia arriba de dos positivos. */
const techo = (a: bigint, b: bigint) => (a + b - 1n) / b;

/**
 * Precio final como lo calcula PrecioCalculator.java: costo × cotización × (1 + margen/100) × (1 + IVA/100),
 * redondeado hacia arriba al múltiplo (sin múltiplo, o con uno ≤ 0, hacia arriba al centavo). Cuenta exacta con
 * BigInt, sin errores de coma flotante; la cotización (pesos por dólar) es un factor más antes del único redondeo.
 * Sin el parámetro vale 1 (artículo en pesos). `null` si falta el costo o el margen, si alguno es negativo, o si
 * la cotización no es mayor que 0. Con `recargos` (CU-13) parte del costo real: costo × (1 + Σ % / 100) a 2 decimales
 * HALF_UP; sin ellos, o con una lista vacía, da lo mismo que antes.
 */
export function precioFinal(
  costo: Decimal,
  margen: Decimal,
  alicuota: Alicuota,
  multiplo: Decimal,
  cotizacion: Decimal = '1',
  recargos?: readonly RecargoDeCosto[] | null,
): number | null {
  const centavos = precioFinalCentavos(costo, margen, alicuota, multiplo, cotizacion, recargos);
  return centavos === null ? null : Number(centavos) / 100;
}

/**
 * Lo mismo que `precioFinal`, pero en centavos exactos como bigint, sin pasar por Number: de acá se formatea el
 * precio cuando importa que no haya coma flotante.
 */
export function precioFinalCentavos(
  costo: Decimal,
  margen: Decimal,
  alicuota: Alicuota,
  multiplo: Decimal,
  cotizacion: Decimal = '1',
  recargos?: readonly RecargoDeCosto[] | null,
): bigint | null {
  // El cálculo parte del costo real (CU-13): el costo con sus recargos.
  const c = costoRealExacto(costo, recargos);
  const m = exacto(margen);
  const iva = exacto(PORCENTAJE_IVA[alicuota]);
  const cot = exacto(cotizacion);
  if (!c || !m || !iva || !cot || c.entero < 0n || m.entero < 0n || cot.entero <= 0n) return null;

  // bruto = numerador / denominador, en pesos.
  const numerador =
    c.entero * cot.entero * (100n * diez(m.escala) + m.entero) * (100n * diez(iva.escala) + iva.entero);
  const denominador = diez(c.escala + cot.escala + m.escala + iva.escala) * 10_000n;

  const mul = exacto(multiplo);
  let centavos: bigint;
  if (!mul || mul.entero <= 0n) {
    centavos = techo(numerador * 100n, denominador);
  } else {
    const veces = techo(numerador * diez(mul.escala), denominador * mul.entero);
    // veces × múltiplo, en centavos; un múltiplo con más de 2 decimales sube al centavo, como setScale(2, CEILING).
    centavos = techo(veces * mul.entero * 100n, diez(mul.escala));
  }
  return centavos;
}

/** El margen que usa el precio: el propio; si no hay, el del rubro; si no, el de la ferretería. 0 se respeta. */
export function margenEfectivo(
  propio: number | null,
  rubro: number | null,
  ferreteria: number,
): { margen: number; origen: 'propio' | 'rubro' | 'ferreteria' } {
  if (propio !== null) return { margen: propio, origen: 'propio' };
  if (rubro !== null) return { margen: rubro, origen: 'rubro' };
  return { margen: ferreteria, origen: 'ferreteria' };
}
