import { MEDIOS_PAGO, MEDIO_PAGO_LABELS } from './venta.models';

describe('venta.models', () => {
  it('los medios de pago incluyen la cuenta corriente, última, con su etiqueta (CC-022)', () => {
    expect(MEDIOS_PAGO).toEqual(['EFECTIVO', 'DEBITO', 'CREDITO', 'TRANSFERENCIA', 'CUENTA_CORRIENTE']);
    expect(MEDIO_PAGO_LABELS.CUENTA_CORRIENTE).toBe('Cuenta corriente');
  });

  it('todo medio tiene etiqueta', () => {
    for (const medio of MEDIOS_PAGO) expect(MEDIO_PAGO_LABELS[medio]).toBeTruthy();
  });
});
