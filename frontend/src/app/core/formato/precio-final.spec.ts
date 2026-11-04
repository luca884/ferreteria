import datos from '../../../../../backend/src/test/resources/catalogo/casos-precio.json';
import { costoRealExacto, margenEfectivo, precioFinal, precioFinalCentavos } from './precio-final';

/**
 * El formulario de artículo muestra el precio en vivo: tiene que dar exactamente lo mismo que PrecioCalculator.
 * Los casos son los de backend/src/test/resources/catalogo/casos-precio.json (CasosCompartidosPrecioTest).
 */
describe('precioFinal', () => {
  it('el JSON compartido trae los casos', () => {
    expect(datos.casos.length).toBeGreaterThanOrEqual(20);
  });

  // Todos los casos, también los de dólares: `cotizacion` (pesos por dólar) vale 1 si el caso no la trae.
  it.each(datos.casos.map((c) => [c.nombre, c] as const))('%s', (_nombre, caso) => {
    const cotizacion = 'cotizacion' in caso ? caso.cotizacion : undefined;
    const recargos = 'recargos' in caso ? caso.recargos : undefined;
    expect(
      precioFinal(
        caso.costo,
        caso.margen,
        caso.alicuota as Parameters<typeof precioFinal>[2],
        caso.multiplo,
        cotizacion,
        recargos,
      ),
    ).toBe(Number(caso.precio));
  });

  // Los casos con recargos (CU-13) traen también el costo real, a 2 decimales.
  it.each(datos.casos.filter((c) => 'costoReal' in c).map((c) => [c.nombre, c] as const))(
    'costo real: %s',
    (_nombre, caso) => {
      const real = costoRealExacto(caso.costo, 'recargos' in caso ? caso.recargos : undefined)!;
      expect(Number(real.entero) / 10 ** real.escala).toBe(Number(caso.costoReal));
    },
  );

  it('el JSON compartido trae los casos con recargos, incluido el de la factura', () => {
    const conRecargos = datos.casos.filter((c) => 'recargos' in c);
    expect(conRecargos.length).toBeGreaterThanOrEqual(6);
    expect(conRecargos[0].costoReal).toBe('246448.70');
  });

  it('sin recargos o con la lista vacía el costo queda tal cual', () => {
    expect(costoRealExacto('100.5', null)).toEqual({ entero: 1005n, escala: 1 });
    expect(costoRealExacto('100.5', [])).toEqual({ entero: 1005n, escala: 1 });
    expect(precioFinal(100, 35, 'IVA_21', 10, 1, [])).toBe(170);
  });

  it('un porcentaje ilegible o un costo negativo no dan costo real', () => {
    expect(costoRealExacto('100', [{ porcentaje: 'abc' }])).toBeNull();
    expect(costoRealExacto('-1', [{ porcentaje: '10' }])).toBeNull();
    expect(precioFinal(100, 35, 'IVA_21', 10, 1, [{ porcentaje: null }])).toBeNull();
  });

  it('el JSON compartido trae los cuatro casos en dólares', () => {
    const enDolares = datos.casos.filter((c) => 'cotizacion' in c && !('recargos' in c));
    expect(enDolares.map((c) => c.precio)).toEqual(['15730.00', '17310.00', '14950.00', '10005.00']);
  });

  it('un artículo en dólares: 10 USD con el dólar a 1000, a 1100 y a 950', () => {
    expect(precioFinal(10, 30, 'IVA_21', 10, 1000)).toBe(15730);
    expect(precioFinal(10, 30, 'IVA_21', 10, 1100)).toBe(17310);
    expect(precioFinal(10, 30, 'IVA_21', 10, '950')).toBe(14950);
  });

  it('la cotización de cuatro decimales entra exacta, antes del único redondeo', () => {
    expect(precioFinal(10, 0, 'IVA_0', null, '1000.5000')).toBe(10005);
    expect(precioFinal(10, 0, 'IVA_0', null, '1000.5001')).toBe(10005.01);
  });

  it('sin el parámetro, o con cotización 1, es un artículo en pesos', () => {
    expect(precioFinal(100, 35, 'IVA_21', 10)).toBe(170);
    expect(precioFinal(100, 35, 'IVA_21', 10, undefined)).toBe(170);
    expect(precioFinal(100, 35, 'IVA_21', 10, 1)).toBe(170);
  });

  it('una cotización que no sirve (faltante, cero, negativa o ilegible) no da precio', () => {
    expect(precioFinal(10, 30, 'IVA_21', 10, null)).toBeNull();
    expect(precioFinal(10, 30, 'IVA_21', 10, 0)).toBeNull();
    expect(precioFinal(10, 30, 'IVA_21', 10, -1000)).toBeNull();
    expect(precioFinal(10, 30, 'IVA_21', 10, 'abc')).toBeNull();
  });

  it('acepta números como los del formulario', () => {
    expect(precioFinal(100, 35, 'IVA_21', 10)).toBe(170);
    expect(precioFinal(0.1, 0.2, 'IVA_0', null)).toBe(0.11);
  });

  it('sin costo o con datos inválidos no hay precio', () => {
    expect(precioFinal(null, 35, 'IVA_21', 10)).toBeNull();
    expect(precioFinal(-1, 35, 'IVA_21', 10)).toBeNull();
    expect(precioFinal(100, -5, 'IVA_21', 10)).toBeNull();
    expect(precioFinal(100, null, 'IVA_21', 10)).toBeNull();
  });
});

describe('precioFinalCentavos', () => {
  it('da los centavos exactos como bigint, sin pasar por Number', () => {
    expect(precioFinalCentavos(100, 35, 'IVA_21', 10)).toBe(17000n);
    expect(precioFinalCentavos(10, 30, 'IVA_21', 10, 1000)).toBe(1573000n);
    expect(precioFinalCentavos('9007199254740993', 0, 'IVA_0', null)).toBe(900719925474099300n);
  });

  it('sin precio (datos inválidos) da null, igual que precioFinal', () => {
    expect(precioFinalCentavos(null, 35, 'IVA_21', 10)).toBeNull();
    expect(precioFinalCentavos(10, 30, 'IVA_21', 10, 0)).toBeNull();
  });

  it('precioFinal sigue dando lo mismo que antes', () => {
    expect(precioFinal(100, 35, 'IVA_21', 10)).toBe(170);
  });
});

describe('margenEfectivo', () => {
  it('el propio gana; si no, el del rubro; si no, el de la ferretería (0 se respeta)', () => {
    expect(margenEfectivo(50, 35, 30)).toEqual({ margen: 50, origen: 'propio' });
    expect(margenEfectivo(null, 35, 30)).toEqual({ margen: 35, origen: 'rubro' });
    expect(margenEfectivo(null, null, 30)).toEqual({ margen: 30, origen: 'ferreteria' });
    expect(margenEfectivo(0, 35, 30)).toEqual({ margen: 0, origen: 'propio' });
    expect(margenEfectivo(null, 0, 30)).toEqual({ margen: 0, origen: 'rubro' });
  });
});
