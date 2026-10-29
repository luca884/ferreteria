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
import { ReporteMovimientos, Resumen } from './reportes.models';
import { ReportesService } from './reportes.service';

registerLocaleData(localeEsAr);

describe('Reportes de ventas', () => {
  const resumen: Resumen = {
    desde: '2026-10-22',
    hasta: '2026-10-22',
    cantidad: 3,
    total: 345,
    neto: 285.13,
    iva: 59.87,
    costo: 100,
    ganancia: 102.48,
    ventasSinCosto: 1,
    notasDeCredito: { cantidad: 1, total: 121 },
    gananciaSinNotas: 123.48,
  };

  const filas: Record<string, unknown[]> = {
    'por-usuario': [{ usuarioId: 2, nombre: 'Vendedor Ventas', cantidad: 2, total: 300, ganancia: 85.29, ventasSinCosto: 1 }],
    'por-medio-pago': [{ formaPago: 'Efectivo', medio: 'EFECTIVO', cantidad: 2, monto: 300 }],
    'por-periodo': [{ periodo: '2026-10-22', cantidad: 3, total: 345, ganancia: 102.48, ventasSinCosto: 1 }],
    'por-articulo': [
      { articuloId: 7, descripcion: 'Tornillo', cantidad: 3, total: 300, ganancia: 85.29, renglonesSinCosto: 1, renglones: 2 },
    ],
    'por-rubro': [{ rubro: 'Bulones', renglones: 2, total: 300, ganancia: 85.29, renglonesSinCosto: 1 }],
    'con-descuento': [
      {
        ventaId: 9,
        numero: 15,
        fecha: '2026-10-22T10:30:00',
        usuario: 'Dueno Ventas',
        cliente: null,
        descuentoPorcentaje: 10,
        descuento: 5,
        total: 45,
      },
    ],
  };

  /** Los movimientos de caja de hoy; con un tipo, solo ese tipo y los otros totales en 0, como el servidor. */
  const movimientos = (tipo?: string): ReporteMovimientos => {
    const todas = [
      { id: 1, fecha: '2026-10-22T09:15:00', tipo: 'INGRESO', motivo: 'Cambio', usuario: 'Vendedor Ventas', importe: 1000 },
      { id: 2, fecha: '2026-10-22T11:40:00', tipo: 'GASTO', motivo: 'Cadete', usuario: 'Vendedor Ventas', importe: 150 },
      { id: 3, fecha: '2026-10-22T12:05:00', tipo: 'RETIRO', motivo: 'Banco', usuario: 'Dueno Ventas', importe: 100 },
    ] as ReporteMovimientos['filas'];
    const mostradas = todas.filter((f) => !tipo || f.tipo === tipo);
    const suma = (t: string) => mostradas.filter((f) => f.tipo === t).reduce((total, f) => total + f.importe, 0);
    return {
      desde: '2026-10-22',
      hasta: '2026-10-22',
      filas: mostradas,
      totales: { INGRESO: suma('INGRESO'), GASTO: suma('GASTO'), RETIRO: suma('RETIRO') },
    };
  };

  let fixture: ComponentFixture<Reportes>;
  let servicio: Record<'resumen' | 'filas' | 'movimientosCaja', ReturnType<typeof vi.fn>>;

  async function crear(dueno = true) {
    servicio = {
      resumen: vi.fn(() => of(resumen)),
      filas: vi.fn((tipo: string) => of({ desde: '2026-10-22', hasta: '2026-10-22', filas: filas[tipo] })),
      movimientosCaja: vi.fn((_desde: string, _hasta: string, tipo?: string) => of(movimientos(tipo))),
    };
    await TestBed.configureTestingModule({
      imports: [Reportes],
      providers: [
        provideRouter([]),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ReportesService, useValue: servicio },
        {
          provide: AuthService,
          useValue: { esDueno: signal(dueno), sesion: signal({ ferreteriaNombre: 'Ferretería El Tornillo' }) },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Reportes);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const normal = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();
  const select = (id: string) => el().querySelector<HTMLSelectElement>(`#${id}`)!;
  const input = (id: string) => el().querySelector<HTMLInputElement>(`#${id}`)!;
  const primeraFila = () =>
    Array.from(el().querySelectorAll('tbody tr:first-child td'))
      .map((td) => normal(td.textContent))
      .join(' ');

  async function elegir(tipo: string) {
    select('reporte').value = tipo;
    select('reporte').dispatchEvent(new Event('change'));
    fixture.detectChanges();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('arranca con el resumen de hoy: ventas, costo, IVA, ganancia, comprobantes y el aviso de las ventas sin costo', async () => {
    await crear();

    expect(servicio['resumen']).toHaveBeenCalledWith(input('desde').value, input('hasta').value);
    expect(input('desde').value).toBe(input('hasta').value);
    const texto = Array.from(el().querySelectorAll('.resumen > div'))
      .map((d) => `${normal(d.querySelector('dt')?.textContent)} ${normal(d.querySelector('dd')?.textContent)}`)
      .join(' · ');
    expect(texto).toContain('Ventas $ 345,00');
    expect(texto).toContain('Costo $ 100,00');
    expect(texto).toContain('IVA $ 59,87');
    expect(texto).toContain('Ganancia con NC $ 102,48');
    expect(texto).toContain('Comprobantes 3');
    expect(normal(el().querySelector('.aviso-costo')?.textContent)).toBe(
      '1 venta sin costo registrado: la ganancia no la incluye.',
    );
  });

  it('el resumen suma el renglón «Notas de crédito» con cuántas y lo que restaron, y la ganancia sin NC y con NC (CU-08)', async () => {
    await crear();

    const texto = Array.from(el().querySelectorAll('.resumen > div')).map(
      (d) => `${normal(d.querySelector('dt')?.textContent)} ${normal(d.querySelector('dd')?.textContent)}`,
    );
    expect(texto).toEqual([
      'Ventas $ 345,00',
      'Notas de crédito 1 · -$ 121,00',
      'Neto $ 285,13',
      'IVA $ 59,87',
      'Costo $ 100,00',
      'Ganancia sin NC $ 123,48',
      'Ganancia con NC $ 102,48',
      'Comprobantes 3',
    ]);
  });

  it('un grupo con solo notas de crédito se ve con 0 ventas y el total y la ganancia en negativo', async () => {
    await crear();
    servicio['filas'].mockReturnValue(
      of({
        desde: '2026-10-22',
        hasta: '2026-10-22',
        filas: [{ usuarioId: 2, nombre: 'Dueno Ventas', cantidad: 0, total: -121, ganancia: -21, ventasSinCosto: 0 }],
      }),
    );
    await elegir('por-usuario');

    expect(primeraFila()).toBe('Dueno Ventas 0 -$ 121,00 -$ 21,00');
  });

  it('ofrece los seis reportes de ventas, como el menú Ventas de GDS, y el de ingresos, gastos y retiros de la caja', async () => {
    await crear();

    const opciones = Array.from(select('reporte').options).map((o) => o.textContent?.trim());
    expect(opciones).toEqual([
      'Resumen',
      'Por usuario',
      'Por medio de pago',
      'Por día o mes',
      'Por artículo',
      'Por rubro',
      'Con descuento',
      'Ingresos, gastos y retiros',
    ]);
  });

  it('por usuario muestra cada usuario con cantidad, total y ganancia', async () => {
    await crear();
    await elegir('por-usuario');

    expect(servicio['filas']).toHaveBeenCalledWith('por-usuario', input('desde').value, input('hasta').value, undefined);
    const fila = primeraFila();
    expect(fila).toBe('Vendedor Ventas 2 $ 300,00 $ 85,29');
    expect(normal(el().querySelector('.aviso-costo')?.textContent)).toBe(
      '1 venta sin costo registrado: la ganancia no la incluye.',
    );
  });

  it('por medio de pago muestra la forma de pago, los pagos y el monto', async () => {
    await crear();
    await elegir('por-medio-pago');

    expect(primeraFila()).toBe('Efectivo 2 $ 300,00');
  });

  it('por día o mes deja elegir cómo agrupar y lo manda', async () => {
    await crear();
    select('reporte').value = 'por-periodo';
    select('reporte').dispatchEvent(new Event('change'));
    fixture.detectChanges();
    select('agrupar').value = 'MES';
    select('agrupar').dispatchEvent(new Event('change'));
    await elegir('por-periodo');

    expect(servicio['filas']).toHaveBeenCalledWith('por-periodo', input('desde').value, input('hasta').value, 'MES');
    expect(primeraFila()).toBe('22/10/2026 3 $ 345,00 $ 102,48');
  });

  it('por artículo muestra la cantidad vendida y avisa los renglones sin costo', async () => {
    await crear();
    await elegir('por-articulo');

    expect(primeraFila()).toBe('Tornillo 3 $ 300,00 $ 85,29');
    expect(normal(el().querySelector('.aviso-costo')?.textContent)).toBe(
      '1 renglón sin costo registrado: la ganancia no lo incluye.',
    );
  });

  it('por rubro muestra el rubro con sus renglones', async () => {
    await crear();
    await elegir('por-rubro');

    expect(primeraFila()).toBe('Bulones 2 $ 300,00 $ 85,29');
  });

  it('con descuento muestra la venta, quién la hizo, el descuento y el total', async () => {
    await crear();
    await elegir('con-descuento');

    expect(primeraFila()).toBe(
      '15 22/10 10:30 Dueno Ventas Consumidor Final 10 % $ 5,00 $ 45,00',
    );
  });

  it('sin filas dice que no hubo ventas en el rango', async () => {
    await crear();
    servicio['filas'].mockReturnValue(of({ desde: '2026-10-22', hasta: '2026-10-22', filas: [] }));
    await elegir('por-usuario');

    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hubo ventas entre esas fechas.');
  });

  it('«Hasta» antes de «Desde» se avisa sin pedir nada al servidor', async () => {
    await crear();
    servicio['resumen'].mockClear();
    input('desde').value = '2026-10-22';
    input('desde').dispatchEvent(new Event('input'));
    input('hasta').value = '2026-10-21';
    input('hasta').dispatchEvent(new Event('input'));
    await elegir('resumen');

    expect(servicio['resumen']).not.toHaveBeenCalled();
    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe(
      'La fecha «Hasta» no puede ser anterior a «Desde».',
    );
  });

  it('un error del servidor se muestra con su mensaje', async () => {
    await crear();
    servicio['filas'].mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Datos inválidos', errors: { desde: 'El reporte admite hasta 366 días.' } } })),
    );
    await elegir('por-rubro');

    expect(normal(el().querySelector('[role="alert"]')?.textContent)).toBe('El reporte admite hasta 366 días.');
  });

  it('el vendedor ve que los reportes son del dueño y no se pide nada', async () => {
    await crear(false);

    expect(servicio['resumen']).not.toHaveBeenCalled();
    expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('Los reportes son solo del dueño.');
  });

  describe('ingresos, gastos y retiros de la caja (G73)', () => {
    const tipoMovimiento = () => el().querySelector<HTMLSelectElement>('#tipo-movimiento');
    const totales = () =>
      Array.from(el().querySelectorAll('.totales-caja > div')).map(
        (d) => `${normal(d.querySelector('dt')?.textContent)} ${normal(d.querySelector('dd')?.textContent)}`,
      );

    it('el título de la pantalla es «Reportes»', async () => {
      await crear();

      expect(normal(el().querySelector('h1')?.textContent)).toBe('Reportes');
    });

    it('el campo «Tipo» aparece solo con este reporte y ofrece Todos, Ingresos, Gastos y Retiros', async () => {
      await crear();
      expect(tipoMovimiento()).toBeNull();

      await elegir('movimientos-caja');

      expect(Array.from(tipoMovimiento()!.options).map((o) => o.textContent?.trim())).toEqual([
        'Todos',
        'Ingresos',
        'Gastos',
        'Retiros',
      ]);
      expect(tipoMovimiento()!.value).toBe('');
    });

    it('con Todos muestra fecha y hora, tipo, motivo, usuario e importe, y los tres totales', async () => {
      await crear();
      await elegir('movimientos-caja');

      expect(servicio['movimientosCaja']).toHaveBeenCalledWith(input('desde').value, input('hasta').value, undefined);
      expect(primeraFila()).toBe('22/10/2026 09:15 Ingreso Cambio Vendedor Ventas $ 1.000,00');
      expect(el().querySelectorAll('tbody tr')).toHaveLength(3);
      expect(totales()).toEqual(['Ingresos $ 1.000,00', 'Gastos $ 150,00', 'Retiros $ 100,00']);
    });

    it('con un tipo lo manda al servidor y muestra solo el total de ese tipo', async () => {
      await crear();
      select('reporte').value = 'movimientos-caja';
      select('reporte').dispatchEvent(new Event('change'));
      fixture.detectChanges();
      tipoMovimiento()!.value = 'GASTO';
      tipoMovimiento()!.dispatchEvent(new Event('change'));
      await elegir('movimientos-caja');

      expect(servicio['movimientosCaja']).toHaveBeenCalledWith(input('desde').value, input('hasta').value, 'GASTO');
      expect(primeraFila()).toBe('22/10/2026 11:40 Gasto Cadete Vendedor Ventas $ 150,00');
      expect(totales()).toEqual(['Gastos $ 150,00']);
    });

    it('sin movimientos lo dice y los totales valen 0', async () => {
      await crear();
      servicio['movimientosCaja'].mockReturnValue(
        of({ desde: '2026-10-22', hasta: '2026-10-22', filas: [], totales: { INGRESO: 0, GASTO: 0, RETIRO: 0 } }),
      );
      await elegir('movimientos-caja');

      expect(normal(el().querySelector('.estado-vacio')?.textContent)).toBe('No hubo movimientos en esas fechas.');
      expect(totales()).toEqual(['Ingresos $ 0,00', 'Gastos $ 0,00', 'Retiros $ 0,00']);
    });

    it('«Imprimir» está solo con este reporte y llama a window.print', async () => {
      await crear();
      expect(el().querySelector('app-barra-acciones button.imprimir')).toBeNull();
      await elegir('movimientos-caja');
      const imprimir = vi.spyOn(window, 'print').mockImplementation(() => undefined);

      el().querySelector<HTMLButtonElement>('app-barra-acciones button.imprimir')!.click();

      expect(imprimir).toHaveBeenCalledOnce();
    });

    it('la hoja lleva la ferretería, el título, el rango y el tipo del resultado, y cuándo se emitió', async () => {
      await crear();
      await elegir('movimientos-caja');

      const hoja = el().querySelector('section.caja-imprimible')!;
      expect(normal(hoja.querySelector('.ferreteria')?.textContent)).toBe('Ferretería El Tornillo');
      expect(normal(hoja.querySelector('h2')?.textContent)).toBe('Ingresos, gastos y retiros');
      expect(normal(hoja.querySelector('.rango')?.textContent)).toBe('Del 22/10/2026 al 22/10/2026 · Tipo: Todos');
      expect(normal(hoja.querySelector('.emitido')?.textContent)).toMatch(/^Emitido el \d{2}\/\d{2}\/\d{4} \d{2}:\d{2}$/);
    });

    it('la hoja dice el tipo y el rango que se pidieron, no los que quedaron en el formulario después', async () => {
      await crear();
      select('reporte').value = 'movimientos-caja';
      select('reporte').dispatchEvent(new Event('change'));
      fixture.detectChanges();
      tipoMovimiento()!.value = 'RETIRO';
      tipoMovimiento()!.dispatchEvent(new Event('change'));
      await elegir('movimientos-caja');
      tipoMovimiento()!.value = 'INGRESO';
      tipoMovimiento()!.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(normal(el().querySelector('section.caja-imprimible .rango')?.textContent)).toBe(
        'Del 22/10/2026 al 22/10/2026 · Tipo: Retiros',
      );
    });

    it('un error del servidor se muestra y no queda ninguna hoja para imprimir', async () => {
      await crear();
      servicio['movimientosCaja'].mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Datos inválidos' } })),
      );
      await elegir('movimientos-caja');

      expect(el().querySelector('section.caja-imprimible')).toBeNull();
      expect(el().querySelector('app-barra-acciones button.imprimir')).toBeNull();
    });
  });
});
