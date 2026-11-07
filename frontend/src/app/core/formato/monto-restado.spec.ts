import { formatearMonto, formatearRestado, MontoPipe, RestadoPipe } from './monto';

// Regresión de bugs de la guía, S4 (sondas del verificador): el helper único de los montos restados.
describe('formatearRestado contra la salida de antes', () => {
  const FORMATO = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' });
  const positivos = [0.01, 0.5, 1, 56.87, 130, 700, 1234.5, 999999.99, 1e9];

  it('cada positivo sale como el «-» + monto de antes, con U+2212 en vez del guion', () => {
    for (const v of positivos) {
      const antes = `-${formatearMonto(v)}`; // lo que escribían las plantillas: -{{ x | monto }}
      const ahora = formatearRestado(v);
      expect(ahora).toBe(antes.replace('-', '−'));
      expect(ahora.charCodeAt(0)).toBe(0x2212);
      expect(ahora).not.toContain('-');
    }
  });

  it('es la misma función que tenía la caja: > 0 lleva −, 0 y negativos quedan como formatearMonto', () => {
    const cajaDeAntes = (importe: number) => (importe > 0 ? `−${formatearMonto(importe)}` : formatearMonto(importe));
    for (const v of [...positivos, 0, -0, -1, -700.5]) expect(formatearRestado(v)).toBe(cajaDeAntes(v));
  });

  it('null y undefined dan vacío, como formatearMonto', () => {
    expect(formatearRestado(null)).toBe('');
    expect(formatearRestado(undefined)).toBe('');
  });

  it('formatearMonto de un negativo no cambió: sigue con el guion de Intl', () => {
    for (const v of [-1, -700, -1234.5]) {
      expect(formatearMonto(v)).toBe(FORMATO.format(v));
      expect(formatearMonto(v).charCodeAt(0)).toBe(0x2d);
      expect(new MontoPipe().transform(v)).toBe(FORMATO.format(v));
    }
  });

  it('el pipe da lo mismo que la función', () => {
    for (const v of [...positivos, 0, -3, null, undefined]) expect(new RestadoPipe().transform(v)).toBe(formatearRestado(v));
  });
});
