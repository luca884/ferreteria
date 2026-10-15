import datos from '../../../../../backend/src/test/resources/catalogo/casos-precio.json';
import { margenEfectivo, precioFinal } from './precio-final';

/**
 * El formulario de artículo muestra el precio en vivo: tiene que dar exactamente lo mismo que PrecioCalculator.
 * Los casos son los de backend/src/test/resources/catalogo/casos-precio.json (CasosCompartidosPrecioTest).
 */
describe('precioFinal', () => {
  it('el JSON compartido trae los casos', () => {
    expect(datos.casos.length).toBeGreaterThanOrEqual(20);
  });

  it.each(datos.casos.map((c) => [c.nombre, c] as const))('%s', (_nombre, caso) => {
    expect(
      precioFinal(caso.costo, caso.margen, caso.alicuota as Parameters<typeof precioFinal>[2], caso.multiplo),
    ).toBe(Number(caso.precio));
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

describe('margenEfectivo', () => {
  it('el propio gana; si no, el del rubro; si no, el de la ferretería (0 se respeta)', () => {
    expect(margenEfectivo(50, 35, 30)).toEqual({ margen: 50, origen: 'propio' });
    expect(margenEfectivo(null, 35, 30)).toEqual({ margen: 35, origen: 'rubro' });
    expect(margenEfectivo(null, null, 30)).toEqual({ margen: 30, origen: 'ferreteria' });
    expect(margenEfectivo(0, 35, 30)).toEqual({ margen: 0, origen: 'propio' });
    expect(margenEfectivo(null, 0, 30)).toEqual({ margen: 0, origen: 'rubro' });
  });
});
