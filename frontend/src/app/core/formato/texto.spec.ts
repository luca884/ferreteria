import { contiene } from './texto';

describe('contiene', () => {
  it('encuentra el texto buscado en cualquier parte, sin distinguir mayúsculas', () => {
    expect(contiene('Bulonera Sur', 'sur')).toBe(true);
    expect(contiene('Bulonera Sur', 'BULO')).toBe(true);
  });

  it('no distingue tildes ni eñes escritas sin tilde', () => {
    expect(contiene('Ferretería Núñez', 'ferreteria nunez')).toBe(true);
    expect(contiene('Ferreteria Nunez', 'Ferretería')).toBe(true);
  });

  it('ignora los espacios de los bordes de lo buscado', () => {
    expect(contiene('Clientes', '  cli ')).toBe(true);
  });

  it('lo que no está no lo encuentra', () => {
    expect(contiene('Proveedores', 'compras')).toBe(false);
  });
});
