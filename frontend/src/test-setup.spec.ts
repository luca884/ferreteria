describe('setup global de los tests', () => {
  it('corre en UTC, no en la zona de la máquina: así una conversión de zona en las fechas se detecta', () => {
    expect(new Date(2026, 0, 15, 12).getTimezoneOffset()).toBe(0);
    expect(new Date(2026, 6, 15, 12).getTimezoneOffset()).toBe(0);
    expect(new Date('2026-10-19T10:30:00').toISOString()).toBe('2026-10-19T10:30:00.000Z');
  });
});
