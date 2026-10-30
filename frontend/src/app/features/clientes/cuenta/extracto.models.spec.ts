import {
  FILTRO_VACIO,
  FiltroExtracto,
  LineaExtracto,
  TAMANIO_PAGINA_EXTRACTO,
  TIPOS_MOVIMIENTO_CUENTA,
  TIPO_MOVIMIENTO_LABELS,
  TOPE_IMPRESION,
  filtroAParams,
  hayFiltro,
  paramsDeImpresion,
  puedeAnular,
  puedeVerRecibo,
  textoPeriodo,
} from './extracto.models';

/** Una línea del libro con el contrato de LineaExtractoResponse (saldo, no saldoDespues). */
const linea = (cambios: Partial<LineaExtracto> = {}): LineaExtracto => ({
  id: 91,
  fecha: '2026-10-20T10:11:12.5',
  tipo: 'COBRO',
  referencia: 'Recibo N.º 14',
  debe: 0,
  haber: 500,
  saldo: 700,
  usuario: 'Ana',
  reciboId: 14,
  comprobanteId: null,
  motivo: null,
  excedioLimite: false,
  autorizadoPor: null,
  anulado: false,
  ...cambios,
});

describe('extracto.models (CC-073, CC-078, CC-096)', () => {
  describe('tipos y etiquetas', () => {
    it('los tipos son los del libro del backend (en mayúsculas, que el backend no acepta de otra forma)', () => {
      expect([...TIPOS_MOVIMIENTO_CUENTA].sort()).toEqual(['ANULACION', 'AJUSTE', 'COBRO', 'NC', 'VENTA'].sort());
    });

    it('cada tipo se lee en español: Venta, Cobro, Anulación, Ajuste y Nota de crédito, en ese orden', () => {
      expect(TIPOS_MOVIMIENTO_CUENTA.map((t) => TIPO_MOVIMIENTO_LABELS[t])).toEqual([
        'Venta',
        'Cobro',
        'Anulación',
        'Ajuste',
        'Nota de crédito',
      ]);
    });
  });

  describe('filtroAParams', () => {
    const filtro = (cambios: Partial<FiltroExtracto> = {}): FiltroExtracto => ({ ...FILTRO_VACIO, ...cambios });

    it('un filtro vacío no manda ni desde, ni hasta, ni tipo: solo page y size (20 por defecto)', () => {
      expect(filtroAParams(filtro())).toEqual({ page: '0', size: '20' });
      expect(TAMANIO_PAGINA_EXTRACTO).toBe(20);
    });

    it('manda desde, hasta y tipo en el formato que espera el backend (ISO y mayúsculas)', () => {
      expect(filtroAParams(filtro({ desde: '2026-10-01', hasta: '2026-10-31', tipo: 'COBRO' }), 2, 50)).toEqual({
        desde: '2026-10-01',
        hasta: '2026-10-31',
        tipo: 'COBRO',
        page: '2',
        size: '50',
      });
    });

    it('omite solo el que está vacío: con un desde y nada más, hasta y tipo no viajan', () => {
      const params = filtroAParams(filtro({ desde: '2026-10-01' }));

      expect(params).toEqual({ desde: '2026-10-01', page: '0', size: '20' });
      expect(Object.keys(params)).not.toContain('hasta');
      expect(Object.keys(params)).not.toContain('tipo');
    });

    it('un valor de solo espacios cuenta como vacío', () => {
      expect(filtroAParams(filtro({ desde: '  ', hasta: ' ' }))).toEqual({ page: '0', size: '20' });
    });

    it('para imprimir se manda impresion=true y NO page ni size', () => {
      const params = paramsDeImpresion(filtro({ desde: '2026-10-01', tipo: 'VENTA' }));

      expect(params).toEqual({ desde: '2026-10-01', tipo: 'VENTA', impresion: 'true' });
      expect(Object.keys(params)).not.toContain('page');
      expect(Object.keys(params)).not.toContain('size');
    });

    it('el tope de impresión es de 2000 movimientos (el del backend)', () => {
      expect(TOPE_IMPRESION).toBe(2000);
    });
  });

  describe('hayFiltro', () => {
    it('el filtro vacío no filtra y cualquier campo con valor sí', () => {
      expect(hayFiltro(FILTRO_VACIO)).toBe(false);
      expect(hayFiltro({ ...FILTRO_VACIO, tipo: 'NC' })).toBe(true);
      expect(hayFiltro({ ...FILTRO_VACIO, hasta: '2026-10-01' })).toBe(true);
    });
  });

  describe('textoPeriodo (CC-099)', () => {
    it('con desde y hasta: «01/10/2026 al 31/10/2026»', () => {
      expect(textoPeriodo('2026-10-01', '2026-10-31')).toBe('01/10/2026 al 31/10/2026');
    });

    it('con uno solo dice desde o hasta; sin ninguno, todo el historial', () => {
      expect(textoPeriodo('2026-10-01', null)).toBe('desde el 01/10/2026');
      expect(textoPeriodo(null, '2026-10-31')).toBe('hasta el 31/10/2026');
      expect(textoPeriodo(null, null)).toBe('todo el historial');
    });

    it('lee el día del texto, sin pasar por la zona del navegador', () => {
      expect(textoPeriodo('2026-01-05', '2026-01-05')).toBe('05/01/2026 al 05/01/2026');
    });
  });

  describe('acciones por tipo de línea (CC-097, CC-098)', () => {
    it('«Anular» solo en un COBRO vigente', () => {
      expect(puedeAnular(linea())).toBe(true);
      expect(puedeAnular(linea({ anulado: true }))).toBe(false);
      expect(puedeAnular(linea({ tipo: 'ANULACION', debe: 500, haber: 0 }))).toBe(false);
      expect(puedeAnular(linea({ tipo: 'VENTA', reciboId: null, comprobanteId: 3 }))).toBe(false);
      expect(puedeAnular(linea({ tipo: 'AJUSTE', reciboId: null }))).toBe(false);
      expect(puedeAnular(linea({ tipo: 'NC', reciboId: null }))).toBe(false);
    });

    it('un COBRO sin recibo (dato roto) no se puede anular ni ver', () => {
      expect(puedeAnular(linea({ reciboId: null }))).toBe(false);
      expect(puedeVerRecibo(linea({ reciboId: null }))).toBe(false);
    });

    it('«Ver recibo» en COBRO (también anulado) y en ANULACION; en las demás no', () => {
      expect(puedeVerRecibo(linea())).toBe(true);
      expect(puedeVerRecibo(linea({ anulado: true }))).toBe(true);
      expect(puedeVerRecibo(linea({ tipo: 'ANULACION' }))).toBe(true);
      expect(puedeVerRecibo(linea({ tipo: 'VENTA', reciboId: null }))).toBe(false);
      expect(puedeVerRecibo(linea({ tipo: 'AJUSTE', reciboId: null }))).toBe(false);
    });
  });
});
