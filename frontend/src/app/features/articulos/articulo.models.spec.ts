import {
  ALICUOTAS_IVA,
  ALICUOTA_IVA_LABELS,
  Articulo,
  ArticuloRequest,
  MONEDAS,
  MONEDA_LABELS,
  TIPOS_AJUSTE_PRECIO,
  TIPO_AJUSTE_PRECIO_LABELS,
  UNIDADES,
  UNIDAD_LABELS,
  errorDeValorDeAjustePrecio,
  monedaDe,
  textoDeAjustePrecio,
} from './articulo.models';

const NBSP = '\u00a0';

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

  it('los ajustes sobre el precio final son en % o en $, con su etiqueta', () => {
    expect(TIPOS_AJUSTE_PRECIO).toEqual(['PORCENTAJE', 'MONTO']);
    expect(TIPOS_AJUSTE_PRECIO.map((t) => TIPO_AJUSTE_PRECIO_LABELS[t])).toEqual(['%', '$']);
  });

  describe('errorDeValorDeAjustePrecio (los mismos textos del backend)', () => {
    it('el valor es obligatorio', () => {
      expect(errorDeValorDeAjustePrecio('PORCENTAJE', null)).toBe('El valor del ajuste es obligatorio.');
      expect(errorDeValorDeAjustePrecio('MONTO', null)).toBe('El valor del ajuste es obligatorio.');
    });

    it('admite hasta 2 decimales', () => {
      expect(errorDeValorDeAjustePrecio('MONTO', 1.005)).toBe('El valor del ajuste admite hasta 2 decimales.');
      expect(errorDeValorDeAjustePrecio('PORCENTAJE', 0.07)).toBeNull();
    });

    it('el porcentaje va de -99,99 a 1.000, los extremos incluidos', () => {
      const mensaje = 'El porcentaje del ajuste tiene que estar entre -99,99 y 1.000.';
      expect(errorDeValorDeAjustePrecio('PORCENTAJE', -100)).toBe(mensaje);
      expect(errorDeValorDeAjustePrecio('PORCENTAJE', 1000.01)).toBe(mensaje);
      expect(errorDeValorDeAjustePrecio('PORCENTAJE', -99.99)).toBeNull();
      expect(errorDeValorDeAjustePrecio('PORCENTAJE', 1000)).toBeNull();
    });

    it('un monto no tiene rango: puede ser negativo o grande', () => {
      expect(errorDeValorDeAjustePrecio('MONTO', -5000)).toBeNull();
      expect(errorDeValorDeAjustePrecio('MONTO', 99999)).toBeNull();
    });
  });

  describe('textoDeAjustePrecio', () => {
    it('nombre, signo y valor: «Flete +10 %»', () => {
      expect(textoDeAjustePrecio({ nombre: 'Flete', tipo: 'PORCENTAJE', valor: 10 })).toBe('Flete +10 %');
      expect(textoDeAjustePrecio({ nombre: 'Promo', tipo: 'PORCENTAJE', valor: -2.5 })).toBe('Promo \u22122,5 %');
    });

    it('en pesos usa el formato de la app y, sin nombre, «Ajuste»', () => {
      expect(textoDeAjustePrecio({ nombre: null, tipo: 'MONTO', valor: -5 })).toBe(`Ajuste \u2212$${NBSP}5,00`);
      expect(textoDeAjustePrecio({ nombre: '  ', tipo: 'MONTO', valor: 1234.5 })).toBe(`Ajuste +$${NBSP}1.234,50`);
    });
  });
});
