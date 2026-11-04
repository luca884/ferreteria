import { describe, expect, it } from 'vitest';
import { TipoOferta, precioDeOferta } from './articulo.models';

/** Referencia exacta (BigInt) de Oferta.precioSobre del backend: centavos, % en centésimos, CEILING al centavo. */
function referencia(normalCents: bigint, tipo: TipoOferta, valorCents: bigint): number | null {
  let c: bigint;
  if (tipo === 'PRECIO_FIJO') c = valorCents;
  else if (tipo === 'DESCUENTO_MONTO') c = normalCents - valorCents;
  else {
    const num = normalCents * (10000n - valorCents);
    c = num / 10000n + (num % 10000n > 0n ? 1n : 0n);
  }
  return c > 0n && c < normalCents ? Number(c) / 100 : null;
}

describe('ofertas (regresión del verificador): precioDeOferta vs backend', () => {
  it('coincide en bordes y 20000 casos al azar', () => {
    let seed = 44;
    const rnd = (n: number) => {
      seed = (seed * 1103515245 + 12345) % 2147483648;
      return seed % n;
    };
    const difs: string[] = [];
    const tipos: TipoOferta[] = ['PRECIO_FIJO', 'DESCUENTO_PORCENTAJE', 'DESCUENTO_MONTO'];
    const casos: [number, TipoOferta, number][] = [];
    for (const p of [1, 2, 3, 99, 100, 101, 333, 999, 1000, 1355200, 9999999, 123456789, 99999999999]) {
      for (const v of [1, 50, 99, 100, 3333, 5000, 6667, 9999]) casos.push([p, 'DESCUENTO_PORCENTAJE', v]);
    }
    for (let i = 0; i < 20000; i++) {
      const t = tipos[rnd(3)];
      const p = 1 + rnd(100000000);
      const v = t === 'DESCUENTO_PORCENTAJE' ? 1 + rnd(9999) : 1 + rnd(100000000);
      casos.push([p, t, v]);
    }
    for (const [p, t, v] of casos) {
      const esperado = referencia(BigInt(p), t, BigInt(v));
      const obtenido = precioDeOferta(p / 100, t, v / 100);
      if (esperado !== obtenido) difs.push(`${p / 100} ${t} ${v / 100}: ${obtenido} vs ${esperado}`);
    }
    expect(difs.slice(0, 10)).toEqual([]);
  });
});
