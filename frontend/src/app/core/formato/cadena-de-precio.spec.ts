import datos from '../../../../../backend/src/test/resources/catalogo/casos-cadena-precio.json';
import { EntradaCadena, cadenaDePrecio, costoDeLista } from './cadena-de-precio';
import { Alicuota } from './precio-final';

/**
 * El desglose del formulario se calcula en vivo mientras se escriben los ajustes: tiene que dar exactamente lo mismo
 * que CostoDeLista y CadenaDePrecio del backend. Los casos son los de
 * backend/src/test/resources/catalogo/casos-cadena-precio.json (CasosCompartidosCadenaTest).
 */
type Caso = (typeof datos.casos)[number];

const alicuotaDe = (caso: Caso) => caso.alicuota as Alicuota;

/** La entrada de la cadena para un caso del JSON; el costo guardado puede diferir del de la lista. */
function entradaDe(caso: Caso): EntradaCadena {
  const usd = caso.moneda === 'USD';
  return {
    tieneLista: true,
    lista: caso.lista,
    incluyeIva: caso.incluyeIva,
    alicuota: alicuotaDe(caso),
    bonificaciones: caso.bonificaciones,
    ajustes: caso.ajustes,
    costoGuardado: caso.costoGuardado,
    moneda: usd ? 'USD' : 'ARS',
    cotizacion: 'cotizacion' in caso ? caso.cotizacion : null,
    margen: caso.margen,
    multiplo: caso.multiplo,
  };
}

describe('cadena de precio', () => {
  it('el JSON compartido trae los casos de R-219, R-209, R-212 y R-210', () => {
    expect(datos.casos.length).toBe(21);
    expect(datos.casos.filter((c) => c.moneda === 'USD').length).toBeGreaterThanOrEqual(2);
    expect(datos.casos.filter((c) => !c.coincide).length).toBeGreaterThanOrEqual(3);
  });

  describe('costoDeLista', () => {
    it.each(datos.casos.map((c) => [c.nombre, c] as const))('%s', (_nombre, caso) => {
      expect(
        costoDeLista(
          caso.lista,
          alicuotaDe(caso),
          caso.incluyeIva,
          caso.bonificaciones,
          caso.ajustes,
        ),
      ).toBe(caso.costo);
    });

    it('+3,5 sobre 200,00 da 207,00, sin error de punto flotante', () => {
      expect(costoDeLista('200.00', 'IVA_21', false, [null, null, null], ['3.5', null, null])).toBe(
        '207.00',
      );
      expect(costoDeLista(200, 'IVA_21', false, [], [3.5])).toBe('207.00');
    });

    it('-99,99 sobre 1.000,00 da 0,10', () => {
      expect(costoDeLista('1000.00', 'IVA_21', false, [], ['-99.99'])).toBe('0.10');
    });

    it('acepta números y listas más cortas que tres (lo que falta es «sin ajuste»)', () => {
      expect(costoDeLista(1000, 'IVA_21', false, [10], [-5, 3])).toBe('880.65');
      expect(costoDeLista(1000, 'IVA_21', false, undefined, undefined)).toBe('1000.00');
    });

    it('sin lista o ilegible no hay costo', () => {
      expect(costoDeLista(null, 'IVA_21', false, [], [])).toBeNull();
      expect(costoDeLista('abc', 'IVA_21', false, [], [])).toBeNull();
    });

    it('un ajuste ilegible no se ignora: no hay costo', () => {
      expect(costoDeLista(1000, 'IVA_21', false, [], ['x'])).toBeNull();
    });
  });

  describe('cadenaDePrecio', () => {
    it.each(datos.casos.map((c) => [c.nombre, c] as const))('%s', (_nombre, caso) => {
      const cadena = cadenaDePrecio(entradaDe(caso));

      expect(cadena.costoDeLista).toBe(caso.costo);
      expect(cadena.coincide).toBe(caso.coincide);
      expect(cadena.precio).toBe(caso.precio);
      expect(cadena.pasos.map((p) => [p.tipo, p.importe])).toEqual(
        caso.pasos.map((p) => [p.tipo, p.importe]),
      );
    });

    it('el 333,33 con -10 muestra 299,9970 en el paso del ajuste y 300,00 de costo', () => {
      const caso = datos.casos.find((c) => c.nombre.includes('333,33'))!;
      const cadena = cadenaDePrecio(entradaDe(caso));

      expect(cadena.pasos.find((p) => p.tipo === 'AJUSTE')?.importe).toBe('299.9970');
      expect(cadena.costoDeLista).toBe('300.00');
    });

    it('cada paso trae su porcentaje (bonificación, ajuste, IVA y margen) y los demás, null', () => {
      const caso = datos.casos[0];
      const cadena = cadenaDePrecio(entradaDe(caso));

      expect(cadena.pasos.map((p) => [p.tipo, p.porcentaje])).toEqual([
        ['LISTA', null],
        ['BONIFICACION', '10'],
        ['AJUSTE', '-5'],
        ['AJUSTE', '3'],
        ['COSTO', null],
        ['MARGEN', '30'],
        ['IVA', '21'],
        ['REDONDEO', null],
        ['PRECIO_FINAL', null],
      ]);
    });

    it('sin lista arranca en el costo guardado y no compara', () => {
      const cadena = cadenaDePrecio({
        tieneLista: false,
        lista: null,
        incluyeIva: null,
        alicuota: 'IVA_21',
        bonificaciones: [],
        ajustes: [],
        costoGuardado: '500.00',
        moneda: 'ARS',
        cotizacion: null,
        margen: '30',
        multiplo: '10',
      });

      expect(cadena.pasos.map((p) => p.tipo)).toEqual([
        'COSTO',
        'MARGEN',
        'IVA',
        'REDONDEO',
        'PRECIO_FINAL',
      ]);
      expect(cadena.costoDeLista).toBeNull();
      expect(cadena.coincide).toBeNull();
      expect(cadena.precio).toBe('790.00');
    });

    it('un artículo en dólares sin ninguna cotización termina en el costo y no da precio', () => {
      const caso = datos.casos.find((c) => c.moneda === 'USD')!;
      const cadena = cadenaDePrecio({ ...entradaDe(caso), cotizacion: null });

      expect(cadena.pasos.at(-1)?.tipo).toBe('COSTO');
      expect(cadena.precio).toBeNull();
    });

    it('sin múltiplo no hay paso de redondeo y el precio sube al centavo', () => {
      const caso = datos.casos[0];
      const cadena = cadenaDePrecio({ ...entradaDe(caso), multiplo: null });

      expect(cadena.pasos.map((p) => p.tipo)).not.toContain('REDONDEO');
      expect(cadena.precio).toBe('1385.27');
    });

    it('un costo guardado distinto del de la lista manda en el precio', () => {
      const caso = datos.casos.find((c) => c.nombre.includes('editado a mano'))!;
      const cadena = cadenaDePrecio(entradaDe(caso));

      expect(cadena.costoDeLista).toBe('900.00');
      expect(cadena.coincide).toBe(false);
      expect(cadena.precio).toBe('1500.00');
    });
  });
});
