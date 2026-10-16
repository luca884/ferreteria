import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  Articulo,
  ArticuloRequest,
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

  it('la moneda es obligatoria en el artículo y en el pedido: un PUT sin moneda no compila (W1)', () => {
    // @ts-expect-error falta `moneda`: un pedido sin ella dejaría al backend decidir y el artículo en USD no se pasa a ARS por omisión
    const sinMoneda: ArticuloRequest = {
      codigoInterno: 'TORN-001',
      codigoBarras: null,
      codigoProveedor: null,
      descripcion: 'Tornillo',
      rubroId: null,
      proveedorId: null,
      costo: 10,
      margenPorcentaje: null,
      alicuotaIva: 'IVA_21',
      unidad: 'UNIDAD',
      stockMinimo: 0,
      ubicacion: null,
      activo: true,
    };
    // @ts-expect-error falta `moneda` en lo que devuelve el backend
    const articuloSinMoneda: Articulo = {
      id: 1,
      codigoInterno: 'TORN-001',
      codigoBarras: null,
      codigoProveedor: null,
      descripcion: 'Tornillo',
      rubroId: null,
      proveedorId: null,
      costo: 10,
      margenPorcentaje: null,
      alicuotaIva: 'IVA_21',
      precioFinal: 20,
      unidad: 'UNIDAD',
      stockActual: 0,
      stockMinimo: 0,
      ubicacion: null,
      activo: true,
    };

    expect(sinMoneda.costo).toBe(10);
    expect(articuloSinMoneda.precioFinal).toBe(20);
  });

  it('monedaDe devuelve la moneda del artículo, y ARS si el backend no la manda', () => {
    expect(monedaDe({ moneda: 'USD' })).toBe('USD');
    expect(monedaDe({ moneda: 'ARS' })).toBe('ARS');
    expect(monedaDe({} as Pick<Articulo, 'moneda'>)).toBe('ARS');
    expect(monedaDe(null)).toBe('ARS');
  });
});
