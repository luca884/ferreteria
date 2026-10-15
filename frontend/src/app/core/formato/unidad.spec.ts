import { cantidadConUnidad } from './unidad';

describe('cantidadConUnidad', () => {
  it.each([
    [1, 'UNIDAD', '1 unidad'],
    [497, 'UNIDAD', '497 unidades'],
    [0, 'UNIDAD', '0 unidades'],
    [-3, 'UNIDAD', '-3 unidades'],
    [-1, 'UNIDAD', '-1 unidad'],
    [1, 'METRO', '1 metro'],
    [145.5, 'METRO', '145,5 metros'],
    [0.5, 'METRO', '0,5 metros'],
    [1, 'KILO', '1 kilo'],
    [2, 'KILO', '2 kilos'],
    [2.125, 'KILO', '2,125 kilos'],
    [1, 'LITRO', '1 litro'],
    [20, 'LITRO', '20 litros'],
  ] as const)('%s %s → «%s»', (cantidad, unidad, esperado) => {
    expect(cantidadConUnidad(cantidad, unidad)).toBe(esperado);
  });

  it('agrupa los miles con punto y no muestra más de 3 decimales', () => {
    expect(cantidadConUnidad(12345, 'UNIDAD')).toBe('12.345 unidades');
    expect(cantidadConUnidad(1.0004, 'METRO')).toBe('1 metro');
  });
});
