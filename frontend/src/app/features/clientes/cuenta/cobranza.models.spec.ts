import {
  MEDIOS_COBRANZA,
  MEDIO_COBRANZA_LABELS,
  aCuentaCentavos,
  centavosATexto,
  clavePartida,
  fechaCompletaLocal,
  filasDeImputacion,
  hoyEnBuenosAires,
  imputacionDe,
  leerImporte,
  renglonesDeImputacion,
  sugerirFifo,
  validarMotivo,
} from './cobranza.models';

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

  describe('validarMotivo (CC-056, S-1: la misma regla que el backend)', () => {
    it('un motivo de 5 a 200 caracteres tras recortar es válido (no devuelve error)', () => {
      expect(validarMotivo('Cliente equivocado')).toBeNull();
      expect(validarMotivo('abcde')).toBeNull();
      expect(validarMotivo('a'.repeat(200))).toBeNull();
    });

    it('con menos de 5 caracteres tras recortar pide escribirlo, aunque tenga espacios alrededor', () => {
      const mensaje = 'Escribí el motivo de la anulación (al menos 5 letras).';
      expect(validarMotivo('abc')).toBe(mensaje);
      expect(validarMotivo('')).toBe(mensaje);
      expect(validarMotivo('     ')).toBe(mensaje);
      expect(validarMotivo('  abcd  ')).toBe(mensaje);
    });

    it('«12345» vale: son 5 caracteres, no hace falta que sean letras (S-1)', () => {
      expect(validarMotivo('12345')).toBeNull();
    });

    it('con más de 200 caracteres tras recortar avisa el máximo; con 200 más espacios todavía vale', () => {
      expect(validarMotivo('a'.repeat(201))).toBe('El motivo admite hasta 200 caracteres.');
      expect(validarMotivo(`  ${'a'.repeat(200)}  `)).toBeNull();
    });
  });

  describe('hoyEnBuenosAires (para saber si un cobro es de hoy)', () => {
    it('el día de la caja es el de Buenos Aires: las 02:30 UTC todavía son el día anterior', () => {
      expect(hoyEnBuenosAires(new Date('2026-10-20T02:30:00Z'))).toBe('2026-10-19');
    });

    it('a las 03:00 UTC (medianoche en Buenos Aires) cambia el día', () => {
      expect(hoyEnBuenosAires(new Date('2026-10-20T03:00:00Z'))).toBe('2026-10-20');
      expect(hoyEnBuenosAires(new Date('2026-01-05T15:00:00Z'))).toBe('2026-01-05');
    });
  });

  describe('fechaCompletaLocal', () => {
    it('«19/10/2026 10:30» de una fecha local del servidor, leída del texto (sin pasar por la zona del navegador)', () => {
      expect(fechaCompletaLocal('2026-10-19T10:30:12.5')).toBe('19/10/2026 10:30');
      expect(fechaCompletaLocal('2026-01-05T23:59:59')).toBe('05/01/2026 23:59');
    });
  });

  describe('partidas pendientes: una venta (comprobanteId) o un Recargo (ajusteId)', () => {
    it('la clave de una venta es C-<id> y la de un Recargo A-<id>: el mismo número no se pisa entre los dos', () => {
      expect(clavePartida({ comprobanteId: 7 })).toBe('C-7');
      expect(clavePartida({ comprobanteId: 7, ajusteId: null })).toBe('C-7');
      expect(clavePartida({ comprobanteId: null, ajusteId: 7 })).toBe('A-7');
      expect(clavePartida({ ajusteId: 3 })).toBe('A-3');
    });

    it('la imputación de una venta lleva solo comprobanteId y la de un Recargo solo ajusteId', () => {
      expect(imputacionDe({ comprobanteId: 7, ajusteId: null }, 100)).toEqual({ comprobanteId: 7, importe: 100 });
      expect(imputacionDe({ comprobanteId: null, ajusteId: 3 }, 50.5)).toEqual({ ajusteId: 3, importe: 50.5 });
      expect('ajusteId' in imputacionDe({ comprobanteId: 7 }, 1)).toBe(false);
      expect('comprobanteId' in imputacionDe({ comprobanteId: null, ajusteId: 3 }, 1)).toBe(false);
    });

    it('leerImporte lee hasta 2 decimales en centavos; null si no es un número o es negativo', () => {
      expect(leerImporte('300,50')).toBe(30050);
      expect(leerImporte('0')).toBe(0);
      expect(leerImporte('1,234')).toBeNull();
      expect(leerImporte('-1')).toBeNull();
      expect(leerImporte('x')).toBeNull();
    });
  });

  describe('filasDeImputacion y renglonesDeImputacion', () => {
    const venta = { comprobanteId: 31, ajusteId: null, referencia: 'Venta N.º 7', fecha: '2026-10-10T09:00:00', importe: 300, imputado: 0, pendiente: 300 };
    const recargo = { comprobanteId: null, ajusteId: 3, referencia: 'Recargo N.º 3', fecha: '2026-10-11T09:00:00', importe: 200, imputado: 0, pendiente: 200 };

    it('con la sugerencia FIFO cada partida toma su importe, con la clave de la partida', () => {
      const filas = filasDeImputacion([venta, recargo], null, [30000, 5000]);

      expect(filas.map((f) => [f.clave, f.texto, f.centavos, f.error])).toEqual([
        ['C-31', '300,00', 30000, null],
        ['A-3', '50,00', 5000, null],
      ]);
      expect(filas.map((f) => f.idCampo)).toEqual(['31', 'a3']);
    });

    it('lo editado manda sobre la sugerencia y se busca por clave: C-3 no es A-3', () => {
      const filas = filasDeImputacion([venta, recargo], { 'A-3': '75', 'C-31': '' }, [30000, 5000]);

      expect(filas.map((f) => [f.texto, f.centavos])).toEqual([
        ['', 0],
        ['75', 7500],
      ]);
    });

    it('un importe mayor que lo pendiente o con muchos decimales marca el error de la fila', () => {
      const filas = filasDeImputacion([venta, recargo], { 'C-31': '300,01', 'A-3': '1,234' }, [0, 0]);

      expect(filas[0].error).toBe('No puede superar lo pendiente ($\u00a0300,00).');
      expect(filas[1].error).toBe('Usá un número con hasta 2 decimales.');
    });

    it('los renglones del pedido son solo las filas con importe, venta por comprobanteId y Recargo por ajusteId', () => {
      const filas = filasDeImputacion([venta, recargo], null, [0, 5000]);

      expect(renglonesDeImputacion(filas)).toEqual([{ ajusteId: 3, importe: 50 }]);
      expect(renglonesDeImputacion(filasDeImputacion([venta, recargo], null, [30000, 5000]))).toEqual([
        { comprobanteId: 31, importe: 300 },
        { ajusteId: 3, importe: 50 },
      ]);
    });
  });

  describe('validarMotivo de un ajuste', () => {
    it('nombra el ajuste y no la anulación, con la misma regla de 5 a 200 caracteres', () => {
      expect(validarMotivo('abc', 'del ajuste')).toBe('Escribí el motivo del ajuste (al menos 5 letras).');
      expect(validarMotivo('a'.repeat(201), 'del ajuste')).toBe('El motivo admite hasta 200 caracteres.');
      expect(validarMotivo('Deuda vieja', 'del ajuste')).toBeNull();
    });
  });
});
