import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  UNIDADES,
  UNIDAD_LABELS,
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
});
