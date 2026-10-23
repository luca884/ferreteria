import { formatearFecha, formatearFechaHora } from './fecha-hora';

describe('formatearFechaHora', () => {
  it('muestra día/mes y hora de Buenos Aires: las 11:00 UTC son las 08:00', () => {
    expect(formatearFechaHora('2026-10-16T11:00:00Z')).toBe('16/10 08:00');
  });

  it('completa con ceros el día, el mes, la hora y los minutos', () => {
    expect(formatearFechaHora('2026-01-05T08:05:00Z')).toBe('05/01 05:05');
  });

  it('la medianoche es 00:00 (nunca 24:00) y cruza al día de Buenos Aires', () => {
    expect(formatearFechaHora('2026-10-16T03:00:00Z')).toBe('16/10 00:00');
    expect(formatearFechaHora('2026-10-16T02:59:00Z')).toBe('15/10 23:59');
  });

  it('sin fecha, o con una fecha ilegible, da texto vacío', () => {
    expect(formatearFechaHora(null)).toBe('');
    expect(formatearFechaHora(undefined)).toBe('');
    expect(formatearFechaHora('no es una fecha')).toBe('');
  });
});

describe('formatearFecha', () => {
  it('pasa una fecha del servidor (AAAA-MM-DD) a dd/mm/aaaa, sin correrla de día por la zona horaria', () => {
    expect(formatearFecha('2026-05-20')).toBe('20/05/2026');
    expect(formatearFecha('2026-01-01')).toBe('01/01/2026');
  });

  it('vacío si falta o no es una fecha', () => {
    expect(formatearFecha(null)).toBe('');
    expect(formatearFecha(undefined)).toBe('');
    expect(formatearFecha('')).toBe('');
    expect(formatearFecha('20/05/2026')).toBe('');
  });
});
