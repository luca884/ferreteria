import { MEDIOS_COBRANZA, MEDIO_COBRANZA_LABELS, aCuentaCentavos, centavosATexto, fechaCompletaLocal, sugerirFifo } from './cobranza.models';

/** Pendientes en orden FIFO tal como los manda GET /pendientes: solo el importe que falta cobrar importa acá. */
const pendientes = (...importes: number[]) => importes.map((pendiente) => ({ pendiente }));

describe('cobranza.models', () => {
  it('los medios de una cobranza son efectivo y transferencia (ni débito, ni crédito, ni cuenta corriente)', () => {
    expect(MEDIOS_COBRANZA).toEqual(['EFECTIVO', 'TRANSFERENCIA']);
    expect(MEDIO_COBRANZA_LABELS).toEqual({ EFECTIVO: 'Efectivo', TRANSFERENCIA: 'Transferencia' });
  });

  /** La tabla del apéndice A de tasks.md, compartida con `PartidasAbiertasTest` del backend. */
  describe('sugerirFifo en centavos (apéndice A)', () => {
    it('T1 exacto: A 300, B 500, C 400 con 1.200 se imputa entero y no queda a cuenta', () => {
      expect(sugerirFifo(pendientes(300, 500, 400), 120000)).toEqual({
        imputaciones: [30000, 50000, 40000],
        aCuentaCentavos: 0,
      });
    });

    it('T2 parcial: con 600 cubre A, parte de B y nada de C', () => {
      expect(sugerirFifo(pendientes(300, 500, 400), 60000)).toEqual({
        imputaciones: [30000, 30000, 0],
        aCuentaCentavos: 0,
      });
    });

    it('T3 sobrante: con 1.500 cubre todo y 300 quedan a cuenta', () => {
      expect(sugerirFifo(pendientes(300, 500, 400), 150000)).toEqual({
        imputaciones: [30000, 50000, 40000],
        aCuentaCentavos: 30000,
      });
    });

    it('T4a centavos: A 0,01 y B 0,99 con 1,00 cubren los dos', () => {
      expect(sugerirFifo(pendientes(0.01, 0.99), 100)).toEqual({ imputaciones: [1, 99], aCuentaCentavos: 0 });
    });

    it('T4b centavos: con 0,50 cubre A 0,01 y B 0,49', () => {
      expect(sugerirFifo(pendientes(0.01, 0.99), 50)).toEqual({ imputaciones: [1, 49], aCuentaCentavos: 0 });
    });

    it('T5 adelanto: sin pendientes no hay imputación y todo queda a cuenta', () => {
      expect(sugerirFifo([], 50000)).toEqual({ imputaciones: [], aCuentaCentavos: 50000 });
    });

    it('T6 total 0: no imputa nada (ni a cuenta)', () => {
      expect(sugerirFifo(pendientes(300), 0)).toEqual({ imputaciones: [0], aCuentaCentavos: 0 });
    });

    it('T7 escala: 100,1 contra un pendiente de 100,10 cubre el pendiente completo', () => {
      expect(sugerirFifo(pendientes(100.1), 10010)).toEqual({ imputaciones: [10010], aCuentaCentavos: 0 });
    });

    it('T8 redondeo: 33,33 + 33,33 + 33,34 sobre un pendiente de 100,00 acumulan 100,00 sin sobrante', () => {
      let pendiente = 10000;
      for (const parte of [3333, 3333, 3334]) {
        const sugerencia = sugerirFifo(pendientes(pendiente / 100), parte);
        expect(sugerencia.aCuentaCentavos).toBe(0);
        pendiente -= sugerencia.imputaciones[0];
      }
      expect(pendiente).toBe(0);
    });

    it('T8 redondeo: 33,34 + 33,34 + 33,33 se pasa 1 centavo en la tercera, que queda a cuenta', () => {
      let pendiente = 10000;
      let ultima = { imputaciones: [0], aCuentaCentavos: 0 };
      for (const parte of [3334, 3334, 3333]) {
        ultima = sugerirFifo(pendientes(pendiente / 100), parte);
        pendiente -= ultima.imputaciones[0];
      }
      // Quedaban 33,32: la tercera imputa 33,32 y el centavo que sobra va «a cuenta».
      expect(ultima).toEqual({ imputaciones: [3332], aCuentaCentavos: 1 });
      expect(pendiente).toBe(0);
    });

    it('T9 orden: el orden FIFO no depende del importe (A 500, B 100 con 550 da A 500 y B 50)', () => {
      expect(sugerirFifo(pendientes(500, 100), 55000)).toEqual({ imputaciones: [50000, 5000], aCuentaCentavos: 0 });
    });

    it('un importe con error de coma flotante (0,1 + 0,2) no pierde un centavo', () => {
      expect(sugerirFifo(pendientes(0.1 + 0.2), 30)).toEqual({ imputaciones: [30], aCuentaCentavos: 0 });
    });

    it('un total que no es un entero de centavos es un error de programación, no se imputa', () => {
      expect(() => sugerirFifo(pendientes(300), 10.5)).toThrow();
    });
  });

  describe('aCuentaCentavos', () => {
    it('a cuenta = total − Σ imputado', () => {
      expect(aCuentaCentavos(150000, [30000, 50000, 40000])).toBe(30000);
    });

    it('sin imputaciones, todo el total queda a cuenta', () => {
      expect(aCuentaCentavos(50000, [])).toBe(50000);
    });

    it('si lo imputado se pasa del total, da negativo (el diálogo lo marca como error)', () => {
      expect(aCuentaCentavos(1000, [600, 600])).toBe(-200);
    });
  });

  describe('centavosATexto', () => {
    it('escribe los centavos como un importe editable, con coma y 2 decimales (sin separador de miles)', () => {
      expect(centavosATexto(30050)).toBe('300,50');
      expect(centavosATexto(5)).toBe('0,05');
      expect(centavosATexto(0)).toBe('0,00');
      expect(centavosATexto(123456789)).toBe('1234567,89');
    });
  });

  describe('fechaCompletaLocal', () => {
    it('«19/10/2026 10:30» de una fecha local del servidor, leída del texto (sin pasar por la zona del navegador)', () => {
      expect(fechaCompletaLocal('2026-10-19T10:30:12.5')).toBe('19/10/2026 10:30');
      expect(fechaCompletaLocal('2026-01-05T23:59:59')).toBe('05/01/2026 23:59');
    });
  });
});
