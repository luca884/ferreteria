import { barras, code128, codigoParaEtiqueta, ean13, esEan13 } from './codigo-barras';

// Vectores de referencia sacados de python-barcode 0.16 (implementación independiente).
const EAN_4006381333931 =
  '10100011010100111010111101111010001001011001101010100001010000101000010111010010000101100110101';
const CODE128_TOR_001 =
  '1101001000011011100010100011101101100010111010011011100100111011001001110110010011100110111001001101100011101011';
const CODE128_12345678 = '1101001110010110011100100010110001110001011011000010100100011101101100011101011';
const CODE128_7790001 =
  '110100111001111011101011011110110110110011001011110111010011100110111011011101100011101011';

describe('esEan13', () => {
  it('acepta 13 dígitos con el dígito verificador correcto', () => {
    expect(esEan13('4006381333931')).toBe(true);
    expect(esEan13('7790001000019')).toBe(true);
  });

  it('rechaza el verificador equivocado, otro largo o caracteres que no son dígitos', () => {
    expect(esEan13('4006381333932')).toBe(false);
    expect(esEan13('400638133393')).toBe(false);
    expect(esEan13('40063813339310')).toBe(false);
    expect(esEan13('40063813339 1')).toBe(false);
  });
});

describe('ean13', () => {
  it('arma los 95 módulos del EAN-13', () => {
    expect(ean13('4006381333931')).toBe(EAN_4006381333931);
  });
});

describe('code128', () => {
  it('texto con letras va en el juego B', () => {
    expect(code128('TOR-001')).toBe(CODE128_TOR_001);
  });

  it('solo dígitos en cantidad par va en el juego C', () => {
    expect(code128('12345678')).toBe(CODE128_12345678);
  });

  it('dígitos en cantidad impar: pares en C y el último dígito en B', () => {
    expect(code128('7790001')).toBe(CODE128_7790001);
  });

  it('cada símbolo ocupa 11 módulos y el de parada 13', () => {
    // Inicio + datos + verificador + parada: 'AB12345' es A, B, 1 (en B), cambio a C, 23, 45.
    expect(code128('AB12345')!.length).toBe(11 * 8 + 13);
  });

  it('un carácter fuera del ASCII imprimible no se puede codificar', () => {
    expect(code128('CAÑO-1')).toBeNull();
    expect(code128('')).toBeNull();
  });
});

describe('codigoParaEtiqueta', () => {
  it('un código de barras EAN-13 válido sale como EAN-13', () => {
    expect(codigoParaEtiqueta({ codigoBarras: '4006381333931', codigoInterno: 'TOR-001' })).toEqual({
      simbologia: 'EAN-13',
      texto: '4006381333931',
      modulos: EAN_4006381333931,
    });
  });

  it('un código de barras que no es EAN-13 sale en Code128', () => {
    expect(codigoParaEtiqueta({ codigoBarras: '7790001', codigoInterno: 'TOR-001' })).toEqual({
      simbologia: 'Code128',
      texto: '7790001',
      modulos: CODE128_7790001,
    });
  });

  it('sin código de barras va el código interno en Code128', () => {
    const esperado = { simbologia: 'Code128', texto: 'TOR-001', modulos: CODE128_TOR_001 };
    expect(codigoParaEtiqueta({ codigoBarras: null, codigoInterno: 'TOR-001' })).toEqual(esperado);
    expect(codigoParaEtiqueta({ codigoBarras: '  ', codigoInterno: 'TOR-001' })).toEqual(esperado);
  });

  it('si ninguno se puede codificar no hay código', () => {
    expect(codigoParaEtiqueta({ codigoBarras: null, codigoInterno: 'CAÑO-1' })).toBeNull();
  });
});

describe('barras', () => {
  it('convierte los módulos en barras con posición y ancho', () => {
    expect(barras('1101001')).toEqual([
      { x: 0, ancho: 2 },
      { x: 3, ancho: 1 },
      { x: 6, ancho: 1 },
    ]);
  });
});
