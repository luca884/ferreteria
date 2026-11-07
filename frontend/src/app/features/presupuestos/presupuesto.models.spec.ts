import { ESTADOS_PRESUPUESTO, ETIQUETA_ESTADO, fechaDia } from './presupuesto.models';

describe('presupuesto.models', () => {
  it('fechaDia da dd/MM/aaaa de un día aaaa-mm-dd, sin pasar por la zona del navegador', () => {
    expect(fechaDia('2026-11-02')).toBe('02/11/2026');
    expect(fechaDia('2026-01-31')).toBe('31/01/2026');
  });

  it('fechaDia de algo vacío es vacío', () => {
    expect(fechaDia('')).toBe('');
    expect(fechaDia(null)).toBe('');
    expect(fechaDia(undefined)).toBe('');
  });

  it('cada estado tiene su etiqueta; el pasado a venta (FACTURADO por dentro) se ve «Vendido», porque puede no tener factura (arreglos-confusos, S5)', () => {
    expect(ESTADOS_PRESUPUESTO.map((e) => ETIQUETA_ESTADO[e])).toEqual(['Pendiente', 'Aceptado', 'Rechazado', 'Vendido']);
  });
});
