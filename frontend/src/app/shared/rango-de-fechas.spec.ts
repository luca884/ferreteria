import { HASTA_ANTERIOR_A_DESDE, rangoAlReves } from './rango-de-fechas';

describe('rangoAlReves (bugs-relevamiento-celular, S4)', () => {
  it('«Hasta» anterior a «Desde», también entre meses y años', () => {
    expect(rangoAlReves('2026-10-22', '2026-10-21')).toBe(true);
    expect(rangoAlReves('2027-01-01', '2026-12-31')).toBe(true);
  });

  it('el mismo día, un rango en orden (también cruzando el año), una sola fecha o ninguna: vale', () => {
    expect(rangoAlReves('2026-10-22', '2026-10-22')).toBe(false);
    expect(rangoAlReves('2026-12-31', '2027-01-01')).toBe(false);
    expect(rangoAlReves('2026-10-22', '')).toBe(false);
    expect(rangoAlReves('', '2026-10-21')).toBe(false);
    expect(rangoAlReves('', '')).toBe(false);
  });

  it('el texto es el mismo que da el servidor', () => {
    expect(HASTA_ANTERIOR_A_DESDE).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
  });
});
