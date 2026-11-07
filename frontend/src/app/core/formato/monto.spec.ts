import {
  formatearCosto,
  formatearCotizacion,
  formatearMonto,
  formatearRestado,
  formatearTexto,
  MontoPipe,
  RestadoPipe,
} from './monto';

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

describe('formatearCosto', () => {
  it('pesos con $ y dólares con US$, en formato argentino', () => {
    expect(formatearCosto(1234.5, 'ARS')).toBe(`$${NBSP}1.234,50`);
    expect(formatearCosto(10, 'USD')).toBe(`US$${NBSP}10,00`);
  });

  it('sin moneda se toma como pesos', () => {
    expect(formatearCosto(10, null)).toBe(`$${NBSP}10,00`);
    expect(formatearCosto(10, undefined)).toBe(`$${NBSP}10,00`);
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

// Un descuento, un gasto o un retiro restan: el mismo signo menos (U+2212) en el ticket, las hojas y la caja (bugs de la
// guía, S4). Un negativo de formatearMonto sigue con su guion.
describe('formatearRestado', () => {
  it('antepone el signo menos a un importe positivo', () => {
    expect(formatearRestado(130)).toBe(`−$${NBSP}130,00`);
    expect(formatearRestado(1234.5)).toBe(`−$${NBSP}1.234,50`);
  });

  it('en 0 no lleva signo', () => {
    expect(formatearRestado(0)).toBe(`$${NBSP}0,00`);
  });

  it('devuelve vacío con null o undefined', () => {
    expect(formatearRestado(null)).toBe('');
    expect(formatearRestado(undefined)).toBe('');
  });

  it('el pipe transforma igual y es puro', () => {
    expect(new RestadoPipe().transform(56.87)).toBe(`−$${NBSP}56,87`);
    const metadatos = (RestadoPipe as unknown as { ɵpipe: { name: string; pure: boolean } }).ɵpipe;
    expect(metadatos.name).toBe('restado');
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

describe('formatearTexto', () => {
  it('formatea un decimal exacto en pesos o dólares, con hasta los decimales pedidos', () => {
    expect(formatearTexto('1385.2625', 'ARS', 4)).toBe(`$${NBSP}1.385,2625`);
    expect(formatearTexto('880.65', 'ARS')).toBe(`$${NBSP}880,65`);
    expect(formatearTexto('8.81', 'USD')).toBe(`US$${NBSP}8,81`);
    expect(formatearTexto('10', null)).toBe(`$${NBSP}10,00`);
  });

  it('no pasa por coma flotante: 2^53 + 1 se muestra entero', () => {
    expect(formatearTexto('9007199254740993.00', 'ARS')).toBe(`$${NBSP}9.007.199.254.740.993,00`);
  });
});
