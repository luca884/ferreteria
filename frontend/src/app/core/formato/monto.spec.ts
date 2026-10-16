import { formatearCotizacion, formatearMonto, MontoPipe } from './monto';

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

describe('formatearCotizacion', () => {
  it('formatea con separador de miles y dos decimales, sin signo $', () => {
    expect(formatearCotizacion(1000)).toBe('1.000,00');
    expect(formatearCotizacion(1050.5)).toBe('1.050,50');
    expect(formatearCotizacion(950)).toBe('950,00');
  });

  it('redondea a dos decimales una cotización de cuatro', () => {
    expect(formatearCotizacion(1000.5)).toBe('1.000,50');
    expect(formatearCotizacion(1234.5678)).toBe('1.234,57');
  });

  it('devuelve vacío con null o undefined', () => {
    expect(formatearCotizacion(null)).toBe('');
    expect(formatearCotizacion(undefined)).toBe('');
  });
});
