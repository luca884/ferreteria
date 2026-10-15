import { formatearMonto, MontoPipe } from './monto';

const NBSP = ' ';

describe('formatearMonto', () => {
  it('formatea con signo $, separador de miles y dos decimales', () => {
    expect(formatearMonto(1234.5)).toBe(`$${NBSP}1.234,50`);
  });

  it('formatea un entero con dos decimales', () => {
    expect(formatearMonto(180)).toBe(`$${NBSP}180,00`);
  });

  it('formatea cero', () => {
    expect(formatearMonto(0)).toBe(`$${NBSP}0,00`);
  });

  it('formatea un negativo', () => {
    expect(formatearMonto(-1234.5)).toBe(`-$${NBSP}1.234,50`);
  });

  it('devuelve vacío con null o undefined', () => {
    expect(formatearMonto(null)).toBe('');
    expect(formatearMonto(undefined)).toBe('');
  });
});

describe('MontoPipe', () => {
  it('transforma igual que formatearMonto', () => {
    const pipe = new MontoPipe();
    expect(pipe.transform(1234.5)).toBe(`$${NBSP}1.234,50`);
    expect(pipe.transform(null)).toBe('');
  });

  it('es puro', () => {
    const metadatos = (MontoPipe as unknown as { ɵpipe: { pure: boolean } }).ɵpipe;
    expect(metadatos.pure).toBe(true);
  });
});
