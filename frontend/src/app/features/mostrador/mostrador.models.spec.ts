import { ArticuloMostrador, estadoStock } from './mostrador.models';

describe('estadoStock', () => {
  const con = (
    stockActual: number,
    stockMinimo: number,
  ): Pick<ArticuloMostrador, 'stockActual' | 'stockMinimo'> => ({
    stockActual,
    stockMinimo,
  });

  it('es sin-stock con stock 0 y mínimo 5', () => {
    expect(estadoStock(con(0, 5))).toBe('sin-stock');
  });

  it('es sin-stock con stock negativo', () => {
    expect(estadoStock(con(-1, 5))).toBe('sin-stock');
  });

  it('el rojo prevalece: stock 0 y mínimo 0 es sin-stock', () => {
    expect(estadoStock(con(0, 0))).toBe('sin-stock');
  });

  it('es bajo-minimo con stock 3 y mínimo 5', () => {
    expect(estadoStock(con(3, 5))).toBe('bajo-minimo');
  });

  it('con stock igual al mínimo no se marca', () => {
    expect(estadoStock(con(5, 5))).toBe('normal');
  });

  it('con stock por encima del mínimo es normal', () => {
    expect(estadoStock(con(6, 5))).toBe('normal');
  });

  it('con stock fraccionario positivo y mínimo 0 es normal', () => {
    expect(estadoStock(con(0.5, 0))).toBe('normal');
  });
});
