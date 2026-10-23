import {
  IMPORTE_MAXIMO_CENTAVOS,
  TIPOS_AJUSTE,
  TIPO_AJUSTE_LABELS,
  saldoTrasAjuste,
  textoDeSaldo,
  validarImporteAjuste,
} from './ajuste.models';

/** Intl separa el signo $ del número con un espacio de no separación. */
const NBSP = '\u00a0';

describe('ajuste.models (GDS-20, G59)', () => {
  it('los tipos son Recargo y Descuento, tal como los acepta el backend (en mayúsculas)', () => {
    expect([...TIPOS_AJUSTE]).toEqual(['RECARGO', 'DESCUENTO']);
    expect(TIPO_AJUSTE_LABELS).toEqual({ RECARGO: 'Recargo', DESCUENTO: 'Descuento' });
  });

  describe('saldoTrasAjuste: el efecto en el saldo, en centavos', () => {
    it('un Recargo sube el saldo por el importe', () => {
      expect(saldoTrasAjuste(120000, 'RECARGO', 50050)).toBe(170050);
    });

    it('un Descuento lo baja y puede dejarlo a favor (negativo)', () => {
      expect(saldoTrasAjuste(120000, 'DESCUENTO', 20000)).toBe(100000);
      expect(saldoTrasAjuste(30000, 'DESCUENTO', 50000)).toBe(-20000);
    });

    it('un Recargo sobre un saldo a favor lo lleva a cero', () => {
      expect(saldoTrasAjuste(-20000, 'RECARGO', 20000)).toBe(0);
    });
  });

  describe('textoDeSaldo: lo que se lee en pantalla', () => {
    it('lo que debe va con el monto; lo que tiene a favor, con «a favor» y sin signo', () => {
      expect(textoDeSaldo(1200)).toBe(`$${NBSP}1.200,00`);
      expect(textoDeSaldo(0)).toBe(`$${NBSP}0,00`);
      expect(textoDeSaldo(-500)).toBe(`$${NBSP}500,00 a favor`);
    });
  });

  describe('validarImporteAjuste', () => {
    it('un importe de hasta 2 decimales, mayor que cero, vale (con coma o con punto)', () => {
      expect(validarImporteAjuste('1234,50')).toBeNull();
      expect(validarImporteAjuste('0.01')).toBeNull();
      expect(validarImporteAjuste(' 500 ')).toBeNull();
    });

    it('vacío pide el importe', () => {
      expect(validarImporteAjuste('')).toBe('Escribí el importe del ajuste.');
      expect(validarImporteAjuste('   ')).toBe('Escribí el importe del ajuste.');
    });

    it('con más de 2 decimales, con letras o negativo avisa los decimales', () => {
      expect(validarImporteAjuste('1,234')).toBe('Usá un número con hasta 2 decimales.');
      expect(validarImporteAjuste('abc')).toBe('Usá un número con hasta 2 decimales.');
      expect(validarImporteAjuste('-5')).toBe('Usá un número con hasta 2 decimales.');
    });

    it('cero no vale: tiene que ser mayor que cero', () => {
      expect(validarImporteAjuste('0')).toBe('El importe tiene que ser mayor que cero.');
      expect(validarImporteAjuste('0,00')).toBe('El importe tiene que ser mayor que cero.');
    });

    it('pasar el tope de 13 enteros y 2 decimales avisa que es demasiado grande', () => {
      expect(IMPORTE_MAXIMO_CENTAVOS).toBe(999_999_999_999_999);
      expect(validarImporteAjuste('9999999999999,99')).toBeNull();
      expect(validarImporteAjuste('10000000000000')).toBe('El importe es demasiado grande.');
    });
  });
});
