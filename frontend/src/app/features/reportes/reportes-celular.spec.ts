import { registerLocaleData } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { Reportes } from './reportes';
import { REPORTES, ReporteMovimientos, Resumen } from './reportes.models';
import { ReportesService } from './reportes.service';

registerLocaleData(localeEsAr);

/**
 * Los reportes en el celular (movil-sectores T11c, S1, S2, S5, S7): sólo cambia cómo se ve. Los pedidos al servidor, los
 * textos y los errores corren en escritorio y en celular con los mismos literales (el helper `elegirReporte` usa el
 * selector que haya en cada pantalla, así esos tests también corren contra la base); los de estructura miran lo propio
 * del celular: el reporte se elige en una hoja inferior. En escritorio y en tablet (matchMedia de 1023.98 px) sigue el
 * `<select>` de hoy. Las tablas pasan a tarjetas sólo con CSS: Vitest no lo lee, se mide en el navegador.
 */
describe('Reportes en el celular (T11c)', () => {
  type Modo = 'escritorio' | 'tablet' | 'celular';

  const DESDE = '2026-10-01';
  const HASTA = '2026-10-15';
  const resumen: Resumen = {
    desde: DESDE,
    hasta: HASTA,
    cantidad: 41,
    total: 670600,
    neto: 554215,
    iva: 116385,
    costo: 412300,
    ganancia: 258300,
    ventasSinCosto: 3,
    notasDeCredito: { cantidad: 2, total: 56200 },
    gananciaSinNotas: 314500,
    totalSinNotas: 726800,
  };
  const filas: Record<string, unknown[]> = {
    'por-usuario': [{ usuarioId: 2, nombre: 'Vendedor Ventas', cantidad: 2, total: 300, ganancia: 85.29, ventasSinCosto: 1 }],
    'por-medio-pago': [{ formaPago: 'Efectivo', medio: 'EFECTIVO', cantidad: 2, monto: 300 }],
    'por-periodo': [{ periodo: '2026-10-22', cantidad: 3, total: 345, ganancia: 102.48, ventasSinCosto: 1 }],
    'por-articulo': [
      { articuloId: 7, descripcion: 'Tornillo', cantidad: 3, total: 300, ganancia: 85.29, renglonesSinCosto: 1, renglones: 2, unidad: 'UNIDAD' },
    ],
    'por-rubro': [{ rubro: 'Bulones', renglones: 2, total: 300, ganancia: 85.29, renglonesSinCosto: 1 }],
    'con-descuento': [
      { ventaId: 9, numero: 15, fecha: '2026-10-22T10:30:00', usuario: 'Dueno Ventas', cliente: null, descuentoPorcentaje: 10, descuento: 5, total: 45 },
    ],
  };
  const movimientos = (): ReporteMovimientos => ({
    desde: DESDE,
    hasta: HASTA,
    filas: [
      { id: 1, fecha: '2026-10-22T09:15:00', tipo: 'INGRESO', motivo: 'Cambio', usuario: 'Vendedor Ventas', importe: 1000 },
      { id: 2, fecha: '2026-10-22T11:40:00', tipo: 'GASTO', motivo: 'Cadete', usuario: 'Vendedor Ventas', importe: 150 },
    ] as ReporteMovimientos['filas'],
    totales: { INGRESO: 1000, GASTO: 150, RETIRO: 0 },
  });

  let fixture: ComponentFixture<Reportes>;
  let servicio: Record<'resumen' | 'filas' | 'movimientosCaja', ReturnType<typeof vi.fn>>;

  async function crear(modo: Modo, dueno = true) {
    if (modo === 'celular') {
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('639.98px'), media: c }));
    } else if (modo === 'tablet') {
      vi.stubGlobal('matchMedia', (c: string) => ({ matches: c.includes('1023.98px'), media: c }));
    }
    servicio = {
      resumen: vi.fn(() => of(resumen)),
      filas: vi.fn((tipo: string) => of({ desde: DESDE, hasta: HASTA, filas: filas[tipo] })),
      movimientosCaja: vi.fn(() => of(movimientos())),
    };
    await TestBed.configureTestingModule({
      imports: [Reportes],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ReportesService, useValue: servicio },
        { provide: AuthService, useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Reportes);
    await refrescar();
  }

  async function refrescar() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.unstubAllGlobals());

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends Element = HTMLElement>(selector: string, dentro: ParentNode = el()) => dentro.querySelector<T>(selector);
  const qa = (selector: string, dentro: ParentNode = el()) => Array.from(dentro.querySelectorAll<HTMLElement>(selector));
  const hoja = () => q('app-hoja-inferior [role="dialog"]');
  const opciones = () => qa('[role="radio"]', hoja()!);
  const tecla = (key: string, destino: EventTarget = document.activeElement ?? document.body) =>
    destino.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  const dom = () => el().outerHTML.replace(/<!--[\s\S]*?-->/g, '').replace(/hoja-inferior-\d+/g, 'hoja-inferior-N').replace(/id="root\d+"/, 'id="root"');

  function escribir(id: string, valor: string) {
    const campo = q<HTMLInputElement>(`#${id}`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
  }
  function fechas(desde = DESDE, hasta = HASTA) {
    escribir('desde', desde);
    escribir('hasta', hasta);
  }
  /** Elige el reporte con lo que haya en pantalla: la hoja inferior en el celular, el `<select>` en escritorio y tablet. */
  async function elegirReporte(tipo: string) {
    const selector = q('#reporte')!;
    if (selector instanceof HTMLSelectElement) {
      selector.value = tipo;
      selector.dispatchEvent(new Event('change'));
    } else {
      selector.click();
      await refrescar();
      const nombre = REPORTES.find((r) => r.tipo === tipo)!.nombre;
      opciones().find((o) => normal(o.textContent) === nombre)!.click();
    }
    await refrescar();
  }
  async function ver() {
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await refrescar();
  }
  /** «dt dd» de cada renglón de una lista de datos (el texto pegado no separa el concepto del importe). */
  const pares = (selector: string) =>
    qa(selector).map((d) => `${normal(q('dt', d)?.textContent)} ${normal(q('dd', d)?.textContent)}`);
  const filasDelPrimerRenglon = () => qa('tbody tr:first-child td').map((td) => normal(td.textContent)).join(' | ');

  // ------------------------------------------------------------------------------------- lo mismo en los dos

  describe.each(['escritorio', 'celular'] as const)('pedidos, textos y errores (%s)', (modo) => {
    it('«Ver» manda cada reporte con sus fechas, con los literales de hoy', async () => {
      await crear(modo);
      fechas();
      servicio['resumen'].mockClear();

      await ver();
      expect(servicio['resumen']).toHaveBeenCalledTimes(1);
      expect(servicio['resumen']).toHaveBeenCalledWith('2026-10-01', '2026-10-15');

      for (const tipo of ['por-usuario', 'por-medio-pago', 'por-articulo', 'por-rubro', 'con-descuento']) {
        await elegirReporte(tipo);
        await ver();
        expect(servicio['filas']).toHaveBeenLastCalledWith(tipo, '2026-10-01', '2026-10-15', undefined);
      }
      // Sólo «Ver» consulta: elegir un reporte no pidió nada.
      expect(servicio['filas']).toHaveBeenCalledTimes(5);
    });

    it('por día o mes manda «Agrupar por» (Día por defecto, Mes si se elige)', async () => {
      await crear(modo);
      fechas();
      await elegirReporte('por-periodo');
      expect(Array.from(q<HTMLSelectElement>('#agrupar')!.options).map((o) => normal(o.textContent))).toEqual(['Día', 'Mes']);

      await ver();
      expect(servicio['filas']).toHaveBeenLastCalledWith('por-periodo', '2026-10-01', '2026-10-15', 'DIA');
      q<HTMLSelectElement>('#agrupar')!.value = 'MES';
      q<HTMLSelectElement>('#agrupar')!.dispatchEvent(new Event('change'));
      await ver();
      expect(servicio['filas']).toHaveBeenLastCalledWith('por-periodo', '2026-10-01', '2026-10-15', 'MES');
      expect(filasDelPrimerRenglon()).toBe('22/10/2026 | 3 | $ 345,00 | $ 102,48');
    });

    it('ingresos, gastos y retiros manda «Tipo» (sin tipo con Todos) y muestra los totales del servidor', async () => {
      await crear(modo);
      fechas();
      await elegirReporte('movimientos-caja');
      expect(Array.from(q<HTMLSelectElement>('#tipo-movimiento')!.options).map((o) => normal(o.textContent))).toEqual([
        'Todos',
        'Ingresos',
        'Gastos',
        'Retiros',
      ]);

      await ver();
      expect(servicio['movimientosCaja']).toHaveBeenLastCalledWith('2026-10-01', '2026-10-15', undefined);
      q<HTMLSelectElement>('#tipo-movimiento')!.value = 'GASTO';
      q<HTMLSelectElement>('#tipo-movimiento')!.dispatchEvent(new Event('change'));
      await ver();
      expect(servicio['movimientosCaja']).toHaveBeenLastCalledWith('2026-10-01', '2026-10-15', 'GASTO');
      expect(pares('.totales-caja > div')).toEqual(['Gastos $ 150,00']);
      expect(normal(q('.rango')?.textContent)).toBe('Del 01/10/2026 al 15/10/2026 · Tipo: Gastos');
      expect(normal(q('button.imprimir')?.textContent)).toBe('Imprimir');
    });

    it('el resumen conserva todos sus conceptos con los textos de hoy y la ganancia', async () => {
      await crear(modo);

      expect(pares('dl.resumen > div')).toEqual([
        'Ventas $ 726.800,00',
        'Notas de crédito 2 · -$ 56.200,00',
        'Total $ 670.600,00',
        'Neto $ 554.215,00',
        'IVA $ 116.385,00',
        'Costo $ 412.300,00',
        'Ganancia sin NC $ 314.500,00',
        'Ganancia con NC $ 258.300,00',
        'Comprobantes 41',
      ]);
      expect(normal(q('.aviso-costo')?.textContent)).toBe('3 ventas sin costo registrado: la ganancia no las incluye.');
      expect(q('button.imprimir')).toBeNull();
    });

    it('cada reporte con tabla lleva el nombre de su columna en cada celda (lo usa la tarjeta)', async () => {
      await crear(modo);
      for (const tipo of Object.keys(filas)) {
        await elegirReporte(tipo);
        await ver();
        const etiquetas = qa('tbody tr:first-child td').map((td) => td.getAttribute('data-etiqueta'));
        expect(etiquetas.every((e) => !!e), tipo).toBe(true);
        expect(qa('thead th').map((th) => normal(th.textContent)).length, tipo).toBe(etiquetas.length);
      }
      await elegirReporte('por-articulo');
      await ver();
      expect(qa('tfoot tr.fila-total > *').map((c) => normal(c.textContent))).toEqual(['Total', '', '$ 300,00', '$ 85,29']);
    });

    it('«Hasta» antes de «Desde» se avisa sin pedir nada y se lleva el resultado anterior', async () => {
      await crear(modo);
      servicio['resumen'].mockClear();
      fechas('2026-10-22', '2026-10-21');

      await ver();

      expect(servicio['resumen']).not.toHaveBeenCalled();
      expect(normal(q('[role="alert"]')?.textContent)).toBe('La fecha «Hasta» no puede ser anterior a «Desde».');
      expect(q('dl.resumen')).toBeNull();
    });

    it('un error del servidor muestra su mensaje; sin mensaje, «No se pudo cargar el reporte.»', async () => {
      await crear(modo);
      fechas();
      servicio['filas'].mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Datos inválidos', errors: { desde: 'El reporte admite hasta 366 días.' } } })),
      );
      await elegirReporte('por-rubro');
      await ver();
      expect(normal(q('[role="alert"]')?.textContent)).toBe('El reporte admite hasta 366 días.');

      servicio['filas'].mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
      await ver();
      expect(normal(q('[role="alert"]')?.textContent)).toBe('No se pudo cargar el reporte.');
      expect(q('table')).toBeNull();
    });

    it('el vendedor sólo ve el aviso: no hay formulario ni se pide nada', async () => {
      await crear(modo, false);

      expect(normal(q('p.estado-vacio')?.textContent)).toBe('Los reportes son solo del dueño.');
      expect(q('form')).toBeNull();
      expect(q('#reporte')).toBeNull();
      expect(servicio['resumen']).not.toHaveBeenCalled();
    });
  });

  // ------------------------------------------------------------------------------------- la hoja del celular

  describe('el reporte se elige en una hoja inferior (celular)', () => {
    it('«Reporte» es un botón con el reporte elegido, no un <select>, y la hoja no está abierta', async () => {
      await crear('celular');

      const selector = q('#reporte')!;
      expect(selector.tagName).toBe('BUTTON');
      expect(normal(selector.textContent)).toContain('Resumen');
      expect(selector.getAttribute('aria-haspopup')).toBe('dialog');
      expect(q('label[for="reporte"]')?.textContent?.trim()).toBe('Reporte');
      expect(hoja()).toBeNull();
      expect(q('select#reporte')).toBeNull();
    });

    it('tocarlo abre la hoja «Reporte» con los ocho tipos, en el orden y con los nombres de hoy, y marca el actual', async () => {
      await crear('celular');
      q('#reporte')!.click();
      await refrescar();

      expect(normal(q('.hoja-inferior-titulo', hoja()!)?.textContent)).toBe('Reporte');
      expect(q('[role="radiogroup"]', hoja()!)?.getAttribute('aria-label')).toBe('Reporte');
      expect(opciones().map((o) => normal(o.textContent))).toEqual([
        'Resumen',
        'Por usuario',
        'Por medio de pago',
        'Por día o mes',
        'Por artículo',
        'Por rubro',
        'Con descuento',
        'Ingresos, gastos y retiros',
      ]);
      expect(opciones().map((o) => o.getAttribute('aria-checked'))).toEqual(['true', ...Array(7).fill('false')]);
      expect(q('#reporte')!.getAttribute('aria-expanded')).toBe('true');
    });

    it('elegir uno cierra la hoja, cambia el botón y los campos propios del reporte, y no pide nada hasta «Ver»', async () => {
      await crear('celular');
      servicio['resumen'].mockClear();
      expect(q('#agrupar')).toBeNull();

      await elegirReporte('por-periodo');

      expect(hoja()).toBeNull();
      expect(normal(q('#reporte')?.textContent)).toContain('Por día o mes');
      expect(q('#agrupar')).not.toBeNull();
      expect(servicio['filas']).not.toHaveBeenCalled();
      expect(servicio['resumen']).not.toHaveBeenCalled();
      // Y al volver a abrir, la marca está en el nuevo.
      q('#reporte')!.click();
      await refrescar();
      expect(opciones().map((o) => o.getAttribute('aria-checked'))).toEqual(['false', 'false', 'false', 'true', 'false', 'false', 'false', 'false']);
    });

    it('Esc cierra la hoja sin cambiar el reporte ni pedir nada, y el foco vuelve al botón (no a un campo)', async () => {
      await crear('celular');
      servicio['resumen'].mockClear();
      q('#reporte')!.click();
      await refrescar();

      tecla('Escape');
      await refrescar();

      expect(hoja()).toBeNull();
      expect(normal(q('#reporte')?.textContent)).toContain('Resumen');
      expect(servicio['resumen']).not.toHaveBeenCalled();
      expect(document.activeElement?.tagName).not.toBe('INPUT');
      expect(document.activeElement?.tagName).not.toBe('SELECT');
    });

    it('un toque en el velo también cierra la hoja sin cambiar nada', async () => {
      await crear('celular');
      q('#reporte')!.click();
      await refrescar();

      q('.hoja-inferior-fondo')!.click();
      await refrescar();

      expect(hoja()).toBeNull();
      expect(normal(q('#reporte')?.textContent)).toContain('Resumen');
    });

    it('cada opción es un botón de la hoja (clase accion-hoja, de 52 px)', async () => {
      await crear('celular');
      q('#reporte')!.click();
      await refrescar();

      for (const opcion of opciones()) {
        expect(opcion.tagName).toBe('BUTTON');
        expect(opcion.getAttribute('type')).toBe('button');
        expect(opcion.classList).toContain('accion-hoja');
      }
    });

    it('en escritorio y en tablet sigue el <select> de hoy con sus ocho opciones y es idéntico en los dos', async () => {
      const volcados: string[] = [];
      for (const modo of ['escritorio', 'tablet'] as const) {
        TestBed.resetTestingModule();
        vi.unstubAllGlobals();
        await crear(modo);
        const selector = q<HTMLSelectElement>('select#reporte')!;
        expect(selector, modo).not.toBeNull();
        expect(Array.from(selector.options).map((o) => `${o.value}:${normal(o.textContent)}`), modo).toEqual(
          REPORTES.map((r) => `${r.tipo}:${r.nombre}`),
        );
        expect(q('app-hoja-inferior'), modo).toBeNull();
        expect(q('button#reporte'), modo).toBeNull();
        volcados.push(dom());
      }
      expect(volcados[1]).toBe(volcados[0]);
    });
  });
});
