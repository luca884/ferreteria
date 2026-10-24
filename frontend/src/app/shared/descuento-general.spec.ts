import { porcentajeDescuentoGeneral } from './descuento-general';

describe('porcentajeDescuentoGeneral', () => {
  const renglon = (descuentoPorcentaje: number) => ({ descuentoPorcentaje });

  it('con solo descuento general devuelve su porcentaje', () => {
    expect(porcentajeDescuentoGeneral({ descuentoPorcentaje: 10, renglones: [renglon(0), renglon(0)] })).toBe(10);
  });

  it('con descuento por renglón devuelve null, haya o no general', () => {
    expect(porcentajeDescuentoGeneral({ descuentoPorcentaje: 0, renglones: [renglon(10)] })).toBeNull();
    expect(porcentajeDescuentoGeneral({ descuentoPorcentaje: 5, renglones: [renglon(0), renglon(10)] })).toBeNull();
  });

  it('sin ningún descuento devuelve null', () => {
    expect(porcentajeDescuentoGeneral({ descuentoPorcentaje: 0, renglones: [renglon(0)] })).toBeNull();
  });
});
