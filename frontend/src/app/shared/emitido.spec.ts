import { emitidoEl } from './emitido';

describe('emitidoEl', () => {
  it('dice el instante en hora de Buenos Aires', () => {
    expect(emitidoEl(new Date('2026-10-21T15:30:00Z'))).toBe('21/10/2026 12:30');
  });

  it('pasa al día anterior cuando en UTC ya es el siguiente', () => {
    expect(emitidoEl(new Date('2026-10-22T01:05:00Z'))).toBe('21/10/2026 22:05');
  });

  it('la medianoche es 00:00, no 24:00', () => {
    expect(emitidoEl(new Date('2026-10-21T03:00:00Z'))).toBe('21/10/2026 00:00');
  });
});
