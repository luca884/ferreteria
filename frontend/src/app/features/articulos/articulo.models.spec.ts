import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  Articulo,
  MONEDAS,
  MONEDA_LABELS,
  UNIDADES,
  UNIDAD_LABELS,
  monedaDe,
} from './articulo.models';

describe('articulo.models', () => {
  it('las alícuotas tienen la etiqueta legible en porcentaje', () => {
    expect(ALICUOTAS_IVA).toEqual(['IVA_0', 'IVA_2_5', 'IVA_5', 'IVA_10_5', 'IVA_21', 'IVA_27']);
    expect(ALICUOTAS_IVA.map((a) => ALICUOTA_IVA_LABELS[a])).toEqual([
      '0 %',
      '2,5 %',
      '5 %',
      '10,5 %',
      '21 %',
      '27 %',
    ]);
  });

  it('las unidades tienen la etiqueta legible', () => {
    expect(UNIDADES).toEqual(['UNIDAD', 'METRO', 'KILO', 'LITRO']);
    expect(UNIDADES.map((u) => UNIDAD_LABELS[u])).toEqual(['Unidad', 'Metro', 'Kilo', 'Litro']);
  });

  it('las monedas son ARS y USD, con su etiqueta; el euro y el real no son monedas de artículo', () => {
    expect(MONEDAS).toEqual(['ARS', 'USD']);
    expect(MONEDAS.map((m) => MONEDA_LABELS[m])).toEqual(['Pesos (ARS)', 'Dólares (USD)']);
  });

  it('monedaDe devuelve la moneda del artículo, y ARS si el backend no la manda', () => {
    expect(monedaDe({ moneda: 'USD' })).toBe('USD');
    expect(monedaDe({ moneda: 'ARS' })).toBe('ARS');
    expect(monedaDe({} as Pick<Articulo, 'moneda'>)).toBe('ARS');
    expect(monedaDe(null)).toBe('ARS');
  });
});
