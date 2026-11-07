import { formatearCosto } from '../../core/formato/monto';
import { AltaPlaneada, Ajustes, CambioPlaneado } from './importacion.models';
import { detalleDeFila, etiquetaDeTipo, textoAjuste, textoAjustes } from './detalle-fila';
import { FilaDeVista } from './seleccion-filas';

const NINGUNO: Ajustes = { a1: null, a2: null, a3: null };

function cambio(parcial: Partial<CambioPlaneado> = {}): CambioPlaneado {
  return {
    fila: 2,
    hoja: 0,
    articuloId: 1,
    codigoProveedor: 'A-1',
    descripcion: 'Tornillo',
    precioLista: 100,
    costoAnterior: 100,
    costoNuevo: 100,
    precioAnterior: 150,
    precioNuevo: 150,
    activo: true,
    variacionFuerte: false,
    monedaAnterior: 'ARS',
    monedaNueva: 'ARS',
    alicuotaAnterior: 'IVA_21',
    alicuotaNueva: 'IVA_21',
    ajustesAnteriores: NINGUNO,
    ajustesNuevos: NINGUNO,
    ...parcial,
  };
}

function alta(parcial: Partial<AltaPlaneada> = {}): AltaPlaneada {
  return {
    fila: 3,
    hoja: 0,
    codigo: 'N-1',
    descripcion: 'Arandela',
    precioLista: 20,
    costo: 18,
    moneda: 'ARS',
    alicuotaIva: 'IVA_21',
    ajustes: NINGUNO,
    unidad: 'UNIDAD',
    codigoBarras: null,
    rubroId: null,
    precioNuevo: 30,
    ...parcial,
  };
}

function fila(tipo: FilaDeVista['tipo'], extra: Partial<FilaDeVista>): FilaDeVista {
  return {
    clave: '0:2',
    hoja: 0,
    fila: 2,
    tipo,
    codigo: 'A-1',
    descripcion: 'x',
    cambio: null,
    alta: null,
    mensaje: null,
    ...extra,
  };
}

describe('detalle-fila', () => {
  describe('textoAjuste', () => {
    it('negativo es descuento y positivo es recargo, con palabras', () => {
      expect(textoAjuste(-5)).toBe('descuento 5 %');
      expect(textoAjuste(3)).toBe('recargo 3 %');
    });

    it('usa coma decimal', () => {
      expect(textoAjuste(-3.5)).toBe('descuento 3,5 %');
      expect(textoAjuste(12.25)).toBe('recargo 12,25 %');
    });

    it('el cero no es ni descuento ni recargo', () => {
      expect(textoAjuste(0)).toBe('sin ajuste (0 %)');
    });
  });

  describe('textoAjustes', () => {
    it('une los que tienen valor, en orden', () => {
      expect(textoAjustes({ a1: -5, a2: null, a3: 3 })).toBe('descuento 5 % y recargo 3 %');
      expect(textoAjustes({ a1: 10, a2: -2, a3: 3 })).toBe('recargo 10 %, descuento 2 % y recargo 3 %');
    });

    it('sin ajustes dice «ninguno»', () => {
      expect(textoAjustes(NINGUNO)).toBe('ninguno');
    });
  });

  describe('etiquetaDeTipo', () => {
    it('rotula cada tipo', () => {
      expect(etiquetaDeTipo('suben')).toBe('Sube');
      expect(etiquetaDeTipo('bajan')).toBe('Baja');
      expect(etiquetaDeTipo('cambianMoneda')).toBe('Cambia de moneda');
      expect(etiquetaDeTipo('corrigen')).toBe('Corrige IVA o ajustes');
      expect(etiquetaDeTipo('nuevos')).toBe('Nuevo');
      expect(etiquetaDeTipo('sinCambio')).toBe('Sin cambio');
      expect(etiquetaDeTipo('conError')).toBe('Con error');
    });
  });

  describe('detalleDeFila', () => {
    it('un cambio de moneda muestra de qué moneda a cuál', () => {
      const f = fila('cambianMoneda', { cambio: cambio({ monedaAnterior: 'ARS', monedaNueva: 'USD' }) });

      expect(detalleDeFila(f)).toEqual(['Moneda: ARS → USD']);
    });

    it('una corrección de IVA muestra la alícuota anterior y la nueva', () => {
      const f = fila('corrigen', { cambio: cambio({ alicuotaAnterior: 'IVA_21', alicuotaNueva: 'IVA_10_5' }) });

      expect(detalleDeFila(f)).toEqual(['IVA: 21 % → 10,5 %']);
    });

    it('una corrección de ajustes muestra antes y ahora con palabras', () => {
      const f = fila('corrigen', {
        cambio: cambio({ ajustesAnteriores: NINGUNO, ajustesNuevos: { a1: -5, a2: 3, a3: null } }),
      });

      expect(detalleDeFila(f)).toEqual([
        'Descuentos y recargos: ninguno → descuento 5 % y recargo 3 %',
      ]);
    });

    it('un cambio que sube y además cambia el IVA muestra las dos cosas', () => {
      const f = fila('suben', {
        cambio: cambio({ costoNuevo: 120, alicuotaAnterior: 'IVA_21', alicuotaNueva: 'IVA_27' }),
      });

      expect(detalleDeFila(f)).toEqual(['IVA: 21 % → 27 %']);
    });

    it('un cambio que no toca moneda, IVA ni ajustes no tiene detalle', () => {
      expect(detalleDeFila(fila('suben', { cambio: cambio({ costoNuevo: 120 }) }))).toEqual([]);
    });

    it('con recargos del costo muestra el costo real de antes y el de ahora, con la suma de los recargos', () => {
      const f = fila('suben', {
        cambio: cambio({ costoNuevo: 200, recargosPorcentaje: 10, costoRealAnterior: 110, costoRealNuevo: 220 }),
      });

      expect(detalleDeFila(f)).toEqual([
        `Costo real: ${formatearCosto(110, 'ARS')} → ${formatearCosto(220, 'ARS')} (recargos 10 %)`,
      ]);
    });

    it('el costo real va en la moneda de cada lado y, si no cambia, un solo importe', () => {
      const deMoneda = fila('cambianMoneda', {
        cambio: cambio({
          monedaAnterior: 'ARS',
          monedaNueva: 'USD',
          costoNuevo: 10,
          recargosPorcentaje: 4.44,
          costoRealAnterior: 104.44,
          costoRealNuevo: 10.44,
        }),
      });
      const corrige = fila('corrigen', {
        cambio: cambio({
          alicuotaNueva: 'IVA_10_5',
          recargosPorcentaje: 14.44,
          costoRealAnterior: 114.44,
          costoRealNuevo: 114.44,
        }),
      });

      expect(detalleDeFila(deMoneda)).toEqual([
        'Moneda: ARS → USD',
        `Costo real: ${formatearCosto(104.44, 'ARS')} → ${formatearCosto(10.44, 'USD')} (recargos 4,44 %)`,
      ]);
      expect(detalleDeFila(corrige)).toEqual([
        'IVA: 21 % → 10,5 %',
        `Costo real: ${formatearCosto(114.44, 'ARS')} (recargos 14,44 %)`,
      ]);
    });

    it('sin recargos (los campos en null) no hay línea de costo real', () => {
      const f = fila('suben', {
        cambio: cambio({ costoNuevo: 200, recargosPorcentaje: null, costoRealAnterior: null, costoRealNuevo: null }),
      });

      expect(detalleDeFila(f)).toEqual([]);
    });

    it('un cambio de un servidor sin los datos nuevos tampoco falla', () => {
      const viejo = cambio({ monedaAnterior: null, monedaNueva: null, alicuotaAnterior: null, alicuotaNueva: null });

      expect(detalleDeFila(fila('bajan', { cambio: viejo }))).toEqual([]);
    });

    it('un artículo nuevo en dólares, con IVA 10,5 y ajustes muestra esos datos', () => {
      const f = fila('nuevos', {
        alta: alta({ moneda: 'USD', alicuotaIva: 'IVA_10_5', ajustes: { a1: -5, a2: null, a3: null } }),
      });

      expect(detalleDeFila(f)).toEqual(['Moneda: USD', 'IVA: 10,5 %', 'Descuentos y recargos: descuento 5 %']);
    });

    it('un artículo nuevo común (pesos, IVA 21 %, unidad) no tiene detalle', () => {
      expect(detalleDeFila(fila('nuevos', { alta: alta() }))).toEqual([]);
    });

    it('un artículo nuevo con código de barras y unidad distinta de «Unidad» los muestra', () => {
      const f = fila('nuevos', { alta: alta({ codigoBarras: '7791234567890', unidad: 'METRO' }) });

      expect(detalleDeFila(f)).toEqual(['Código de barras: 7791234567890', 'Unidad: Metro']);
    });

    it('una fila con error muestra el motivo', () => {
      expect(detalleDeFila(fila('conError', { mensaje: 'Moneda no reconocida' }))).toEqual(['Moneda no reconocida']);
    });

    it('una fila sin cambio no tiene detalle', () => {
      expect(detalleDeFila(fila('sinCambio', {}))).toEqual([]);
    });
  });
});
