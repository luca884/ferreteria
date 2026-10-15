import casos from '../../../../../backend/src/test/resources/ventas/casos-totales.json';
import {
  aCentavos,
  aMilesimas,
  desdeTexto,
  totalRenglon,
  totalTicket,
} from './centavos';

interface CasoRenglon {
  nombre: string;
  precio: string;
  cantidad: string;
  total: string;
}
interface CasoTicket {
  nombre: string;
  renglones: string[];
  total: string;
}

const renglones = casos.renglones as CasoRenglon[];
const tickets = casos.tickets as CasoTicket[];

function totalDe(caso: CasoRenglon): number {
  return totalRenglon(desdeTexto(caso.precio, 2), desdeTexto(caso.cantidad, 3));
}

describe('centavos (casos compartidos con Java)', () => {
  for (const caso of renglones) {
    it(`renglón ${caso.nombre}: total ${caso.total}`, () => {
      expect(totalDe(caso)).toBe(desdeTexto(caso.total, 2));
    });
  }

  for (const t of tickets) {
    it(`ticket ${t.nombre}: total ${t.total}`, () => {
      const lineas = t.renglones.map((n) => totalDe(renglones.find((r) => r.nombre === n)!));
      expect(lineas.reduce((a, b) => a + b, 0)).toBe(desdeTexto(t.total, 2));
    });
  }

  it('el ticket de los tres primeros da 2485,50', () => {
    const t = tickets.find((x) => x.nombre === 'tres-primeros')!;
    const rs = t.renglones.map((n) => {
      const c = renglones.find((r) => r.nombre === n)!;
      return { precioCentavos: desdeTexto(c.precio, 2), cantidadMilesimas: desdeTexto(c.cantidad, 3) };
    });
    expect(totalTicket(rs)).toBe(248550);
  });
});

describe('centavos', () => {
  it('redondea HALF_UP en el medio: 333,33 x 2,5 = 833,325 da 833,33', () => {
    expect(totalRenglon(33333, 2500)).toBe(83333);
  });

  it('0,05 x 0,1 da 0,01 (no 0,00)', () => {
    expect(totalRenglon(5, 100)).toBe(1);
  });

  it('falla si el producto no es un entero seguro', () => {
    expect(() => totalRenglon(Number.MAX_SAFE_INTEGER, 2000)).toThrow();
    expect(() => totalRenglon(1.5, 1000)).toThrow();
  });

  it('aCentavos y aMilesimas redondean al entero más cercano', () => {
    expect(aCentavos(1210)).toBe(121000);
    expect(aCentavos(0.29)).toBe(29);
    expect(aMilesimas(2.5)).toBe(2500);
    expect(aMilesimas(0.015)).toBe(15);
  });

  describe('desdeTexto', () => {
    it('parsea sin pasar por float', () => {
      expect(desdeTexto('170.00', 2)).toBe(17000);
      expect(desdeTexto('2.5', 3)).toBe(2500);
      expect(desdeTexto('3', 3)).toBe(3000);
      expect(desdeTexto('0.015', 3)).toBe(15);
      expect(desdeTexto('4463.33', 2)).toBe(446333);
      expect(desdeTexto('0.29', 2)).toBe(29);
    });

    it('acepta coma decimal y signo', () => {
      expect(desdeTexto('2,5', 3)).toBe(2500);
      expect(desdeTexto('-1.50', 2)).toBe(-150);
    });

    it('acepta ceros de más y rechaza decimales significativos de más', () => {
      expect(desdeTexto('2.5000', 3)).toBe(2500);
      expect(() => desdeTexto('0.0005', 3)).toThrow();
    });

    it('rechaza texto que no es un número', () => {
      expect(() => desdeTexto('abc', 2)).toThrow();
      expect(() => desdeTexto('', 2)).toThrow();
      expect(() => desdeTexto('1.2.3', 2)).toThrow();
    });
  });
});
