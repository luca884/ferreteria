import { avisoSinCosto, errorDeRango, hoyEnBuenosAires, textoPeriodo } from './reportes.models';

describe('reportes.models', () => {
  it('avisa las ventas sin costo en singular y plural, y nada si no hay', () => {
    expect(avisoSinCosto(0, 'ventas')).toBeNull();
    expect(avisoSinCosto(1, 'ventas')).toBe('1 venta sin costo registrado: la ganancia no la incluye.');
    expect(avisoSinCosto(3, 'ventas')).toBe('3 ventas sin costo registrado: la ganancia no las incluye.');
    expect(avisoSinCosto(1, 'renglones')).toBe('1 renglón sin costo registrado: la ganancia no lo incluye.');
    expect(avisoSinCosto(2, 'renglones')).toBe('2 renglones sin costo registrado: la ganancia no los incluye.');
  });

  it('muestra el día como dd/mm/aaaa y el mes como mm/aaaa', () => {
    expect(textoPeriodo('2026-10-22')).toBe('22/10/2026');
    expect(textoPeriodo('2026-09')).toBe('09/2026');
  });

  it('hoy es la fecha de Buenos Aires, no la UTC', () => {
    // 02:30 UTC del 23 es 23:30 del 22 en Buenos Aires.
    expect(hoyEnBuenosAires(new Date('2026-10-23T02:30:00Z'))).toBe('2026-10-22');
    expect(hoyEnBuenosAires(new Date('2026-10-23T03:30:00Z'))).toBe('2026-10-23');
  });

  it('frena «Hasta» antes de «Desde», fechas vacías y más de 366 días con el texto del backend', () => {
    expect(errorDeRango('2026-10-22', '2026-10-22')).toBeNull();
    expect(errorDeRango('2026-10-22', '2026-10-21')).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
    expect(errorDeRango('', '2026-10-21')).toBe('Elegí las dos fechas.');
    // Del 22/10/2025 al 23/10/2026 son 367 días contando los dos extremos; del 23/10/2025, 366.
    expect(errorDeRango('2025-10-22', '2026-10-23')).toBe('El reporte admite hasta 366 días.');
    expect(errorDeRango('2025-10-23', '2026-10-23')).toBeNull();
  });
});
