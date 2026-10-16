/** Alícuotas de IVA (las mismas que el backend). */
export type Alicuota = 'IVA_0' | 'IVA_2_5' | 'IVA_5' | 'IVA_10_5' | 'IVA_21' | 'IVA_27';
type Decimal = number | string | null | undefined;

const PORCENTAJE_IVA: Record<Alicuota, string> = {
  IVA_0: '0',
  IVA_2_5: '2.5',
  IVA_5: '5',
  IVA_10_5: '10.5',
  IVA_21: '21',
  IVA_27: '27',
};

/** Un decimal exacto: valor = entero / 10^escala. */
interface Exacto {
  entero: bigint;
  escala: number;
}

function exacto(valor: Decimal): Exacto | null {
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

/** Cociente entero hacia arriba de dos positivos. */
const techo = (a: bigint, b: bigint) => (a + b - 1n) / b;

/**
 * Precio final como lo calcula PrecioCalculator.java: costo × cotización × (1 + margen/100) × (1 + IVA/100),
 * redondeado hacia arriba al múltiplo (sin múltiplo, o con uno ≤ 0, hacia arriba al centavo). Cuenta exacta con
 * BigInt, sin errores de coma flotante; la cotización (pesos por dólar) es un factor más antes del único redondeo.
 * Sin el parámetro vale 1 (artículo en pesos). `null` si falta el costo o el margen, si alguno es negativo, o si
 * la cotización no es mayor que 0.
 */
export function precioFinal(
  costo: Decimal,
  margen: Decimal,
  alicuota: Alicuota,
  multiplo: Decimal,
  cotizacion: Decimal = '1',
): number | null {
  const c = exacto(costo);
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
  return Number(centavos) / 100;
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
